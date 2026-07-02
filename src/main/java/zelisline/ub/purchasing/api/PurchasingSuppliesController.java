package zelisline.ub.purchasing.api;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.purchasing.api.dto.PatchPathBSupplyInvoiceRequest;
import zelisline.ub.purchasing.api.dto.PathBSupplyInvoiceDetailDto;
import zelisline.ub.purchasing.api.dto.PathBSupplyListRow;
import zelisline.ub.purchasing.api.dto.SupplyKopokopoPayResponse;
import zelisline.ub.purchasing.api.dto.SupplyPayOptionsResponse;
import zelisline.ub.purchasing.api.dto.SupplyPaymentHistoryRow;
import zelisline.ub.purchasing.application.SupplierDisbursementService;
import zelisline.ub.purchasing.application.SupplyInvoiceEditService;
import zelisline.ub.purchasing.application.SupplyReceiptQueryService;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/purchasing/supplies")
@RequiredArgsConstructor
public class PurchasingSuppliesController {

    private final SupplyReceiptQueryService supplyReceiptQueryService;
    private final SupplyInvoiceEditService supplyInvoiceEditService;
    private final SupplierDisbursementService supplierDisbursementService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'purchasing.path_b.read') or hasPermission(null, 'purchasing.payment.read')")
    public List<PathBSupplyListRow> list(
            @RequestParam(required = false) String branchId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return supplyReceiptQueryService.listPathBSupplies(
                TenantRequestIds.resolveBusinessId(request),
                branchId);
    }

    @GetMapping("/{invoiceId}")
    @PreAuthorize("hasPermission(null, 'purchasing.path_b.read') or hasPermission(null, 'purchasing.payment.read')")
    public PathBSupplyInvoiceDetailDto getInvoiceDetail(
            @PathVariable String invoiceId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return supplyInvoiceEditService.getPathBInvoiceDetail(
                TenantRequestIds.resolveBusinessId(request),
                invoiceId
        );
    }

    @PatchMapping("/{invoiceId}")
    @PreAuthorize("hasPermission(null, 'purchasing.path_b.write')")
    public PathBSupplyInvoiceDetailDto patchInvoice(
            @PathVariable String invoiceId,
            @Valid @RequestBody PatchPathBSupplyInvoiceRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return supplyInvoiceEditService.patchPathBInvoice(
                TenantRequestIds.resolveBusinessId(request),
                invoiceId,
                body
        );
    }

    @GetMapping("/{invoiceId}/pay-options")
    @PreAuthorize("hasPermission(null, 'purchasing.path_b.read') or hasPermission(null, 'purchasing.payment.read')")
    public SupplyPayOptionsResponse payOptions(
            @PathVariable String invoiceId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return supplierDisbursementService.payOptions(
                TenantRequestIds.resolveBusinessId(request),
                invoiceId);
    }

    @PostMapping("/{invoiceId}/pay-kopokopo")
    @PreAuthorize("hasPermission(null, 'purchasing.payment.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public SupplyKopokopoPayResponse payViaKopokopo(
            @PathVariable String invoiceId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return supplierDisbursementService.initiateKopokopoPay(
                TenantRequestIds.resolveBusinessId(request),
                invoiceId);
    }

    @GetMapping("/{invoiceId}/disbursement-status")
    @PreAuthorize("hasPermission(null, 'purchasing.path_b.read') or hasPermission(null, 'purchasing.payment.read')")
    public SupplyKopokopoPayResponse disbursementStatus(
            @PathVariable String invoiceId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return supplierDisbursementService.disbursementStatus(
                TenantRequestIds.resolveBusinessId(request),
                invoiceId);
    }

    @GetMapping("/{invoiceId}/payment-history")
    @PreAuthorize("hasPermission(null, 'purchasing.payment.read')")
    public List<SupplyPaymentHistoryRow> paymentHistory(
            @PathVariable String invoiceId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return supplyReceiptQueryService.paymentHistoryForSupplyInvoice(
                TenantRequestIds.resolveBusinessId(request),
                invoiceId
        );
    }
}
