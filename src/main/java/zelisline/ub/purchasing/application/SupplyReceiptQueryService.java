package zelisline.ub.purchasing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.purchasing.PurchasingConstants;
import zelisline.ub.purchasing.api.dto.PathBSupplyListRow;
import zelisline.ub.purchasing.api.dto.SupplyPaymentHistoryRow;
import zelisline.ub.purchasing.domain.SupplierInvoice;
import zelisline.ub.purchasing.domain.SupplierPayment;
import zelisline.ub.purchasing.domain.SupplierPaymentAllocation;
import zelisline.ub.purchasing.repository.SupplierInvoiceLineRepository;
import zelisline.ub.purchasing.repository.SupplierInvoiceRepository;
import zelisline.ub.purchasing.repository.SupplierPaymentAllocationRepository;
import zelisline.ub.purchasing.repository.SupplierPaymentRepository;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.repository.SupplierRepository;

@Service
@RequiredArgsConstructor
public class SupplyReceiptQueryService {

    private static final BigDecimal MONEY = new BigDecimal("0.01");

    private final SupplierInvoiceRepository supplierInvoiceRepository;
    private final SupplierInvoiceLineRepository supplierInvoiceLineRepository;
    private final SupplierPaymentAllocationRepository allocationRepository;
    private final SupplierPaymentRepository supplierPaymentRepository;
    private final SupplierRepository supplierRepository;

    @Transactional(readOnly = true)
    public List<PathBSupplyListRow> listPathBSupplies(String businessId) {
        List<SupplierInvoice> invs = supplierInvoiceRepository
                .findByBusinessIdAndStatusAndRawPurchaseSessionIdIsNotNullOrderByCreatedAtDescIdDesc(
                        businessId, PurchasingConstants.INVOICE_POSTED);
        if (invs.isEmpty()) {
            return List.of();
        }
        Set<String> supplierIds = invs.stream().map(SupplierInvoice::getSupplierId).collect(Collectors.toSet());
        Map<String, Supplier> supMap = supplierRepository.findAllById(supplierIds).stream()
                .filter(s -> businessId.equals(s.getBusinessId()) && s.getDeletedAt() == null)
                .collect(Collectors.toMap(Supplier::getId, s -> s, (a, b) -> a));

        List<PathBSupplyListRow> rows = new ArrayList<>(invs.size());
        for (SupplierInvoice inv : invs) {
            Supplier sup = supMap.get(inv.getSupplierId());
            String supName = sup != null ? sup.getName() : "";
            long cnt = supplierInvoiceLineRepository.countByInvoiceId(inv.getId());
            BigDecimal grand = inv.getGrandTotal().setScale(2, RoundingMode.HALF_UP);
            BigDecimal paid = nz(allocationRepository.sumAmountBySupplierInvoiceId(inv.getId()))
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal open = grand.subtract(paid).setScale(2, RoundingMode.HALF_UP);
            String status = paymentStatus(open, paid);
            rows.add(new PathBSupplyListRow(
                    inv.getId(),
                    inv.getSupplierId(),
                    supName,
                    inv.getInvoiceNumber(),
                    inv.getCreatedAt(),
                    (int) cnt,
                    grand,
                    paid,
                    open,
                    status));
        }
        return rows;
    }

    @Transactional(readOnly = true)
    public List<SupplyPaymentHistoryRow> paymentHistoryForSupplyInvoice(String businessId, String invoiceId) {
        SupplierInvoice inv = supplierInvoiceRepository.findByIdAndBusinessId(invoiceId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"));
        if (inv.getRawPurchaseSessionId() == null || inv.getRawPurchaseSessionId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a direct supply (Path B) invoice");
        }
        List<SupplierPaymentAllocation> allocs = allocationRepository.findBySupplierInvoiceIdOrderByCreatedAtAsc(invoiceId);
        if (allocs.isEmpty()) {
            return List.of();
        }
        Set<String> paymentIds = allocs.stream().map(SupplierPaymentAllocation::getSupplierPaymentId).collect(Collectors.toSet());
        List<SupplierPayment> pays = supplierPaymentRepository.findAllById(paymentIds);
        Map<String, SupplierPayment> byPayId = new HashMap<>();
        for (SupplierPayment p : pays) {
            if (businessId.equals(p.getBusinessId())) {
                byPayId.put(p.getId(), p);
            }
        }
        List<SupplyPaymentHistoryRow> out = new ArrayList<>();
        for (SupplierPaymentAllocation a : allocs) {
            SupplierPayment p = byPayId.get(a.getSupplierPaymentId());
            if (p == null) {
                continue;
            }
            out.add(new SupplyPaymentHistoryRow(
                    p.getId(),
                    a.getId(),
                    p.getPaidAt(),
                    p.getPaymentMethod(),
                    p.getAmount().setScale(2, RoundingMode.HALF_UP),
                    a.getAmount().setScale(2, RoundingMode.HALF_UP),
                    p.getReference(),
                    p.getNotes()));
        }
        return out;
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
}
