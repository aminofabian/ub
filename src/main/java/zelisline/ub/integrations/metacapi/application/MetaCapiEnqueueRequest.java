package zelisline.ub.integrations.metacapi.application;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Typed input for enqueueing a durable Meta CAPI event.
 *
 * <p>{@code userData} and {@code customData} must already be in CAPI wire shape
 * (e.g. {@code em/ph/external_id} as sha256-hex arrays, raw {@code fbp/fbc},
 * {@code client_ip_address}/{@code client_user_agent}) — the builder assembles
 * the envelope exactly as supplied so the browser Pixel and CAPI copies match.
 */
public record MetaCapiEnqueueRequest(
        String businessId,
        /** CAPI event name, e.g. {@code CompleteRegistration} / {@code Purchase}. */
        String eventName,
        /** Stable event id; must equal the Pixel eventID byte-for-byte. */
        String eventId,
        /** When the conversion completed (never {@code updated_at}). */
        Instant eventTime,
        /** Frontend page where the action started, on the tenant's domain. */
        String eventSourceUrl,
        /** Defaults to {@code website}. */
        String actionSource,
        JsonNode userData,
        JsonNode customData
) {

    public MetaCapiEnqueueRequest {
        if (businessId == null || businessId.isBlank()) {
            throw new IllegalArgumentException("businessId is required");
        }
        if (eventName == null || eventName.isBlank()) {
            throw new IllegalArgumentException("eventName is required");
        }
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId is required");
        }
        if (eventTime == null) {
            throw new IllegalArgumentException("eventTime is required");
        }
        if (actionSource == null || actionSource.isBlank()) {
            actionSource = "website";
        }
    }
}
