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
