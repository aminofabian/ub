package zelisline.ub.wholesale.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.domain.SuperAdmin;
import zelisline.ub.identity.repository.SuperAdminRepository;
import zelisline.ub.wholesale.api.dto.SetStandingExclusionRequest;
import zelisline.ub.wholesale.api.dto.StandingExclusionResponse;
import zelisline.ub.wholesale.application.SupplierAppealService;

/** Platform support: see and set whether one order counts in a supplier's standing. Reversible. */
@RestController
@RequestMapping("/api/v1/super-admin/standing")
@RequiredArgsConstructor
public class SuperAdminStandingController {

    private final SupplierAppealService supplierAppealService;
    private final SuperAdminRepository superAdminRepository;
    private final zelisline.ub.marketplace.repository.MarketplaceSupplierRepository marketplaceSupplierRepository;

    @GetMapping("/orders/{orderId}")
    public StandingExclusionResponse exclusion(@PathVariable String orderId) {
        requireSuperAdmin();
        return supplierAppealService.standingExclusion(orderId);
    }

    @PutMapping("/orders/{orderId}/exclusion")
    public StandingExclusionResponse setExclusion(
            @PathVariable String orderId,
            @Valid @RequestBody SetStandingExclusionRequest body
    ) {
        SuperAdmin admin = requireSuperAdmin();
        return supplierAppealService.setStandingExcluded(
                orderId, body.excluded(), body.note(), admin.getId(), admin.getName());
    }

    /**
     * Decision D2: switches the readiness checklist on or off in the portal for one supplier, with or without a
     * shop. Body: {@code {"enabled": true}}.
     */
    @GetMapping("/suppliers/{marketplaceSupplierId}/engagement")
    public java.util.Map<String, Object> supplierEngagement(@PathVariable String marketplaceSupplierId) {
        requireSuperAdmin();
        var supplier = marketplaceSupplierRepository.findById(marketplaceSupplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
        return java.util.Map.of(
                "marketplaceSupplierId", supplier.getId(), "enabled", supplier.isEngagementEnabled());
    }

    @PutMapping("/suppliers/{marketplaceSupplierId}/engagement")
    public java.util.Map<String, Object> setSupplierEngagement(
            @PathVariable String marketplaceSupplierId,
            @RequestBody java.util.Map<String, Boolean> body
    ) {
        requireSuperAdmin();
        var supplier = marketplaceSupplierRepository.findById(marketplaceSupplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
        supplier.setEngagementEnabled(body != null && Boolean.TRUE.equals(body.get("enabled")));
        marketplaceSupplierRepository.save(supplier);
        return java.util.Map.of(
                "marketplaceSupplierId", supplier.getId(), "enabled", supplier.isEngagementEnabled());
    }

    private SuperAdmin requireSuperAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        String id = (String) authentication.getPrincipal();
        return superAdminRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Super admin not found"));
    }
}
