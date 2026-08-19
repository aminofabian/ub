package zelisline.ub.platform.email.api.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class PlatformEmailCampaignDtos {

    private PlatformEmailCampaignDtos() {
    }

    public record CreatePlatformEmailCampaignRequest(
            @NotBlank @Size(max = 255) String name,
            @NotBlank @Size(max = 32) String segmentKey,
            List<String> businessIds,
            List<String> userIds,
            @NotBlank @Size(max = 255) String subject,
            @NotBlank String bodyMarkdown,
            @Size(max = 120) String ctaLabel
    ) {
    }

    public record PreviewPlatformEmailRequest(
            String segmentKey,
            List<String> businessIds,
            List<String> userIds,
            @NotBlank @Size(max = 255) String subject,
            @NotBlank String bodyMarkdown,
            @Size(max = 120) String ctaLabel,
            String userId
    ) {
    }

    public record PreviewCampaignUserRequest(String userId) {
    }

    public record SaEmailRecipientResponse(
            String userId,
            String email,
            String name,
            String roleKey,
            String userStatus,
            Instant lastLoginAt,
            String businessId,
            String businessName,
            String slug,
            String onboardingStatus,
            String continueKind,
            String skipReason
    ) {
    }

    public record PlatformEmailCampaignSummaryResponse(
            String id,
            String name,
            String segmentKey,
            String subject,
            String status,
            int recipientsTargeted,
            int recipientsSent,
            int recipientsFailed,
            int recipientsSkipped,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt
    ) {
    }

    public record PlatformEmailCampaignDetailResponse(
            String id,
            String name,
            String segmentKey,
            String subject,
            String bodyMarkdown,
            String ctaLabel,
            String status,
            int recipientsTargeted,
            int recipientsSent,
            int recipientsFailed,
            int recipientsSkipped,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt,
            List<PlatformEmailCampaignRecipientResponse> recipients
    ) {
    }

    public record PlatformEmailCampaignRecipientResponse(
            String id,
            String businessId,
            String userId,
            String email,
            String continueKind,
            String status,
            String error,
            Instant sentAt
    ) {
    }

    public record PlatformEmailPreviewResponse(
            String userId,
            String email,
            String subject,
            String html,
            String text,
            String continueUrl,
            List<String> unknownTags
    ) {
    }
}
