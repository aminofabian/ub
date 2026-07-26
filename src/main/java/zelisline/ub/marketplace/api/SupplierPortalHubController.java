package zelisline.ub.marketplace.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.GlobalSupplierHubResponse;
import zelisline.ub.marketplace.api.dto.SupplierPortalHubShopDetailResponse;
import zelisline.ub.marketplace.application.GlobalSupplierHubService;
import zelisline.ub.marketplace.application.SupplierPortalHubService;
import zelisline.ub.platform.security.CurrentSupplierUser;
import zelisline.ub.platform.security.SupplierPrincipal;
import zelisline.ub.suppliers.api.dto.PublicSupplierComplaintRequest;
import zelisline.ub.suppliers.api.dto.PublicSupplierComplaintResponse;

@Validated
@RestController
@RequestMapping("/api/v1/supplier-portal/hub")
@RequiredArgsConstructor
public class SupplierPortalHubController {

    private final SupplierPortalHubService supplierPortalHubService;
    private final GlobalSupplierHubService globalSupplierHubService;

    @GetMapping("/shops")
    @PreAuthorize("hasPermission(null, 'supplier.catalog.read')")
    public GlobalSupplierHubResponse listShops() {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        return globalSupplierHubService.forMarketplaceSupplierId(principal.marketplaceSupplierId());
    }

    @GetMapping("/shops/{localSupplierId}/supplies")
    @PreAuthorize("hasPermission(null, 'supplier.catalog.read')")
    public SupplierPortalHubShopDetailResponse shopSupplies(@PathVariable String localSupplierId) {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        return supplierPortalHubService.shopSupplies(principal.marketplaceSupplierId(), localSupplierId);
    }

    @PostMapping("/shops/{localSupplierId}/complaints")
    @PreAuthorize("hasPermission(null, 'supplier.catalog.write')")
    public PublicSupplierComplaintResponse submitComplaint(
            @PathVariable String localSupplierId,
            @Valid @RequestBody PublicSupplierComplaintRequest body,
            HttpServletRequest request
    ) {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        return supplierPortalHubService.submitShopComplaint(
                principal.marketplaceSupplierId(), localSupplierId, body, request);
    }
}
