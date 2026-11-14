package zelisline.ub.serviceops.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.serviceops.application.ServicePayModelService;
import zelisline.ub.tenancy.api.TenantRequestIds;

/**
 * What the shop's service commission is worked out on (ADR 0019). Anyone at the till or in the
 * commission screens can read it; choosing it is a commission decision.
 */
@Validated
@RestController
@RequestMapping("/api/v1/commissions/pay-model")
@RequiredArgsConstructor
public class ServicePayModelController {

    private final ServicePayModelService payModelService;

    /** {@code GROSS}: on the whole price. {@code NET_OF_PRODUCT}: after the service's product cost. */
    public record PayModel(@NotBlank @Pattern(regexp = "^(GROSS|NET_OF_PRODUCT)$") String model) {
    }

    @GetMapping
    @PreAuthorize("hasPermission(null, 'sales.sell') or hasPermission(null, 'commissions.read')"
            + " or hasPermission(null, 'commissions.plans.manage')")
    public PayModel get(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return new PayModel(payModelService.model(TenantRequestIds.resolveBusinessId(request)));
    }

    @PutMapping
    @PreAuthorize("hasPermission(null, 'commissions.plans.manage')")
    public PayModel set(@Valid @RequestBody PayModel body, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return new PayModel(payModelService.setModel(TenantRequestIds.resolveBusinessId(request), body.model()));
    }
}
