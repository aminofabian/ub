package zelisline.ub.tenancy.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.tenancy.api.dto.BusinessResponse;
import zelisline.ub.tenancy.api.dto.CreateBusinessRequest;
import zelisline.ub.tenancy.api.dto.DomainResponse;
import zelisline.ub.tenancy.api.dto.UpdateBusinessRequest;
import zelisline.ub.tenancy.api.dto.AddDomainRequest;
import zelisline.ub.tenancy.application.BusinessDeletionService;
import zelisline.ub.tenancy.application.TenancyService;

@Validated
@RestController
@RequestMapping("/api/v1/super-admin/businesses")
@RequiredArgsConstructor
public class SuperAdminBusinessController {

    private final TenancyService tenancyService;
    private final BusinessDeletionService businessDeletionService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BusinessResponse createBusiness(@Valid @RequestBody CreateBusinessRequest request) {
        return tenancyService.createBusiness(request);
    }

    @GetMapping
    public Page<BusinessResponse> listBusinesses(Pageable pageable) {
        return tenancyService.listBusinesses(pageable);
    }

    @PatchMapping("/{businessId}")
    public BusinessResponse updateBusiness(
            @PathVariable String businessId,
            @Valid @RequestBody UpdateBusinessRequest request
    ) {
        return tenancyService.updateBusiness(businessId, request);
    }

    @DeleteMapping("/{businessId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBusiness(@PathVariable String businessId) {
        businessDeletionService.deleteBusinessAndUsers(businessId);
    }

    @GetMapping("/{businessId}/domains")
    public java.util.List<DomainResponse> listDomains(@PathVariable String businessId) {
        return tenancyService.listDomains(businessId);
    }

    @PostMapping("/{businessId}/domains")
    @ResponseStatus(HttpStatus.CREATED)
    public DomainResponse addDomain(
            @PathVariable String businessId,
            @Valid @RequestBody AddDomainRequest request
    ) {
        return tenancyService.addDomain(businessId, request.domain());
    }

    @PostMapping("/{businessId}/domains/{domainId}/primary")
    public DomainResponse setPrimaryDomain(@PathVariable String businessId, @PathVariable String domainId) {
        return tenancyService.setPrimaryDomain(businessId, domainId);
    }

    @DeleteMapping("/{businessId}/domains/{domainId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDomain(@PathVariable String businessId, @PathVariable String domainId) {
        tenancyService.deleteDomain(businessId, domainId);
    }
}
