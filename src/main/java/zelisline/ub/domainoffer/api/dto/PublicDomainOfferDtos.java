package zelisline.ub.domainoffer.api.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Public (tokenised) surface for the custom-domain + theme offer. */
public final class PublicDomainOfferDtos {

    private PublicDomainOfferDtos() {
    }

    public record ThemeOption(String id, String label, String hint) {
    }

    public record PublicDomainOfferResponse(
            String businessName,
            String businessSlug,
            String shopHost,
            String shopUrl,
            String suggestedDomain,
            long priceCents,
            String currency,
            String status,
            boolean paid,
            boolean delivered,
            String deliveredDomain,
            String requestedDomain,
            String domainMode,
            String themeDirection,
            String themeKey,
            String themeNotes,
            String maskedPhone,
            String reference,
            Instant paidAt,
            Instant slaDueAt,
            String message,
            boolean preview,
            boolean canPay,
            String paymentUnavailableReason,
            List<ThemeOption> themes,
            List<ThemeOption> directions
    ) {
    }

    public record PublicPayRequest(
            @NotBlank @Size(max = 32) String phoneNumber,
            @NotBlank @Size(max = 255) String requestedDomain,
            @NotBlank @Size(max = 24) String domainMode,
            @NotBlank @Size(max = 32) String themeDirection,
            @Size(max = 64) String themeKey,
            @Size(max = 1000) String themeNotes,
            @Size(max = 120) String contactName
    ) {
    }

    public record PublicPayResponse(
            String status,
            String message,
            boolean promptSent,
            String reference
    ) {
    }

    public record PublicStatusResponse(
            String status,
            boolean paid,
            boolean delivered,
            String deliveredDomain,
            String lastStkStatus,
            String message,
            Instant paidAt,
            Instant slaDueAt,
            String reference
    ) {
    }
}
