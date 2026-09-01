package zelisline.ub.sales.receipt;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.application.ProductDisplayName;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.domain.SaleItem;
import zelisline.ub.sales.domain.SalePayment;
import zelisline.ub.sales.repository.SaleItemRepository;
import zelisline.ub.sales.repository.SalePaymentRepository;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.sales.application.SaleActorNameService;
import zelisline.ub.tenancy.api.dto.BranchReceiptSettingsResponse;
import zelisline.ub.tenancy.application.BranchReceiptSettingsService;
import zelisline.ub.tenancy.application.StorefrontSettingsService;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class SaleReceiptService {

    private static final int MONEY_SCALE = 2;

    private final SaleRepository saleRepository;
    private final SaleItemRepository saleItemRepository;
    private final SalePaymentRepository salePaymentRepository;
    private final BusinessRepository businessRepository;
    private final BranchRepository branchRepository;
    private final ItemRepository itemRepository;
    private final SaleActorNameService saleActorNameService;
    private final BranchReceiptSettingsService branchReceiptSettingsService;
    private final StorefrontSettingsService storefrontSettingsService;
    private final CustomerRepository customerRepository;
    private final CustomerPhoneRepository customerPhoneRepository;

    public byte[] buildPdf(String businessId, String saleId) {
        return ReceiptPdfRenderer.render(loadSnapshot(businessId, saleId, null));
    }

    public byte[] buildEscPos(String businessId, String saleId, int widthMm) {
        return buildEscPos(businessId, saleId, widthMm, null);
    }

    public byte[] buildEscPos(String businessId, String saleId, int widthMm, BigDecimal cashReceivedOverride) {
        if (widthMm != 50 && widthMm != 58 && widthMm != 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "widthMm must be 50, 58, or 80");
        }
        return ReceiptEscPosRenderer.render(loadSnapshot(businessId, saleId, cashReceivedOverride), widthMm);
    }

    private ReceiptSnapshot loadSnapshot(String businessId, String saleId) {
        return loadSnapshot(businessId, saleId, null);
    }

    private ReceiptSnapshot loadSnapshot(String businessId, String saleId, BigDecimal cashReceivedOverride) {
        Sale sale = saleRepository.findByIdAndBusinessId(saleId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sale not found"));
        Business business = businessRepository.findById(businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found"));
        Branch branch = branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(sale.getBranchId(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found"));

        List<SaleItem> items = saleItemRepository.findBySaleIdOrderByLineIndexAsc(sale.getId());
        List<SalePayment> pays = salePaymentRepository.findBySaleIdOrderBySortOrderAsc(sale.getId());

        List<String> itemIds = items.stream()
                .map(SaleItem::getItemId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        Map<String, Item> itemMap = itemIds.isEmpty()
                ? Map.of()
                : itemRepository.findAllById(itemIds).stream()
                .filter(i -> businessId.equals(i.getBusinessId()))
                .collect(Collectors.toMap(Item::getId, i -> i));
        Set<String> parentIds = new HashSet<>();
        for (Item row : itemMap.values()) {
            String parentId = row.getVariantOfItemId();
            if (parentId != null && !parentId.isBlank()) {
                parentIds.add(parentId);
            }
        }
        Map<String, String> parentNameById = new LinkedHashMap<>();
        if (!parentIds.isEmpty()) {
            for (Item parent : itemRepository.findByIdInAndBusinessIdAndDeletedAtIsNull(parentIds, businessId)) {
                parentNameById.put(parent.getId(), parent.getName());
            }
        }

        List<ReceiptLineRow> lines = new ArrayList<>();
        for (SaleItem si : items) {
            if (si.isAirtime()) {
                String desc = si.getLineLabel() != null && !si.getLineLabel().isBlank()
                        ? si.getLineLabel()
                        : "Airtime";
                lines.add(new ReceiptLineRow(
                        desc,
                        si.getQuantity().stripTrailingZeros().toPlainString(),
                        null,
                        money(si.getUnitPrice()),
                        money(si.getLineTotal())
                ));
                continue;
            }
            Item it = itemMap.get(si.getItemId());
            String parentName = it != null && it.getVariantOfItemId() != null
                    ? parentNameById.get(it.getVariantOfItemId())
                    : null;
            String desc = it != null
                    ? ProductDisplayName.forVariant(it, parentName)
                    : "Item";
            lines.add(new ReceiptLineRow(
                    desc,
                    si.getQuantity().stripTrailingZeros().toPlainString(),
                    it != null ? it.getUnitType() : null,
                    money(si.getUnitPrice()),
                    money(si.getLineTotal())
            ));
        }

        List<ReceiptPaymentRow> payments = new ArrayList<>();
        for (SalePayment p : pays) {
            payments.add(new ReceiptPaymentRow(
                    p.getMethod(),
                    money(p.getAmount()),
                    p.getReference()
            ));
        }

        String customerName = null;
        String customerPhone = null;
        String customerId = sale.getCustomerId();
        if (customerId != null && !customerId.isBlank()) {
            customerName = customerRepository
                    .findByIdAndBusinessIdAndDeletedAtIsNull(customerId.trim(), businessId)
                    .map(Customer::getName)
                    .map(String::trim)
                    .filter(name -> !name.isBlank())
                    .orElse(null);
            customerPhone = resolvePrimaryPhone(customerId.trim());
        }

        ZoneId zone = ZoneId.of(blankToDefault(business.getTimezone(), "UTC"));
        String soldAt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")
                .withZone(zone)
                .format(sale.getSoldAt());

        String footer = footerNote(sale);
        BranchReceiptSettingsResponse receiptSettings =
                branchReceiptSettingsService.read(branch.getReceiptSettings());
        String logoUrl = storefrontSettingsService
                .readTenantConfig(business.getSettings(), business.getName())
                .branding()
                .logoUrl();

        String cashReceivedDisplay = null;
        String changeGivenDisplay = null;
        BigDecimal cashReceived = resolveCashReceivedForReceipt(
                sale, pays, cashReceivedOverride);
        if (cashReceived != null && cashReceived.compareTo(sale.getGrandTotal()) >= 0) {
            cashReceivedDisplay = money(cashReceived);
            changeGivenDisplay = money(cashReceived.subtract(sale.getGrandTotal()));
        }

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
                saleActorNameService.resolveSoldByName(businessId, sale.getSoldBy()),
                blankToDefault(business.getCurrency(), "KES").trim().toUpperCase(Locale.ROOT),
                sale.getId(),
                sale.getReceiptNo(),
                null,
                soldAt,
                sale.getStatus(),
                customerName,
                customerPhone,
                lines,
                payments,
                money(sale.getGrandTotal()),
                cashReceivedDisplay,
                changeGivenDisplay,
                footer
        );
    }

    private static String blankToNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    /** Primary phone first (oldest row wins ties), else the first non-blank phone. */
    private String resolvePrimaryPhone(String customerId) {
        return customerPhoneRepository.findByCustomerIdOrderByCreatedAtAsc(customerId).stream()
                .filter(p -> p.getPhone() != null && !p.getPhone().isBlank())
                .sorted((a, b) -> Boolean.compare(b.isPrimary(), a.isPrimary()))
                .findFirst()
                .map(p -> p.getPhone().trim())
                .orElse(null);
    }

    private static String footerNote(Sale sale) {
        if (SalesConstants.SALE_STATUS_VOIDED.equals(sale.getStatus())) {
            return "*** VOIDED — not valid for returns ***";
        }
        BigDecimal ref = sale.getRefundedTotal();
        if (ref != null && ref.signum() > 0) {
            return "Refunded to date: " + money(ref);
        }
        return "";
    }

    private static String money(BigDecimal v) {
        return v.setScale(MONEY_SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Stored cash_received wins; else optional query/body override when the sale is
     * a single cash payment (full cash checkout).
     */
    private static BigDecimal resolveCashReceivedForReceipt(
            Sale sale,
            List<SalePayment> pays,
            BigDecimal override
    ) {
        if (sale.getCashReceived() != null) {
            return sale.getCashReceived();
        }
        if (override == null) {
            return null;
        }
        BigDecimal cash = override.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (cash.compareTo(sale.getGrandTotal()) < 0) {
            return null;
        }
        if (pays.size() != 1 || !SalesConstants.PAYMENT_METHOD_CASH.equals(pays.get(0).getMethod())) {
            return null;
        }
        return cash;
    }

    private static String blankToDefault(String raw, String def) {
        if (raw == null || raw.isBlank()) {
            return def;
        }
        return raw.trim();
    }
}
