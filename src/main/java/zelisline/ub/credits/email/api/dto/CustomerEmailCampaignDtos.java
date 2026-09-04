package zelisline.ub.credits.email.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class CustomerEmailCampaignDtos {

    private CustomerEmailCampaignDtos() {
    }

    public record FilterCondition(
            @NotBlank String field,
            @NotBlank String op,
            String value,
            String valueTo,
            Integer days,
            String itemId
    ) {
    }

    public record AudienceFilter(
            /** ALL (AND) or ANY (OR). Default ALL. */
            String matchMode,
            List<FilterCondition> conditions
    ) {
    }

    public record AudiencePreviewRequest(
            @NotBlank String recipientMethod,
            List<String> customerIds,
            AudienceFilter filter
    ) {
    }

    public record AudienceRecipientRow(
            String customerId,
            String name,
            String email,
            String phone,
            String skipReason
    ) {
    }

    public record AudiencePreviewResponse(
            int matched,
            int automaticallyExcluded,
            int finalRecipients,
            List<AudienceRecipientRow> sample,
            List<AudienceRecipientRow> excludedSample
    ) {
    }

    public record CreateCustomerEmailCampaignRequest(
            @NotBlank @Size(max = 255) String name,
            @NotBlank @Size(max = 255) String subject,
            @NotBlank String bodyHtml,
            @NotBlank String recipientMethod,
            List<String> customerIds,
            AudienceFilter filter
    ) {
    }

    public record UpdateCustomerEmailCampaignRequest(
            @Size(max = 255) String name,
            @Size(max = 255) String subject,
            String bodyHtml,
            String recipientMethod,
            List<String> customerIds,
            AudienceFilter filter
    ) {
    }

    public record PreviewCustomerEmailRequest(
            @NotBlank String subject,
            @NotBlank String bodyHtml,
            @NotBlank String recipientMethod,
            List<String> customerIds,
            AudienceFilter filter,
            String customerId
    ) {
    }

    public record PreviewCampaignCustomerRequest(String customerId) {
    }

    public record SendCustomerEmailCampaignRequest(
            /** Required when recipientMethod is all_eligible — must be SEND. */
            String confirmPhrase
    ) {
    }

    public record CustomerEmailCampaignRecipientResponse(
            String id,
            String customerId,
            String email,
            String customerName,
            String status,
            String skipReason,
            String error,
            Instant sentAt
    ) {
    }

    public record CustomerEmailCampaignSummaryResponse(
            String id,
            String name,
            String subject,
            String recipientMethod,
            String status,
            int recipientsTargeted,
            int recipientsSent,
            int recipientsFailed,
            int recipientsSkipped,
            Instant createdAt,
            Instant completedAt
    ) {
    }

    public record CustomerEmailCampaignDetailResponse(
            String id,
            String name,
            String subject,
            String bodyHtml,
            String recipientMethod,
            AudienceFilter filter,
            String status,
            int recipientsTargeted,
            int recipientsSent,
            int recipientsFailed,
            int recipientsSkipped,
            Instant createdAt,
            Instant updatedAt,
            Instant startedAt,
            Instant completedAt,
            List<CustomerEmailCampaignRecipientResponse> recipients
    ) {
    }

    public record CustomerEmailPreviewResponse(
            String subject,
            String html,
            String sampleCustomerId,
            String sampleCustomerName,
            String sampleEmail,
            List<String> unknownVariables,
            int matched,
            int automaticallyExcluded,
            int finalRecipients
    ) {
    }

    public record MergeSample(
            String name,
            String firstName,
            String email,
            String phone,
            String shop,
            String shopUrl,
            BigDecimal walletBalance,
            BigDecimal tabBalance,
            int loyaltyPoints
    ) {
    }
}
