package zelisline.ub.sales.receipt;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.domain.SaleItem;
import zelisline.ub.sales.domain.SalePayment;
import zelisline.ub.sales.repository.SaleItemRepository;
import zelisline.ub.sales.repository.SalePaymentRepository;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
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

    public byte[] buildPdf(String businessId, String saleId) {
        return ReceiptPdfRenderer.render(loadSnapshot(businessId, saleId));
    }

    public byte[] buildEscPos(String businessId, String saleId, int widthMm) {
        if (widthMm != 58 && widthMm != 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "widthMm must be 58 or 80");
        }
        return ReceiptEscPosRenderer.render(loadSnapshot(businessId, saleId), widthMm);
    }

    private ReceiptSnapshot loadSnapshot(String businessId, String saleId) {
        Sale sale = saleRepository.findByIdAndBusinessId(saleId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sale not found"));
        Business business = businessRepository.findById(businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found"));
        Branch branch = branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(sale.getBranchId(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found"));

        List<SaleItem> items = saleItemRepository.findBySaleIdOrderByLineIndexAsc(sale.getId());
        List<SalePayment> pays = salePaymentRepository.findBySaleIdOrderBySortOrderAsc(sale.getId());

        List<String> itemIds = items.stream().map(SaleItem::getItemId).distinct().toList();
        Map<String, Item> itemMap = itemRepository.findAllById(itemIds).stream()
                .filter(i -> businessId.equals(i.getBusinessId()))
                .collect(Collectors.toMap(Item::getId, i -> i));

        List<ReceiptLineRow> lines = new ArrayList<>();
        for (SaleItem si : items) {
            Item it = itemMap.get(si.getItemId());
            String desc = it != null ? it.getName() : "Item";
            if (it != null && it.getVariantName() != null && !it.getVariantName().isBlank()) {
                desc = desc + " (" + it.getVariantName() + ")";
            }
            lines.add(new ReceiptLineRow(
                    desc,
                    si.getQuantity().stripTrailingZeros().toPlainString(),
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

        ZoneId zone = ZoneId.of(blankToDefault(business.getTimezone(), "UTC"));
        String soldAt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")
                .withZone(zone)
                .format(sale.getSoldAt());

        String footer = footerNote(sale);

        return new ReceiptSnapshot(
                business.getName(),
                branch.getName(),
                blankToDefault(business.getCurrency(), "KES").trim().toUpperCase(Locale.ROOT),
                sale.getId(),
                soldAt,
                sale.getStatus(),
                lines,
                payments,
                money(sale.getGrandTotal()),
                footer
        );
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

    private static String blankToDefault(String raw, String def) {
        if (raw == null || raw.isBlank()) {
            return def;
        }
        return raw.trim();
    }
}
