package zelisline.ub.marketplace.api;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.SupplierPortalOrderDetailResponse;
import zelisline.ub.marketplace.api.dto.SupplierPortalOrderListRow;
import zelisline.ub.marketplace.api.dto.SupplierPortalRespondRequest;
import zelisline.ub.marketplace.api.dto.SupplierPortalShipRequest;
import zelisline.ub.marketplace.application.SupplierPortalOrdersService;
import zelisline.ub.platform.security.CurrentSupplierUser;
import zelisline.ub.platform.security.SupplierPrincipal;

@Validated
@RestController
@RequestMapping("/api/v1/supplier-portal/orders")
@RequiredArgsConstructor
public class SupplierPortalOrdersController {

    private final SupplierPortalOrdersService supplierPortalOrdersService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'supplier.orders.read')")
    public List<SupplierPortalOrderListRow> list() {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        return supplierPortalOrdersService.listOrders(principal.marketplaceSupplierId());
    }

    @GetMapping("/{purchaseOrderId}")
    @PreAuthorize("hasPermission(null, 'supplier.orders.read')")
    public SupplierPortalOrderDetailResponse get(@PathVariable String purchaseOrderId) {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        return supplierPortalOrdersService.getOrder(principal.marketplaceSupplierId(), purchaseOrderId);
    }

    @PostMapping("/{purchaseOrderId}/respond")
    @PreAuthorize("hasPermission(null, 'supplier.orders.respond')")
    public SupplierPortalOrderDetailResponse respond(
            @PathVariable String purchaseOrderId,
            @Valid @RequestBody SupplierPortalRespondRequest request) {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        return supplierPortalOrdersService.respond(
                principal.marketplaceSupplierId(), purchaseOrderId, request);
    }

    @PostMapping("/{purchaseOrderId}/ship")
    @PreAuthorize("hasPermission(null, 'supplier.orders.ship')")
    public SupplierPortalOrderDetailResponse ship(
            @PathVariable String purchaseOrderId,
            @Valid @RequestBody SupplierPortalShipRequest request) {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        return supplierPortalOrdersService.ship(
                principal.marketplaceSupplierId(), purchaseOrderId, request);
    }
}
