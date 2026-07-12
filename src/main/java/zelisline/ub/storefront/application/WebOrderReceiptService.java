package zelisline.ub.storefront.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.sales.receipt.ReceiptEscPosRenderer;
import zelisline.ub.sales.receipt.ReceiptLineRow;
import zelisline.ub.sales.receipt.ReceiptPaymentRow;
import zelisline.ub.sales.receipt.ReceiptSnapshot;
import zelisline.ub.storefront.api.dto.WebOrderPickupTicketClaimResponse;
import zelisline.ub.storefront.domain.WebOrder;
import zelisline.ub.storefront.domain.WebOrderLine;
import zelisline.ub.storefront.repository.WebOrderLineRepository;
import zelisline.ub.storefront.repository.WebOrderRepository;
import zelisline.ub.tenancy.api.dto.BranchReceiptSettingsResponse;
import zelisline.ub.tenancy.application.BranchReceiptSettingsService;
import zelisline.ub.tenancy.application.StorefrontSettingsService;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class WebOrderReceiptService {

    private static final int MONEY_SCALE = 2;
    /** Auto-print window for cashier pickup tickets. */
    public static final long PICKUP_TICKET_AUTO_PRINT_MAX_AGE_SECONDS = 60L * 60L;

    private final WebOrderRepository webOrderRepository;
    private final WebOrderLineRepository webOrderLineRepository;
    private final BusinessRepository businessRepository;
    private final BranchRepository branchRepository;
    private final ItemRepository itemRepository;
    private final BranchReceiptSettingsService branchReceiptSettingsService;
    private final StorefrontSettingsService storefrontSettingsService;

    /**
     * Atomically claim a one-time auto-print. Returns {@code claimed=true} only for the
     * first successful claim on an order younger than one hour.
     */
    @Transactional
    public WebOrderPickupTicketClaimResponse claimPickupTicketPrint(String businessId, String orderId) {
        String id = orderId == null ? "" : orderId.trim();
        if (id.isBlank()) {
            return new WebOrderPickupTicketClaimResponse(false, "not_found");
        }
        WebOrder order = webOrderRepository.findByIdAndBusinessId(id, businessId).orElse(null);
        if (order == null) {
            return new WebOrderPickupTicketClaimResponse(false, "not_found");
        }
        if (order.getPickupTicketPrintedAt() != null) {
            return new WebOrderPickupTicketClaimResponse(false, "already_printed");
        }
        Instant now = Instant.now();
        Instant minCreatedAt = now.minusSeconds(PICKUP_TICKET_AUTO_PRINT_MAX_AGE_SECONDS);
        if (order.getCreatedAt() == null || order.getCreatedAt().isBefore(minCreatedAt)) {
            return new WebOrderPickupTicketClaimResponse(false, "too_old");
        }
        int updated = webOrderRepository.claimPickupTicketPrint(id, businessId, now, minCreatedAt);
        if (updated == 1) {
            return new WebOrderPickupTicketClaimResponse(true, "claimed");
        }
        // Lost a race with another till, or age window closed mid-claim.
        WebOrder again = webOrderRepository.findByIdAndBusinessId(id, businessId).orElse(null);
        if (again != null && again.getPickupTicketPrintedAt() != null) {
            return new WebOrderPickupTicketClaimResponse(false, "already_printed");
        }
        return new WebOrderPickupTicketClaimResponse(false, "too_old");
    }

    public byte[] buildEscPos(String businessId, String orderId, int widthMm) {
        if (widthMm != 50 && widthMm != 58 && widthMm != 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "widthMm must be 50, 58, or 80");
        }
        return ReceiptEscPosRenderer.render(loadSnapshot(businessId, orderId), widthMm);
    }

    private ReceiptSnapshot loadSnapshot(String businessId, String orderId) {
        WebOrder order = webOrderRepository.findByIdAndBusinessId(orderId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        Business business = businessRepository.findById(businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found"));
        Branch branch = branchRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(order.getCatalogBranchId(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found"));

        List<WebOrderLine> items = webOrderLineRepository.findByOrderIdOrderByLineIndexAsc(order.getId());
        List<String> itemIds = items.stream().map(WebOrderLine::getItemId).distinct().toList();
        Map<String, Item> itemMap = itemRepository.findAllById(itemIds).stream()
                .filter(i -> businessId.equals(i.getBusinessId()))
                .collect(Collectors.toMap(Item::getId, i -> i));

        List<ReceiptLineRow> lines = new ArrayList<>();
        for (WebOrderLine ol : items) {
            Item it = itemMap.get(ol.getItemId());
            String desc = ol.getItemName() != null && !ol.getItemName().isBlank()
                    ? ol.getItemName()
                    : (it != null ? it.getName() : "Item");
            String variant = ol.getVariantName();
            if (variant != null && !variant.isBlank()) {
                desc = desc + " (" + variant + ")";
            }
            lines.add(new ReceiptLineRow(
                    desc,
                    ol.getQuantity().stripTrailingZeros().toPlainString(),
                    it != null ? it.getUnitType() : null,
                    money(ol.getUnitPrice()),
                    money(ol.getLineTotal())
            ));
        }

        ZoneId zone = ZoneId.of(blankToDefault(business.getTimezone(), "UTC"));
        String placedAt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")
                .withZone(zone)
                .format(order.getCreatedAt());

        BranchReceiptSettingsResponse receiptSettings =
                branchReceiptSettingsService.read(branch.getReceiptSettings());
        String logoUrl = storefrontSettingsService
                .readTenantConfig(business.getSettings(), business.getName())
                .branding()
                .logoUrl();

        String shortId = order.getId().length() > 8 ? order.getId().substring(0, 8) : order.getId();
        String paymentLabel = blankToDefault(order.getStatus(), "pending_payment")
                .replace('_', ' ');

        return new ReceiptSnapshot(
                business.getName(),
                blankToNull(logoUrl),
                branch.getName(),
                blankToNull(branch.getAddress()),
                receiptSettings.phone(),
                receiptSettings.email(),
                receiptSettings.website(),
                receiptSettings.tillNumber(),
                receiptSettings.footerNote(),
                null,
                blankToDefault(
                        order.getCurrency() != null ? order.getCurrency() : business.getCurrency(),
                        "KES"
                ).trim().toUpperCase(Locale.ROOT),
                order.getId(),
                null,
                "Web Order " + shortId,
                placedAt,
                order.getStatus(),
                blankToNull(order.getCustomerName()),
                blankToNull(order.getCustomerPhone()),
                lines,
                List.of(new ReceiptPaymentRow(paymentLabel, money(order.getGrandTotal()), null)),
                money(order.getGrandTotal()),
                null,
                null,
                "Online order — prepare for pickup"
        );
    }

    private static String blankToNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    private static String money(BigDecimal v) {
        return v.setScale(MONEY_SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    private static String blankToDefault(String raw, String def) {
        if (raw == null || raw.isBlank()) {
            return def;
        }
        return raw.trim();
    }
}
