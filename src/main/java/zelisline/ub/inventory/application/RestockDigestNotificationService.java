package zelisline.ub.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.domain.RestockRun;
import zelisline.ub.inventory.domain.RestockSuggestion;
import zelisline.ub.inventory.repository.RestockRunRepository;
import zelisline.ub.inventory.repository.RestockSuggestionRepository;
import zelisline.ub.notifications.application.NotificationOutboxService;
import zelisline.ub.opsalerts.application.TenantOpsAlertDispatcher;
import zelisline.ub.opsalerts.domain.OpsAlertType;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.repository.BranchRepository;

/**
 * Phase-2 notify: turns a freshly generated {@link RestockRun} into an in-app digest
 * (outbox → orchestrator → notification bell) and an owner WhatsApp/SMS ops alert
 * (top lines by value, then "…and N more").
 */
@Service
@RequiredArgsConstructor
public class RestockDigestNotificationService {

    private static final int WHATSAPP_TOP_LINES = 5;

    private final RestockRunRepository restockRunRepository;
    private final RestockSuggestionRepository restockSuggestionRepository;
    private final ItemRepository itemRepository;
    private final NotificationOutboxService notificationOutboxService;
    private final TenantOpsAlertDispatcher tenantOpsAlertDispatcher;
    private final BranchRepository branchRepository;

    /**
     * Enqueue the in-app digest + dispatch the owner ops alert for a run. Idempotent:
     * only fires while the run is still {@code generated}; afterwards it's a no-op.
     */
    @Transactional
    public void notifyRun(String businessId, String runId) {
        RestockRun run = restockRunRepository.findByIdAndBusinessId(runId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
        if (!InventoryConstants.DIGEST_RUN_GENERATED.equals(run.getStatus())) {
            return; // already notified / accepted / expired
        }

        List<RestockSuggestion> suggestions =
                restockSuggestionRepository.findByRunIdOrderBySuggestedQtyDescIdAsc(runId);

        String branchName = branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(run.getBranchId(), businessId)
                .map(Branch::getName)
                .filter(n -> n != null && !n.isBlank())
                .orElse("branch");
        String currency = run.getCurrency() != null ? run.getCurrency().trim() : "KES";
        long supplierCount = suggestions.stream()
                .map(RestockSuggestion::getSupplierId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .count();

        notificationOutboxService.enqueueRestockDigest(
                businessId,
                run.getBranchId(),
                run.getId(),
                branchName,
                String.valueOf(run.getLineCount()),
                money(run.getEstTotal()),
                currency,
                String.valueOf(supplierCount));

        if (!suggestions.isEmpty()) {
            dispatchOpsAlert(businessId, run, suggestions, branchName, currency);
        }

        run.setStatus(InventoryConstants.DIGEST_RUN_NOTIFIED);
        restockRunRepository.save(run);
    }

    private void dispatchOpsAlert(
            String businessId,
            RestockRun run,
            List<RestockSuggestion> suggestions,
            String branchName,
            String currency
    ) {
        Set<String> itemIds = suggestions.stream()
                .map(RestockSuggestion::getItemId)
                .collect(Collectors.toSet());
        java.util.Map<String, Item> itemsById = itemRepository.findAllById(itemIds).stream()
                .collect(Collectors.toMap(Item::getId, i -> i, (a, b) -> a));

        List<RestockSuggestion> top = suggestions.stream()
                .sorted(Comparator.comparing(this::lineValue, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(WHATSAPP_TOP_LINES)
                .toList();

        StringBuilder msg = new StringBuilder("Tonight's list — ")
                .append(branchName)
                .append(": ")
                .append(run.getLineCount())
                .append(" items · ~")
                .append(currency)
                .append(" ")
                .append(money(run.getEstTotal()));
        for (RestockSuggestion s : top) {
            Item item = itemsById.get(s.getItemId());
            String name = item != null && item.getName() != null ? item.getName() : s.getItemId();
            msg.append("\n• ").append(name)
                    .append(" × ").append(s.getSuggestedQty() == null ? "0" : s.getSuggestedQty().stripTrailingZeros().toPlainString());
        }
        if (suggestions.size() > WHATSAPP_TOP_LINES) {
            msg.append("\n…and ")
                    .append(suggestions.size() - WHATSAPP_TOP_LINES)
                    .append(" more. Open Palmart to review.");
        }
        tenantOpsAlertDispatcher.dispatch(businessId, OpsAlertType.RESTOCK_DIGEST, msg.toString());
    }

    private BigDecimal lineValue(RestockSuggestion s) {
        if (s.getSuggestedQty() == null || s.getUnitCost() == null) {
            return BigDecimal.ZERO;
        }
        return s.getSuggestedQty().multiply(s.getUnitCost());
    }

    private static String money(BigDecimal amount) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.UK);
        nf.setMaximumFractionDigits(0);
        return nf.format(value.setScale(0, RoundingMode.HALF_UP));
    }
}
