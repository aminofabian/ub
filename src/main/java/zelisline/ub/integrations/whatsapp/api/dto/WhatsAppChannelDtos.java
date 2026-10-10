package zelisline.ub.integrations.whatsapp.api.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Super-admin DTOs for WhatsApp number → shop routing. */
public final class WhatsAppChannelDtos {

    private WhatsAppChannelDtos() {
    }

    /** Assign (or re-assign) a Meta number to a shop. */
    public record RouteNumberRequest(
            @NotBlank @Size(max = 36) String businessId,
            @Size(max = 32) String displayNumber,
            @Size(max = 120) String label
    ) {
    }

    public record NumberRow(
            String phoneNumberId,
            String displayNumber,
            String label,
            String status,
            String qualityRating,
            String businessId,
            String businessName,
            Instant createdAt,
            Instant updatedAt,
            /** True when this number rides the shop's own Meta app (Model B). */
            boolean ownCredentials
    ) {
    }

    /** {@code defaultPhoneNumberId} is the platform-level number from the Meta keys. */
    public record NumbersResponse(
            boolean platformConfigured,
            String defaultPhoneNumberId,
            List<NumberRow> numbers
    ) {
    }

    /**
     * Runtime on/off switches. When a switch is inheriting an unset override it follows the
     * {@code *EnvDefault} value shown alongside it.
     */
    public record SettingsResponse(
            boolean inboundEnabled,
            boolean outboundEnabled,
            boolean inboundEnvDefault,
            boolean outboundEnvDefault,
            boolean metaConfigured
    ) {
    }

    /** {@code null} leaves a stored override untouched (the switch keeps inheriting the env flag). */
    public record UpdateSettingsRequest(
            Boolean inboundEnabled,
            Boolean outboundEnabled
    ) {
    }
}
