package zelisline.ub.marketplace.api;

import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.application.SupplierSignupService;
import zelisline.ub.tenancy.api.TenantRequestIds;

/** A shop that picked "Supplier" at sign-up: the platform makes its marketplace page. */
@RestController
@RequestMapping("/api/v1/supplier-signup")
@RequiredArgsConstructor
public class SupplierSignupController {

    private final SupplierSignupService supplierSignupService;

    @PostMapping
    @PreAuthorize("hasPermission(null, 'business.manage_settings')")
    public Map<String, Object> becomeSupplier(HttpServletRequest request) {
        String passportId = supplierSignupService.becomeSupplier(TenantRequestIds.resolveBusinessId(request));
        return Map.of("supplier", true, "marketplaceSupplierId", passportId);
    }
}
