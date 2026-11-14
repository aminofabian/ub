package zelisline.ub.serviceops.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.serviceops.application.ServiceTeamSignInService;
import zelisline.ub.serviceops.application.ServiceTeamSignInService.SentSignIn;
import zelisline.ub.tenancy.api.TenantRequestIds;

/** Makes a team member's sign-in and sends it to them by email and text. */
@Validated
@RestController
@RequestMapping("/api/v1/service-team")
@RequiredArgsConstructor
public class ServiceTeamSignInController {

    private static final String TENANT_HOST_HEADER = "X-Tenant-Host";

    private final ServiceTeamSignInService signInService;

    /** Their address is needed the first time; the mobile number is optional and is kept. */
    public record SendSignInRequest(
            @Email @Size(max = 191) String email,
            @Size(max = 50) String phone,
            @Size(max = 36) String branchId
    ) {
    }

    @PostMapping("/{staffProfileId}/send-sign-in")
    @PreAuthorize("hasPermission(null, 'staff.hr.update') and hasPermission(null, 'users.create')")
    public SentSignIn send(
            @PathVariable String staffProfileId, @Valid @RequestBody SendSignInRequest body, HttpServletRequest request) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        return signInService.send(TenantRequestIds.resolveBusinessId(request), staffProfileId,
                body.email(), body.phone(), body.branchId(), signInHost(request), caller);
    }

    /**
     * The shop's own address, as the request named it. The tenant filter has already matched it
     * to this business; anything that does not look like a host name is left out of the message.
     */
    private static String signInHost(HttpServletRequest request) {
        String host = request.getHeader(TENANT_HOST_HEADER);
        if (host == null) {
            return null;
        }
        host = host.trim().toLowerCase(java.util.Locale.ROOT);
        return host.matches("^[a-z0-9]([a-z0-9.-]{0,180})(:\\d{1,5})?$") ? host : null;
    }
}
