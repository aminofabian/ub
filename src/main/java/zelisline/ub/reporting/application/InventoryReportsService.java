package zelisline.ub.reporting.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.reporting.api.dto.InventoryExpiryPipelineResponse;
import zelisline.ub.reporting.api.dto.InventoryValuationResponse;
import zelisline.ub.reporting.repository.MvInventorySnapshotRepository;
import zelisline.ub.reporting.repository.MvInventorySnapshotRepository.ValuationRow;
import zelisline.ub.tenancy.repository.BranchRepository;

@Service
@RequiredArgsConstructor
public class InventoryReportsService {

    private static final BigDecimal QTY_ZERO = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    private static final BigDecimal MONEY_ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final MvInventorySnapshotRepository snapshotRepository;
    private final InventoryBatchRepository inventoryBatchRepository;
    private final ItemRepository itemRepository;
    private final BranchRepository branchRepository;

    @Transactional(readOnly = true)
    public InventoryValuationResponse valuation(String businessId, String branchId) {
        String resolvedBranch = resolveBranch(businessId, branchId);
        List<InventoryValuationResponse.Row> rows = new ArrayList<>();
        BigDecimal totalQty = QTY_ZERO;
        BigDecimal totalVal = MONEY_ZERO;
        for (ValuationRow r : snapshotRepository.listValuation(businessId, resolvedBranch)) {
            String name = itemRepository.findById(r.getItemId())
                    .filter(i -> businessId.equals(i.getBusinessId()))
                    .map(Item::getName)
                    .orElse("(unknown item)");
            BigDecimal qty = r.getQtyOnHand() == null ? QTY_ZERO : r.getQtyOnHand().setScale(4, RoundingMode.HALF_UP);
            BigDecimal val = r.getFifoValue() == null ? MONEY_ZERO : r.getFifoValue().setScale(2, RoundingMode.HALF_UP);
            rows.add(new InventoryValuationResponse.Row(
                    r.getBranchId(),
                    r.getItemId(),
                    name,
                    qty,
                    val,
                    r.getEarliestExpiry()
            ));
            totalQty = totalQty.add(qty);
            totalVal = totalVal.add(val);
        }
        return new InventoryValuationResponse(
                resolvedBranch,
                List.copyOf(rows),
                totalQty.setScale(4, RoundingMode.HALF_UP),
                totalVal.setScale(2, RoundingMode.HALF_UP)
        );
    }

    @Transactional(readOnly = true)
    public InventoryExpiryPipelineResponse expiryPipeline(String businessId, String branchId, LocalDate today) {
        String resolvedBranch = resolveBranch(businessId, branchId);
        LocalDate asOf = today != null ? today : LocalDate.now();
        LocalDate horizon = asOf.plusDays(365);
        List<InventoryBatch> batches = inventoryBatchRepository.findExpiringOnOrBefore(
                businessId,
                resolvedBranch,
                horizon
        );

        Map<String, Acc> acc = new LinkedHashMap<>();
        for (String k : List.of("expired", "due_7d", "due_30d", "due_90d", "later")) {
            acc.put(k, new Acc());
        }

        for (InventoryBatch b : batches) {
            if (b.getExpiryDate() == null || b.getQuantityRemaining().signum() <= 0) {
                continue;
            }
            String bucket = classify(asOf, b.getExpiryDate());
            BigDecimal qty = b.getQuantityRemaining().setScale(4, RoundingMode.HALF_UP);
            acc.get(bucket).add(qty);
        }

        Map<String, InventoryExpiryPipelineResponse.Bucket> buckets = new LinkedHashMap<>();
        acc.forEach((k, v) -> buckets.put(k, new InventoryExpiryPipelineResponse.Bucket(v.count, v.qty)));
        return new InventoryExpiryPipelineResponse(resolvedBranch, buckets);
    }

    private static String classify(LocalDate today, LocalDate expiry) {
        long days = ChronoUnit.DAYS.between(today, expiry);
        if (days < 0) {
            return "expired";
        }
        if (days <= 7) {
            return "due_7d";
        }
        if (days <= 30) {
            return "due_30d";
        }
        if (days <= 90) {
            return "due_90d";
        }
        return "later";
    }

    private static final class Acc {
        private long count;
        private BigDecimal qty = QTY_ZERO;

        void add(BigDecimal q) {
            count++;
            qty = qty.add(q).setScale(4, RoundingMode.HALF_UP);
        }
    }

    private String resolveBranch(String businessId, String branchId) {
        if (branchId == null || branchId.isBlank()) {
            return null;
        }
        String trimmed = branchId.trim();
        branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(trimmed, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch not found"));
        return trimmed;
    }
}
