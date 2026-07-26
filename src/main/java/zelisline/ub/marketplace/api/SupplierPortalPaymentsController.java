package zelisline.ub.marketplace.api;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.SupplierPortalPaymentRow;
import zelisline.ub.marketplace.application.SupplierPortalPaymentsService;
import zelisline.ub.platform.security.CurrentSupplierUser;
import zelisline.ub.platform.security.SupplierPrincipal;

@Validated
@RestController
@RequestMapping("/api/v1/supplier-portal/payments")
@RequiredArgsConstructor
public class SupplierPortalPaymentsController {

    private final SupplierPortalPaymentsService supplierPortalPaymentsService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'supplier.orders.read')")
    public List<SupplierPortalPaymentRow> list(
            @RequestParam(required = false) String localSupplierId
    ) {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        return supplierPortalPaymentsService.listPayments(principal.marketplaceSupplierId(), localSupplierId);
    }
}
