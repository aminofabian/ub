package zelisline.ub.credits.sms.api;

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
import zelisline.ub.credits.sms.api.dto.CustomerSmsCampaignDtos.CreateCustomerSmsCampaignRequest;
import zelisline.ub.credits.sms.api.dto.CustomerSmsCampaignDtos.CustomerSmsCampaignDetailResponse;
import zelisline.ub.credits.sms.api.dto.CustomerSmsCampaignDtos.CustomerSmsCampaignSummaryResponse;
import zelisline.ub.credits.sms.api.dto.CustomerSmsCampaignDtos.ScheduleCustomerSmsCampaignRequest;
import zelisline.ub.credits.sms.api.dto.CustomerSmsCampaignDtos.SendCustomerSmsCampaignRequest;
import zelisline.ub.credits.sms.api.dto.CustomerSmsCampaignDtos.SmsAudiencePreviewRequest;
import zelisline.ub.credits.sms.api.dto.CustomerSmsCampaignDtos.SmsAudiencePreviewResponse;
import zelisline.ub.credits.sms.api.dto.CustomerSmsCampaignDtos.SmsEstimateRequest;
import zelisline.ub.credits.sms.api.dto.CustomerSmsCampaignDtos.SmsEstimateResponse;
import zelisline.ub.credits.sms.api.dto.CustomerSmsCampaignDtos.TestSmsRequest;
import zelisline.ub.credits.sms.api.dto.CustomerSmsCampaignDtos.TestSmsResponse;
import zelisline.ub.credits.sms.api.dto.CustomerSmsCampaignDtos.UpdateCustomerSmsCampaignRequest;
import zelisline.ub.credits.sms.application.CustomerSmsCampaignService;
import zelisline.ub.credits.sms.application.SmsCampaignTestSender;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

/**
 * SMS campaigns to customers (docs/scopes/SMS_HUB_SCOPE.md). Mirrors
 * {@code CustomerEmailCampaignController}; sending only queues the campaign.
 */
@Validated
@RestController
@RequestMapping("/api/v1/customer-sms-campaigns")
@RequiredArgsConstructor
public class CustomerSmsCampaignController {

    private final CustomerSmsCampaignService campaignService;
    private final SmsCampaignTestSender testSender;

    @PostMapping("/audience-preview")
    @PreAuthorize("hasPermission(null, 'credits.customers.write')")
    public SmsAudiencePreviewResponse audiencePreview(
            @Valid @RequestBody SmsAudiencePreviewRequest request,
            HttpServletRequest http
    ) {
        CurrentTenantUser.require(http);
        return campaignService.previewAudience(TenantRequestIds.resolveBusinessId(http), request);
    }

    @PostMapping("/estimate")
    @PreAuthorize("hasPermission(null, 'credits.customers.write')")
    public SmsEstimateResponse estimate(@Valid @RequestBody SmsEstimateRequest request, HttpServletRequest http) {
        CurrentTenantUser.require(http);
        return campaignService.estimate(TenantRequestIds.resolveBusinessId(http), request.body());
    }

    /** Text the wording to one number (the sender's own phone) before saving a campaign. */
    @PostMapping("/test")
    @PreAuthorize("hasPermission(null, 'credits.customers.write')")
    public TestSmsResponse test(@Valid @RequestBody TestSmsRequest request, HttpServletRequest http) {
        CurrentTenantUser.require(http);
        return testSender.send(TenantRequestIds.resolveBusinessId(http), request.body(), request.phone());
    }

    @GetMapping
    @PreAuthorize("hasPermission(null, 'credits.customers.read')")
    public Page<CustomerSmsCampaignSummaryResponse> list(Pageable pageable, HttpServletRequest http) {
        CurrentTenantUser.require(http);
        return campaignService.list(TenantRequestIds.resolveBusinessId(http), pageable);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasPermission(null, 'credits.customers.write')")
    public CustomerSmsCampaignDetailResponse create(
            @Valid @RequestBody CreateCustomerSmsCampaignRequest request,
            HttpServletRequest http
    ) {
        String actorId = CurrentTenantUser.auditActorId(http);
        return campaignService.createDraft(TenantRequestIds.resolveBusinessId(http), actorId, request);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'credits.customers.read')")
    public CustomerSmsCampaignDetailResponse get(@PathVariable String id, HttpServletRequest http) {
        CurrentTenantUser.require(http);
        return campaignService.get(TenantRequestIds.resolveBusinessId(http), id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'credits.customers.write')")
    public CustomerSmsCampaignDetailResponse update(
            @PathVariable String id,
            @Valid @RequestBody UpdateCustomerSmsCampaignRequest request,
            HttpServletRequest http
    ) {
        CurrentTenantUser.require(http);
        return campaignService.updateDraft(TenantRequestIds.resolveBusinessId(http), id, request);
    }

    /** A new draft with the same wording and audience, resolved again as of now. */
    @PostMapping("/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasPermission(null, 'credits.customers.write')")
    public CustomerSmsCampaignDetailResponse duplicate(@PathVariable String id, HttpServletRequest http) {
        String actorId = CurrentTenantUser.auditActorId(http);
        return campaignService.duplicate(TenantRequestIds.resolveBusinessId(http), actorId, id);
    }

    @PostMapping("/{id}/send")
    @PreAuthorize("hasPermission(null, 'credits.customers.write')")
    public CustomerSmsCampaignDetailResponse send(
            @PathVariable String id,
            @RequestBody(required = false) SendCustomerSmsCampaignRequest request,
            HttpServletRequest http
    ) {
        CurrentTenantUser.require(http);
        return campaignService.sendNow(
                TenantRequestIds.resolveBusinessId(http), id, request == null ? null : request.confirmPhrase());
    }

    @PostMapping("/{id}/schedule")
    @PreAuthorize("hasPermission(null, 'credits.customers.write')")
    public CustomerSmsCampaignDetailResponse schedule(
            @PathVariable String id,
            @Valid @RequestBody ScheduleCustomerSmsCampaignRequest request,
            HttpServletRequest http
    ) {
        CurrentTenantUser.require(http);
        return campaignService.schedule(
                TenantRequestIds.resolveBusinessId(http), id, request.scheduledAt(), request.confirmPhrase());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasPermission(null, 'credits.customers.write')")
    public CustomerSmsCampaignDetailResponse cancel(@PathVariable String id, HttpServletRequest http) {
        CurrentTenantUser.require(http);
        return campaignService.cancel(TenantRequestIds.resolveBusinessId(http), id);
    }
}
