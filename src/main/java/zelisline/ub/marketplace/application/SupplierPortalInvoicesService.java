package zelisline.ub.marketplace.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.SupplierPortalInvoiceRow;
import zelisline.ub.marketplace.domain.BusinessSupplierConnection;
import zelisline.ub.marketplace.domain.BusinessSupplierConnectionStatuses;
import zelisline.ub.marketplace.repository.BusinessSupplierConnectionRepository;
import zelisline.ub.purchasing.application.PathBAssociatedCostService;
import zelisline.ub.purchasing.domain.SupplierInvoice;
import zelisline.ub.purchasing.domain.SupplierInvoiceLine;
import zelisline.ub.purchasing.repository.SupplierInvoiceLineRepository;
import zelisline.ub.purchasing.repository.SupplierInvoiceRepository;
import zelisline.ub.purchasing.repository.SupplierPaymentAllocationRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class SupplierPortalInvoicesService {

    private static final BigDecimal MONEY = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final SupplierInvoiceRepository supplierInvoiceRepository;
    private final SupplierInvoiceLineRepository supplierInvoiceLineRepository;
    private final BusinessRepository businessRepository;
    private final SupplierPaymentAllocationRepository allocationRepository;
    private final PathBAssociatedCostService pathBAssociatedCostService;
    private final BusinessSupplierConnectionRepository connectionRepository;

    @Transactional(readOnly = true)
    public List<SupplierPortalInvoiceRow> listInvoices(String marketplaceSupplierId) {
        Set<String> visibleShops = connectionRepository
                .findByMarketplaceSupplierIdAndStatus(
                        marketplaceSupplierId, BusinessSupplierConnectionStatuses.ACTIVE)
                .stream()
                .filter(BusinessSupplierConnection::isCanViewPurchaseHistory)
                .map(BusinessSupplierConnection::getBusinessId)
                .collect(Collectors.toSet());
        if (visibleShops.isEmpty()) {
            return List.of();
        }
        List<SupplierInvoice> invoices = supplierInvoiceRepository.findForSupplierPortal(marketplaceSupplierId)
                .stream()
                .filter(invoice -> visibleShops.contains(invoice.getBusinessId()))
                .toList();
        if (invoices.isEmpty()) {
            return List.of();
        }
        Map<String, List<SupplierInvoiceLine>> linesByInvoice = supplierInvoiceLineRepository
                .findByInvoiceIdInOrderBySortOrderAscIdAsc(
                        invoices.stream().map(SupplierInvoice::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(SupplierInvoiceLine::getInvoiceId));
        return invoices.stream()
                .map(invoice -> toRow(invoice, linesByInvoice.getOrDefault(invoice.getId(), List.of())))
                .toList();
    }

    private SupplierPortalInvoiceRow toRow(SupplierInvoice inv, List<SupplierInvoiceLine> lines) {
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
                inv.getStatus(),
                lines.stream()
                        .map(line -> new SupplierPortalInvoiceRow.Line(
                                line.getId(),
                                line.getDescription(),
                                line.getQty(),
                                line.getUnitCost(),
                                line.getLineTotal(),
                                line.getPurchaseUnit(),
                                line.getPurchaseQty(),
                                line.getUnitsPerPurchaseUnit(),
                                line.getPackPrice()))
                        .toList());
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
