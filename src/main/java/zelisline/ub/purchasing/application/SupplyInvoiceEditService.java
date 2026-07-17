package zelisline.ub.purchasing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.purchasing.PurchasingConstants;
import zelisline.ub.purchasing.api.dto.PatchPathBSupplyInvoiceRequest;
import zelisline.ub.purchasing.api.dto.PathBSupplyInvoiceDetailDto;
import zelisline.ub.purchasing.api.dto.PathBSupplyInvoiceLineDto;
import zelisline.ub.purchasing.domain.RawPurchaseLine;
import zelisline.ub.purchasing.domain.SupplierInvoice;
import zelisline.ub.purchasing.domain.SupplierInvoiceLine;
import zelisline.ub.purchasing.repository.RawPurchaseLineRepository;
import zelisline.ub.purchasing.repository.SupplierInvoiceLineRepository;
import zelisline.ub.purchasing.repository.SupplierInvoiceRepository;
import zelisline.ub.purchasing.repository.SupplierPaymentAllocationRepository;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.repository.SupplierRepository;

@Service
@RequiredArgsConstructor
public class SupplyInvoiceEditService {

    private static final BigDecimal MONEY = new BigDecimal("0.01");

    private final SupplierInvoiceRepository supplierInvoiceRepository;
    private final SupplierInvoiceLineRepository supplierInvoiceLineRepository;
    private final SupplierPaymentAllocationRepository allocationRepository;
    private final SupplierRepository supplierRepository;
    private final PathBPurchaseService pathBPurchaseService;
    private final RawPurchaseLineRepository rawPurchaseLineRepository;

    @Transactional(readOnly = true)
    public PathBSupplyInvoiceDetailDto getPathBInvoiceDetail(String businessId, String invoiceId) {
        SupplierInvoice inv = supplierInvoiceRepository.findByIdAndBusinessId(invoiceId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"));
        if (inv.getRawPurchaseSessionId() == null || inv.getRawPurchaseSessionId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a direct supply (Path B) invoice");
        }
        return toDetail(businessId, inv);
    }

    @Transactional
    public PathBSupplyInvoiceDetailDto patchPathBInvoice(
            String businessId,
            String invoiceId,
            PatchPathBSupplyInvoiceRequest req
    ) {
        SupplierInvoice inv = supplierInvoiceRepository.findByIdAndBusinessId(invoiceId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"));
        if (inv.getRawPurchaseSessionId() == null || inv.getRawPurchaseSessionId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a direct supply (Path B) invoice");
        }
        if (!PurchasingConstants.INVOICE_POSTED.equals(inv.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only posted invoices can be edited");
        }
        if (req.lines() != null && !req.lines().isEmpty()) {
            BigDecimal paid = nz(allocationRepository.sumAmountBySupplierInvoiceId(invoiceId));
            if (paid.compareTo(MONEY) >= 0) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Remove payments from this invoice before editing quantities or line amounts, or update header only");
            }
            pathBPurchaseService.rebalancePostedSupplyInvoiceLines(businessId, invoiceId, req.lines());
            inv = supplierInvoiceRepository.findByIdAndBusinessId(invoiceId, businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"));
        }
        String trimmedNo = req.invoiceNumber().trim();
        if (trimmedNo.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invoice number is required");
        }
        if (!trimmedNo.equals(inv.getInvoiceNumber())
                && supplierInvoiceRepository.existsByBusinessIdAndInvoiceNumberAndIdNot(
                        businessId, trimmedNo, inv.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Invoice number already exists");
        }
        inv.setInvoiceNumber(trimmedNo);
        inv.setInvoiceDate(req.invoiceDate());
        inv.setDueDate(req.dueDate());
        String notes = req.notes();
        inv.setNotes(notes == null || notes.isBlank() ? null : notes.trim());
        try {
            supplierInvoiceRepository.save(inv);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Invoice number already exists");
        }
        return toDetail(businessId, inv);
    }

    private PathBSupplyInvoiceDetailDto toDetail(String businessId, SupplierInvoice inv) {
        Supplier sup = supplierRepository.findById(inv.getSupplierId()).orElse(null);
        if (sup != null && !businessId.equals(sup.getBusinessId())) {
            sup = null;
        }
        String supName;
        if (sup == null) {
            supName = "Unknown supplier";
        } else if (sup.getDeletedAt() != null) {
            String base = sup.getName() == null || sup.getName().isBlank() ? "Supplier" : sup.getName().trim();
            supName = base + " (deleted)";
        } else {
            supName = sup.getName();
        }
        BigDecimal grand = inv.getGrandTotal().setScale(2, RoundingMode.HALF_UP);
        BigDecimal paid = nz(allocationRepository.sumAmountBySupplierInvoiceId(inv.getId()))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal open = grand.subtract(paid).setScale(2, RoundingMode.HALF_UP);
        String status = paymentStatus(open, paid);

        List<SupplierInvoiceLine> dbLines = supplierInvoiceLineRepository.findByInvoiceIdOrderBySortOrderAsc(inv.getId());
        List<PathBSupplyInvoiceLineDto> lines = new ArrayList<>(dbLines.size());
        for (SupplierInvoiceLine sil : dbLines) {
            BigDecimal usable = BigDecimal.ZERO;
            BigDecimal wastage = BigDecimal.ZERO;
            if (sil.getRawLineId() != null) {
                RawPurchaseLine rl = rawPurchaseLineRepository.findById(sil.getRawLineId()).orElse(null);
                if (rl != null && rl.getUsableQty() != null) {
                    usable = rl.getUsableQty();
                }
                if (rl != null && rl.getWastageQty() != null) {
                    wastage = rl.getWastageQty();
                }
            }
            lines.add(new PathBSupplyInvoiceLineDto(
                    sil.getId(),
                    sil.getDescription(),
                    sil.getItemId(),
                    sil.getQty(),
                    sil.getUnitCost(),
                    sil.getLineTotal().setScale(2, RoundingMode.HALF_UP),
                    sil.getSortOrder(),
                    usable,
                    wastage));
        }

        return new PathBSupplyInvoiceDetailDto(
                inv.getId(),
                inv.getSupplierId(),
                supName,
                inv.getInvoiceNumber(),
                inv.getInvoiceDate(),
                inv.getDueDate(),
                inv.getNotes(),
                inv.getCreatedAt(),
                grand,
                paid,
                open,
                status,
                lines);
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
