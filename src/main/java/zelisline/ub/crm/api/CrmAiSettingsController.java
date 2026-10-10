package zelisline.ub.crm.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import zelisline.ub.crm.api.dto.CrmDtos;
import zelisline.ub.crm.application.CrmAiSettingsService;
import zelisline.ub.crm.domain.CrmAiSettings;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

/** Per-business AI/auto-reply settings (SokoMind). */
@Validated
@RestController
@RequestMapping("/api/v1/crm/ai-settings")
@RequiredArgsConstructor
public class CrmAiSettingsController {

    private final CrmAiSettingsService aiSettingsService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'crm.inbox.read')")
    public CrmDtos.AiSettingsResponse get(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return toResponse(aiSettingsService.get(TenantRequestIds.resolveBusinessId(request)));
    }

    @PutMapping
    @PreAuthorize("hasPermission(null, 'crm.inbox.manage')")
    public CrmDtos.AiSettingsResponse update(
            @Valid @RequestBody CrmDtos.UpdateAiSettingsRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        CrmAiSettings updated = aiSettingsService.update(
                TenantRequestIds.resolveBusinessId(request),
                body.autoReplyEnabled(),
                body.maxRepliesPerConversation(),
                body.handoffUserId());
        return toResponse(updated);
    }

    private static CrmDtos.AiSettingsResponse toResponse(CrmAiSettings settings) {
        return new CrmDtos.AiSettingsResponse(
                settings.isAutoReplyEnabled(),
                settings.getMaxRepliesPerConversation(),
                settings.getHandoffUserId());
    }
}
