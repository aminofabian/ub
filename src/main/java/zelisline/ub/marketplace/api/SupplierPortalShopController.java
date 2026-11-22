package zelisline.ub.marketplace.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.OpenSupplierShopRequest;
import zelisline.ub.marketplace.api.dto.SupplierShopImportResponse;
import zelisline.ub.marketplace.api.dto.SupplierShopResponse;
import zelisline.ub.marketplace.application.SupplierShopCatalogService;
import zelisline.ub.marketplace.application.SupplierShopService;
import zelisline.ub.platform.security.CurrentSupplierUser;
import zelisline.ub.platform.security.SupplierPrincipal;

/** A supplier's own wholesale shop (ADR 0038, P1). */
@Validated
@RestController
@RequestMapping("/api/v1/supplier-portal/shop")
@RequiredArgsConstructor
public class SupplierPortalShopController {

    private final SupplierShopService supplierShopService;
    private final SupplierShopCatalogService supplierShopCatalogService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'supplier.catalog.read')")
    public SupplierShopResponse current() {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        return supplierShopService.current(principal.marketplaceSupplierId());
    }

    /** Copies the supplier's price list into its shop. Safe to repeat: nothing is created twice. */
    @PostMapping("/products/import")
    @PreAuthorize("hasPermission(null, 'supplier.catalog.write')")
    public SupplierShopImportResponse importProducts() {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        return supplierShopCatalogService.copyPriceListIntoShop(principal.marketplaceSupplierId());
    }

    /** Idempotent: a second call returns the shop that is already open. */
    @PostMapping
    @PreAuthorize("hasPermission(null, 'supplier.profile.write')")
    public SupplierShopResponse open(@Valid @RequestBody(required = false) OpenSupplierShopRequest request) {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        return supplierShopService.open(principal.marketplaceSupplierId(), request);
    }
}
