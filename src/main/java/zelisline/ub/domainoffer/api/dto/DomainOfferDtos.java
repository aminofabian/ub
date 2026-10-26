package zelisline.ub.domainoffer.api.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Super Admin console shapes for the domain + theme offer. */
public final class DomainOfferDtos {

    private DomainOfferDtos() {
    }

    public record DomainOfferStats(
            long eligible,
            long invited,
            long awaitingPayment,
            long paid,
            long inProgress,
            long delivered,
            long cancelled,
            long expired,
            long overdue,
            long dueNow,
            long revenueCents,
            int conversionPct
    ) {
    }

    public record DomainOfferTouch(
            int index,
            int dayOffset,
            String label,
            String subject,
            String preview,
            String bodyMarkdown,
            String cta,
            String inAppTitle,
            String inAppBody
    ) {
    }

    public record DomainOfferCampaignResponse(
            String id,
            String name,
            String status,
            String audienceKey,
            long priceCents,
            String currency,
            int intervalDays,
            int maxTouches,
            boolean emailEnabled,
            boolean inAppEnabled,
            int recipientsTargeted,
            Instant startedAt,
            Instant pausedAt,
            Instant completedAt,
            Instant createdAt,
            DomainOfferStats stats,
            List<DomainOfferTouch> touches
    ) {
    }

    public record StartCampaignRequest(@Size(max = 191) String name) {
    }

    public record DomainOfferOrderResponse(
            String id,
            String reference,
            String businessId,
            String businessName,
            String businessSlug,
            String status,
            String domainMode,
            String requestedDomain,
            String suggestedDomain,
            String themeKey,
            String themeDirection,
            String themeNotes,
            String contactName,
            String contactEmail,
            String contactPhone,
            long amountCents,
            String currency,
            int touchCount,
            Instant lastTouchAt,
            Instant nextTouchAt,
            int emailSentCount,
            int inAppSentCount,
            String lastEmailError,
            String lastStkStatus,
            String mpesaReceipt,
            String payerPhone,
            Instant paymentStartedAt,
            Instant paidAt,
            Instant slaDueAt,
            boolean overdue,
            String assignedTo,
            Instant startedAt,
            Instant deliveredAt,
            String deliveredDomain,
            String domainMappingId,
            String domainOrderId,
            String registrationStatus,
            String fulfilmentNotes,
            String lastError,
            String payLink,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record OrderListResponse(
            List<DomainOfferOrderResponse> rows,
            long total,
            DomainOfferStats stats
    ) {
    }

    public record AssignOrderRequest(@Size(max = 191) String assignedTo) {
    }

    public record DeliverOrderRequest(
            @NotBlank @Size(max = 255) String domain,
            @Size(max = 2000) String notes,
            Boolean connectDomain
    ) {
    }

    public record CancelOrderRequest(@Size(max = 500) String reason) {
    }

    public record RefundOrderRequest(@Size(max = 500) String note) {
    }

    public record TestEmailRequest(@NotBlank @Email @Size(max = 191) String email) {
    }
}
