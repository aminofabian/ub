package zelisline.ub.tenancy.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.tenancy.api.dto.AddDomainRequest;
import zelisline.ub.tenancy.api.dto.DomainResponse;
import zelisline.ub.tenancy.application.TenancyService;

/**
 * Tenant self-service for domain mappings. Scopes every operation to the
 * authenticated business so tenants can manage their own subdomain(s) and
 * custom domains without super-admin access. See
 * {@code SuperAdminBusinessController} for the platform-operator equivalent.
 */
@Validated
@RestController
@RequestMapping("/api/v1/businesses/me/domains")
@RequiredArgsConstructor
public class MyDomainsController {

    private static final String REQUIRES_MANAGE_SETTINGS =
            "hasPermission(null, 'business.manage_settings')";

    private final TenancyService tenancyService;

    @GetMapping
    @PreAuthorize(REQUIRES_MANAGE_SETTINGS)
    public List<DomainResponse> listMyDomains(HttpServletRequest request) {
        return tenancyService.listDomains(TenantRequestIds.resolveBusinessId(request));
    }

    @PostMapping
    @PreAuthorize(REQUIRES_MANAGE_SETTINGS)
    @ResponseStatus(HttpStatus.CREATED)
    public DomainResponse addMyDomain(
            HttpServletRequest request,
            @Valid @RequestBody AddDomainRequest body
    ) {
        return tenancyService.addDomain(
                TenantRequestIds.resolveBusinessId(request),
                body.domain()
        );
    }

    @PostMapping("/{domainId}/primary")
    @PreAuthorize(REQUIRES_MANAGE_SETTINGS)
    public DomainResponse setMyPrimaryDomain(
            HttpServletRequest request,
            @PathVariable String domainId
    ) {
        return tenancyService.setPrimaryDomain(
                TenantRequestIds.resolveBusinessId(request),
                domainId
        );
    }

    @DeleteMapping("/{domainId}")
    @PreAuthorize(REQUIRES_MANAGE_SETTINGS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMyDomain(HttpServletRequest request, @PathVariable String domainId) {
        tenancyService.deleteDomain(
                TenantRequestIds.resolveBusinessId(request),
                domainId
        );
    }
}
