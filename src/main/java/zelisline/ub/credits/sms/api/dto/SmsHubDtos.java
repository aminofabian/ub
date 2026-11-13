package zelisline.ub.credits.sms.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public final class SmsHubDtos {

    private SmsHubDtos() {
    }

    /** Contact reachability counts for the Contacts tab header. */
    public record ContactCounts(
            int total,
            int canText,
            int receiptsOnly,
            int optedOut,
            int noNumber
    ) {
    }

    public record ReasonTotal(String reason, long messages, long credits) {
    }

    /** One Africa/Nairobi day: texts the provider accepted and the credits they cost. */
    public record DailyTotal(String date, long messages, long credits) {
    }

    /** Last 30 days by outcome. {@code sent} = accepted, no delivery report yet. */
    public record StatusCounts(long sent, long delivered, long failed, long blocked) {
    }

    public record SummaryResponse(
            ContactCounts contacts,
            long messagesLast30Days,
            long creditsLast30Days,
            List<ReasonTotal> byReason,
            List<DailyTotal> daily,
            StatusCounts statuses,
            boolean smsConfigured,
            String sendWindow
    ) {
    }

    /**
     * {@code reach} is one of CAN_TEXT, RECEIPTS_ONLY, OPTED_OUT, NO_NUMBER;
     * {@code maskedNumber} is set when the only number on file is an M-Pesa mask.
     */
    public record ContactRow(
            String customerId,
            String name,
            String phone,
            String maskedNumber,
            String origin,
            String reach,
            String consentSource,
            Instant consentUpdatedAt,
            Instant lastPurchaseAt,
            long purchaseCount
    ) {
    }

    public record ContactsPageResponse(
            List<ContactRow> rows,
            int page,
            int size,
            int totalRows,
            int totalPages,
            ContactCounts counts
    ) {
    }

    public record UpdateConsentRequest(
            @NotEmpty @Size(max = 500) List<String> customerIds,
            @NotBlank String status,
            /** Must be true when status is OPTED_IN: staff confirm the customer agreed. */
            Boolean customerAgreed
    ) {
    }

    public record UpdateConsentResponse(int changed) {
    }

    /** One number to add; {@code name} is optional. A bad number is counted, not rejected. */
    public record ImportContactRow(
            @Size(max = 500) String name,
            @Size(max = 100) String phone
    ) {
    }

    public record ImportContactsRequest(
            @NotEmpty @Size(max = 500) List<@Valid ImportContactRow> rows,
            /** True when staff confirm these people agreed to offers by SMS. */
            Boolean customerAgreed
    ) {
    }

    /**
     * {@code alreadyThere} numbers were on file; {@code keptOptedOut} of them had opted
     * out and stay that way; {@code renamed} had only their number as a name;
     * {@code invalid} are not Kenyan mobile numbers.
     */
    public record ImportContactsResponse(
            int added,
            int alreadyThere,
            int markedAgreed,
            int keptOptedOut,
            int renamed,
            int invalid,
            /** Valid numbers that could not be saved this time; sending them again is safe. */
            int failed,
            List<String> invalidSamples
    ) {
    }

    public record ContactsExportResponse(List<ContactRow> rows) {
    }

    public record MessageRow(
            String id,
            String customerId,
            String phone,
            String body,
            int parts,
            int credits,
            String messageClass,
            String reason,
            String campaignId,
            String provider,
            String status,
            String error,
            Instant createdAt,
            Instant deliveredAt
    ) {
    }

    public record OrderNoticeSetting(Boolean enabled, String template) {
    }

    public record AutomationsResponse(
            Map<String, OrderNoticeSetting> orderNotices,
            boolean thankYouEnabled,
            String thankYouTemplate,
            String thankYouDefault,
            boolean winbackEnabled,
            int winbackDays,
            String winbackTemplate,
            String winbackDefault,
            int promoWeeklyCap,
            String sendWindow,
            String optOutFooter
    ) {
    }

    public record UpdateAutomationsRequest(
            Map<String, OrderNoticeSetting> orderNotices,
            Boolean thankYouEnabled,
            @Size(max = 320) String thankYouTemplate,
            Boolean winbackEnabled,
            Integer winbackDays,
            @Size(max = 320) String winbackTemplate,
            Integer promoWeeklyCap
    ) {
    }
}
