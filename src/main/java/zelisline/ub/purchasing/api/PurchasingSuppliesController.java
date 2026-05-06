package zelisline.ub.purchasing.api;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.purchasing.api.dto.PatchPathBSupplyInvoiceRequest;
import zelisline.ub.purchasing.api.dto.PathBSupplyInvoiceDetailDto;
import zelisline.ub.purchasing.api.dto.PathBSupplyListRow;
import zelisline.ub.purchasing.api.dto.SupplyPaymentHistoryRow;
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

    @GetMapping
    @PreAuthorize("hasPermission(null, 'purchasing.path_b.read') or hasPermission(null, 'purchasing.payment.read')")
    public List<PathBSupplyListRow> list(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return supplyReceiptQueryService.listPathBSupplies(TenantRequestIds.resolveBusinessId(request));
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
