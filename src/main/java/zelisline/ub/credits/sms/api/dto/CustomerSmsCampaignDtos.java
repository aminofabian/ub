package zelisline.ub.credits.sms.api.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import zelisline.ub.credits.email.api.dto.CustomerEmailCampaignDtos.AudienceFilter;

public final class CustomerSmsCampaignDtos {

    private CustomerSmsCampaignDtos() {
    }

    public record SmsAudiencePreviewRequest(
            @NotBlank String recipientMethod,
            List<String> customerIds,
            AudienceFilter filter
    ) {
    }

    public record SmsAudienceRow(
            String customerId,
            String name,
            String phone,
            String skipReason
    ) {
    }

    public record SmsAudiencePreviewResponse(
            int matched,
            int automaticallyExcluded,
            int finalRecipients,
            List<SmsAudienceRow> sample,
            List<SmsAudienceRow> excludedSample
    ) {
    }

    public record SmsEstimateRequest(@NotBlank @Size(max = 600) String body) {
    }

    /** {@code encoding} is GSM (160 per part) or UNICODE (70 per part). */
    public record SmsEstimateResponse(
            String text,
            int characters,
            int parts,
            String encoding,
            List<String> unknownVariables
    ) {
    }

    public record CreateCustomerSmsCampaignRequest(
            @NotBlank @Size(max = 255) String name,
            @NotBlank @Size(max = 600) String body,
            @NotBlank String recipientMethod,
            List<String> customerIds,
            AudienceFilter filter
    ) {
    }

    public record UpdateCustomerSmsCampaignRequest(
            @Size(max = 255) String name,
            @Size(max = 600) String body,
            String recipientMethod,
            List<String> customerIds,
            AudienceFilter filter
    ) {
    }

    public record SendCustomerSmsCampaignRequest(
            /** Required when recipientMethod is all_eligible: must be SEND. */
            String confirmPhrase
    ) {
    }

    public record ScheduleCustomerSmsCampaignRequest(
            @NotNull Instant scheduledAt,
            String confirmPhrase
    ) {
    }

    public record CustomerSmsCampaignRecipientResponse(
            String id,
            String customerId,
            String customerName,
            String phone,
            String status,
            String skipReason,
            String error,
            int parts,
            Instant sentAt
    ) {
    }

    public record CustomerSmsCampaignSummaryResponse(
            String id,
            String name,
            String body,
            String recipientMethod,
            String status,
            Instant scheduledAt,
            int recipientsTargeted,
            int recipientsSent,
            int recipientsFailed,
            int recipientsSkipped,
            int creditsUsed,
            Instant createdAt,
            Instant completedAt
    ) {
    }

    public record CustomerSmsCampaignDetailResponse(
            String id,
            String name,
            String body,
            String text,
            int partsPerMessage,
            String recipientMethod,
            AudienceFilter filter,
            String status,
            Instant scheduledAt,
            int recipientsTargeted,
            int recipientsPending,
            int recipientsSent,
            int recipientsFailed,
            int recipientsSkipped,
            int creditsUsed,
            int estimatedCredits,
            /** Recipients who bought within 48 hours of the campaign finishing; null until then. */
            Integer boughtWithin48Hours,
            Instant createdAt,
            Instant updatedAt,
            Instant startedAt,
            Instant completedAt,
            List<CustomerSmsCampaignRecipientResponse> recipients
    ) {
    }
}
