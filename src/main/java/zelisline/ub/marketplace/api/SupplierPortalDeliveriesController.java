package zelisline.ub.marketplace.api;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.SupplierPortalDeliveryRow;
import zelisline.ub.marketplace.application.SupplierPortalDeliveriesService;
import zelisline.ub.platform.security.CurrentSupplierUser;
import zelisline.ub.platform.security.SupplierPrincipal;

@Validated
@RestController
@RequestMapping("/api/v1/supplier-portal/deliveries")
@RequiredArgsConstructor
public class SupplierPortalDeliveriesController {

    private final SupplierPortalDeliveriesService deliveriesService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'supplier.orders.read')")
    public List<SupplierPortalDeliveryRow> list() {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        return deliveriesService.listDeliveries(principal.marketplaceSupplierId());
    }
}
