package zelisline.ub.marketplace.api;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.SupplierPortalInvoiceRow;
import zelisline.ub.marketplace.application.SupplierPortalInvoicesService;
import zelisline.ub.platform.security.CurrentSupplierUser;
import zelisline.ub.platform.security.SupplierPrincipal;

@RestController
@RequestMapping("/api/v1/supplier-portal/invoices")
@RequiredArgsConstructor
public class SupplierPortalInvoicesController {

    private final SupplierPortalInvoicesService supplierPortalInvoicesService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'supplier.orders.read')")
    public List<SupplierPortalInvoiceRow> list() {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        return supplierPortalInvoicesService.listInvoices(principal.marketplaceSupplierId());
    }
}
