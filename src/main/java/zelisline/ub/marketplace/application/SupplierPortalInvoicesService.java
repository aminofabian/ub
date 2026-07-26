package zelisline.ub.marketplace.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.SupplierPortalInvoiceRow;
import zelisline.ub.purchasing.application.PathBAssociatedCostService;
import zelisline.ub.purchasing.domain.SupplierInvoice;
import zelisline.ub.purchasing.repository.SupplierInvoiceRepository;
import zelisline.ub.purchasing.repository.SupplierPaymentAllocationRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class SupplierPortalInvoicesService {

    private static final BigDecimal MONEY = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final SupplierInvoiceRepository supplierInvoiceRepository;
    private final BusinessRepository businessRepository;
    private final SupplierPaymentAllocationRepository allocationRepository;
    private final PathBAssociatedCostService pathBAssociatedCostService;

    @Transactional(readOnly = true)
    public List<SupplierPortalInvoiceRow> listInvoices(String marketplaceSupplierId) {
        return supplierInvoiceRepository.findForSupplierPortal(marketplaceSupplierId).stream()
                .map(this::toRow)
                .toList();
    }

    private SupplierPortalInvoiceRow toRow(SupplierInvoice inv) {
        BigDecimal grand = pathBAssociatedCostService.payableGrandTotal(inv.getBusinessId(), inv);
        BigDecimal paid = money(nz(allocationRepository.sumAmountBySupplierInvoiceId(inv.getId())));
        BigDecimal open = grand.subtract(paid).setScale(2, RoundingMode.HALF_UP);
        String paymentStatus = open.compareTo(MONEY) <= 0
                ? "PAID"
                : (paid.compareTo(MONEY) <= 0 ? "UNPAID" : "PARTIAL");
        return new SupplierPortalInvoiceRow(
                inv.getId(),
                inv.getBusinessId(),
                businessRepository.findById(inv.getBusinessId())
                        .map(b -> b.getName() != null ? b.getName() : "Business")
                        .orElse("Business"),
                inv.getInvoiceNumber(),
                inv.getInvoiceDate(),
                inv.getDueDate(),
                inv.getSubtotal(),
                inv.getTaxTotal(),
                grand,
                paid,
                open.max(MONEY),
                paymentStatus,
                inv.getStatus());
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
