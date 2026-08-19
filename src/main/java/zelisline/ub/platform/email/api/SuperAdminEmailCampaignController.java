package zelisline.ub.platform.email.api;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.email.api.dto.PlatformEmailCampaignDtos.CreatePlatformEmailCampaignRequest;
import zelisline.ub.platform.email.api.dto.PlatformEmailCampaignDtos.PlatformEmailCampaignDetailResponse;
import zelisline.ub.platform.email.api.dto.PlatformEmailCampaignDtos.PlatformEmailCampaignSummaryResponse;
import zelisline.ub.platform.email.api.dto.PlatformEmailCampaignDtos.PlatformEmailPreviewResponse;
import zelisline.ub.platform.email.api.dto.PlatformEmailCampaignDtos.PreviewCampaignUserRequest;
import zelisline.ub.platform.email.api.dto.PlatformEmailCampaignDtos.PreviewPlatformEmailRequest;
import zelisline.ub.platform.email.api.dto.PlatformEmailCampaignDtos.SaEmailRecipientResponse;
import zelisline.ub.platform.email.application.PlatformEmailCampaignService;

@Validated
@RestController
@RequestMapping("/api/v1/super-admin")
@RequiredArgsConstructor
public class SuperAdminEmailCampaignController {

    private final PlatformEmailCampaignService platformEmailCampaignService;

    @GetMapping("/email-recipients")
    public Page<SaEmailRecipientResponse> listRecipients(
            @RequestParam(required = false) String segment,
            @RequestParam(required = false) List<String> businessIds,
            @RequestParam(required = false) List<String> userIds,
            @RequestParam(required = false) String q,
            Pageable pageable
    ) {
        return platformEmailCampaignService.listRecipients(segment, businessIds, userIds, q, pageable);
    }

    @GetMapping("/email-campaigns")
    public Page<PlatformEmailCampaignSummaryResponse> listCampaigns(Pageable pageable) {
        return platformEmailCampaignService.listCampaigns(pageable);
    }

    @PostMapping("/email-campaigns")
    @ResponseStatus(HttpStatus.CREATED)
    public PlatformEmailCampaignDetailResponse create(
            @Valid @RequestBody CreatePlatformEmailCampaignRequest request
    ) {
        return platformEmailCampaignService.createDraft(request, requireSuperAdminId());
    }

    @PostMapping("/email-campaigns/preview")
    public PlatformEmailPreviewResponse previewUnpersisted(
            @Valid @RequestBody PreviewPlatformEmailRequest request
    ) {
        return platformEmailCampaignService.previewUnpersisted(request);
    }

    @GetMapping("/email-campaigns/{id}")
    public PlatformEmailCampaignDetailResponse get(@PathVariable String id) {
        return platformEmailCampaignService.getCampaign(id);
    }

    @PostMapping("/email-campaigns/{id}/preview")
    public PlatformEmailPreviewResponse previewCampaign(
            @PathVariable String id,
            @RequestBody(required = false) PreviewCampaignUserRequest request
    ) {
        return platformEmailCampaignService.previewCampaign(
                id, request == null ? null : request.userId());
    }

    @PostMapping("/email-campaigns/{id}/send")
    public PlatformEmailCampaignDetailResponse send(@PathVariable String id) {
        return platformEmailCampaignService.send(id, requireSuperAdminId());
    }

    private static String requireSuperAdminId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof String saId) || saId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return saId;
    }
}
