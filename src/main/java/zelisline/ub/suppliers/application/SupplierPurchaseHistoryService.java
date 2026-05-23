package zelisline.ub.suppliers.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.purchasing.PurchasingConstants;
import zelisline.ub.purchasing.domain.SupplierInvoice;
import zelisline.ub.purchasing.repository.SupplierInvoiceLineRepository;
import zelisline.ub.purchasing.repository.SupplierInvoiceRepository;
import zelisline.ub.purchasing.repository.SupplierPaymentAllocationRepository;
import zelisline.ub.suppliers.api.dto.SupplierPurchaseHistoryResponse;
import zelisline.ub.suppliers.api.dto.SupplierPurchaseHistoryRow;
import zelisline.ub.suppliers.api.dto.SupplierPurchaseHistorySummary;
import zelisline.ub.suppliers.repository.SupplierRepository;

@Service
@RequiredArgsConstructor
public class SupplierPurchaseHistoryService {

    private static final BigDecimal MONEY = new BigDecimal("0.01");
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final SupplierRepository supplierRepository;
    private final SupplierInvoiceRepository supplierInvoiceRepository;
    private final SupplierInvoiceLineRepository supplierInvoiceLineRepository;
    private final SupplierPaymentAllocationRepository allocationRepository;

    @Transactional(readOnly = true)
    public SupplierPurchaseHistoryResponse purchaseHistory(
            String businessId,
            String supplierId,
            Integer limitRaw
    ) {
        supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull(supplierId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));

        int limit = limitRaw == null ? DEFAULT_LIMIT : Math.clamp(limitRaw, 1, MAX_LIMIT);
        List<SupplierInvoice> allInvs = supplierInvoiceRepository.findByBusinessIdAndSupplierIdAndStatus(
                businessId, supplierId, PurchasingConstants.INVOICE_POSTED);

        BigDecimal totalSpent = BigDecimal.ZERO;
        BigDecimal totalPaid = BigDecimal.ZERO;
        BigDecimal openBalance = BigDecimal.ZERO;
        LocalDate lastInvoiceDate = null;

        allInvs.sort((a, b) -> {
            int byDate = b.getInvoiceDate().compareTo(a.getInvoiceDate());
            if (byDate != 0) {
                return byDate;
            }
            int byCreated = b.getCreatedAt().compareTo(a.getCreatedAt());
            if (byCreated != 0) {
                return byCreated;
            }
            return b.getId().compareTo(a.getId());
        });

        List<SupplierPurchaseHistoryRow> orders = new ArrayList<>(Math.min(limit, allInvs.size()));
        for (SupplierInvoice inv : allInvs) {
            BigDecimal grand = money2(inv.getGrandTotal());
            BigDecimal paid = money2(nz(allocationRepository.sumAmountBySupplierInvoiceId(inv.getId())));
            BigDecimal open = grand.subtract(paid).setScale(2, RoundingMode.HALF_UP);
            int lineCount = (int) supplierInvoiceLineRepository.countByInvoiceId(inv.getId());

            totalSpent = totalSpent.add(grand);
            totalPaid = totalPaid.add(paid);
            if (open.compareTo(MONEY) > 0) {
                openBalance = openBalance.add(open);
            }
            if (lastInvoiceDate == null) {
                lastInvoiceDate = inv.getInvoiceDate();
            }

            if (orders.size() < limit) {
                orders.add(new SupplierPurchaseHistoryRow(
                        inv.getId(),
                        inv.getInvoiceNumber(),
                        inv.getInvoiceDate(),
                        inv.getCreatedAt(),
                        lineCount,
                        grand,
                        paid,
                        open,
                        paymentStatus(open, paid),
                        sourceType(inv)));
            }
        }

        int invoiceCount = allInvs.size();

        return new SupplierPurchaseHistoryResponse(
                new SupplierPurchaseHistorySummary(
                        money2(totalSpent),
                        money2(totalPaid),
                        money2(openBalance),
                        invoiceCount,
                        lastInvoiceDate),
                List.copyOf(orders));
    }

    private static String sourceType(SupplierInvoice inv) {
        if (inv.getRawPurchaseSessionId() != null && !inv.getRawPurchaseSessionId().isBlank()) {
            return "DIRECT_SUPPLY";
        }
        if (inv.getGoodsReceiptId() != null && !inv.getGoodsReceiptId().isBlank()) {
            return "GOODS_RECEIPT";
        }
        return "INVOICE";
    }

    private static String paymentStatus(BigDecimal open, BigDecimal paid) {
        if (open.compareTo(MONEY) <= 0) {
            return "PAID";
        }
        if (paid.compareTo(MONEY) <= 0) {
            return "UNPAID";
        }
        return "PARTIAL";
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal money2(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
