package zelisline.ub.notifications.api.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateNotificationCampaignRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank String campaignType,
        @NotBlank @Size(max = 255) String title,
        @NotBlank @Size(max = 2000) String body,
        @Size(max = 512) String actionUrl,
        String recipientScope,
        @Size(max = 36) String catalogBranchId,
        Instant scheduledAt
) {
}
