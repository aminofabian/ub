package zelisline.ub.marketplace.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.PatchSupplierPortalProfileRequest;
import zelisline.ub.marketplace.api.dto.SupplierPortalProfileResponse;
import zelisline.ub.marketplace.application.SupplierPortalProfileService;
import zelisline.ub.platform.security.CurrentSupplierUser;
import zelisline.ub.platform.security.SupplierPrincipal;

@Validated
@RestController
@RequestMapping("/api/v1/supplier-portal/profile")
@RequiredArgsConstructor
public class SupplierPortalProfileController {

    private final SupplierPortalProfileService supplierPortalProfileService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'supplier.catalog.read')")
    public SupplierPortalProfileResponse getProfile() {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        return supplierPortalProfileService.getProfile(principal.marketplaceSupplierId());
    }

    @PatchMapping
    @PreAuthorize("hasPermission(null, 'supplier.catalog.write')")
    public SupplierPortalProfileResponse updateProfile(@Valid @RequestBody PatchSupplierPortalProfileRequest request) {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        return supplierPortalProfileService.updateProfile(principal.marketplaceSupplierId(), request);
    }
}
