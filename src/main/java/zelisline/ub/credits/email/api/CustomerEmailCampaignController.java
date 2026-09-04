package zelisline.ub.credits.email.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.email.api.dto.CustomerEmailCampaignDtos.AudiencePreviewRequest;
import zelisline.ub.credits.email.api.dto.CustomerEmailCampaignDtos.AudiencePreviewResponse;
import zelisline.ub.credits.email.api.dto.CustomerEmailCampaignDtos.CreateCustomerEmailCampaignRequest;
import zelisline.ub.credits.email.api.dto.CustomerEmailCampaignDtos.CustomerEmailCampaignDetailResponse;
import zelisline.ub.credits.email.api.dto.CustomerEmailCampaignDtos.CustomerEmailCampaignSummaryResponse;
import zelisline.ub.credits.email.api.dto.CustomerEmailCampaignDtos.CustomerEmailPreviewResponse;
import zelisline.ub.credits.email.api.dto.CustomerEmailCampaignDtos.PreviewCampaignCustomerRequest;
import zelisline.ub.credits.email.api.dto.CustomerEmailCampaignDtos.PreviewCustomerEmailRequest;
import zelisline.ub.credits.email.api.dto.CustomerEmailCampaignDtos.SendCustomerEmailCampaignRequest;
import zelisline.ub.credits.email.api.dto.CustomerEmailCampaignDtos.UpdateCustomerEmailCampaignRequest;
import zelisline.ub.credits.email.application.CustomerEmailCampaignService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/customer-email-campaigns")
@RequiredArgsConstructor
public class CustomerEmailCampaignController {

    private final CustomerEmailCampaignService customerEmailCampaignService;

    @PostMapping("/audience-preview")
    @PreAuthorize("hasPermission(null, 'credits.customers.write')")
    public AudiencePreviewResponse audiencePreview(
            @Valid @RequestBody AudiencePreviewRequest request,
            HttpServletRequest http
    ) {
        CurrentTenantUser.require(http);
        return customerEmailCampaignService.previewAudience(
                TenantRequestIds.resolveBusinessId(http), request);
    }

    @PostMapping("/preview")
    @PreAuthorize("hasPermission(null, 'credits.customers.write')")
    public CustomerEmailPreviewResponse previewUnpersisted(
            @Valid @RequestBody PreviewCustomerEmailRequest request,
            HttpServletRequest http
    ) {
        CurrentTenantUser.require(http);
        return customerEmailCampaignService.previewUnpersisted(
                TenantRequestIds.resolveBusinessId(http), request);
    }

    @GetMapping
    @PreAuthorize("hasPermission(null, 'credits.customers.read')")
    public Page<CustomerEmailCampaignSummaryResponse> list(Pageable pageable, HttpServletRequest http) {
        CurrentTenantUser.require(http);
        return customerEmailCampaignService.list(TenantRequestIds.resolveBusinessId(http), pageable);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasPermission(null, 'credits.customers.write')")
    public CustomerEmailCampaignDetailResponse create(
            @Valid @RequestBody CreateCustomerEmailCampaignRequest request,
            HttpServletRequest http
    ) {
        String actorId = CurrentTenantUser.auditActorId(http);
        return customerEmailCampaignService.createDraft(
                TenantRequestIds.resolveBusinessId(http), actorId, request);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'credits.customers.read')")
    public CustomerEmailCampaignDetailResponse get(@PathVariable String id, HttpServletRequest http) {
        CurrentTenantUser.require(http);
        return customerEmailCampaignService.get(TenantRequestIds.resolveBusinessId(http), id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'credits.customers.write')")
    public CustomerEmailCampaignDetailResponse update(
            @PathVariable String id,
            @Valid @RequestBody UpdateCustomerEmailCampaignRequest request,
            HttpServletRequest http
    ) {
        CurrentTenantUser.require(http);
        return customerEmailCampaignService.updateDraft(
                TenantRequestIds.resolveBusinessId(http), id, request);
    }

    @PostMapping("/{id}/preview")
    @PreAuthorize("hasPermission(null, 'credits.customers.write')")
    public CustomerEmailPreviewResponse previewCampaign(
            @PathVariable String id,
            @RequestBody(required = false) PreviewCampaignCustomerRequest request,
            HttpServletRequest http
    ) {
        CurrentTenantUser.require(http);
        return customerEmailCampaignService.previewCampaign(
                TenantRequestIds.resolveBusinessId(http),
                id,
                request == null ? null : request.customerId());
    }

    @PostMapping("/{id}/send")
    @PreAuthorize("hasPermission(null, 'credits.customers.write')")
    public CustomerEmailCampaignDetailResponse send(
            @PathVariable String id,
            @RequestBody(required = false) SendCustomerEmailCampaignRequest request,
            HttpServletRequest http
    ) {
        CurrentTenantUser.require(http);
        return customerEmailCampaignService.send(
                TenantRequestIds.resolveBusinessId(http),
                id,
                request == null ? new SendCustomerEmailCampaignRequest(null) : request);
    }
}
