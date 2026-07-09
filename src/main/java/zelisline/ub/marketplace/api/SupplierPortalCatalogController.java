package zelisline.ub.marketplace.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.CreateSupplierPortalProductRequest;
import zelisline.ub.marketplace.api.dto.PatchSupplierPortalProductRequest;
import zelisline.ub.marketplace.api.dto.SupplierPortalProductResponse;
import zelisline.ub.marketplace.application.SupplierPortalCatalogService;
import zelisline.ub.platform.security.CurrentSupplierUser;
import zelisline.ub.platform.security.SupplierPrincipal;

@Validated
@RestController
@RequestMapping("/api/v1/supplier-portal/products")
@RequiredArgsConstructor
public class SupplierPortalCatalogController {

    private final SupplierPortalCatalogService supplierPortalCatalogService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'supplier.catalog.read')")
    public Page<SupplierPortalProductResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            Pageable pageable) {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        return supplierPortalCatalogService.listProducts(
                principal.marketplaceSupplierId(), q, status, pageable);
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'supplier.catalog.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public SupplierPortalProductResponse create(@Valid @RequestBody CreateSupplierPortalProductRequest request) {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        return supplierPortalCatalogService.createProduct(principal.marketplaceSupplierId(), request);
    }

    @PatchMapping("/{productId}")
    @PreAuthorize("hasPermission(null, 'supplier.catalog.write')")
    public SupplierPortalProductResponse update(
            @PathVariable String productId,
            @Valid @RequestBody PatchSupplierPortalProductRequest request) {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        return supplierPortalCatalogService.updateProduct(
                principal.marketplaceSupplierId(), productId, request);
    }

    @DeleteMapping("/{productId}")
    @PreAuthorize("hasPermission(null, 'supplier.catalog.write')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String productId) {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        supplierPortalCatalogService.deleteProduct(principal.marketplaceSupplierId(), productId);
    }
}
