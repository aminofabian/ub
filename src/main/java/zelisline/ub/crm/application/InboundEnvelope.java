package zelisline.ub.crm.application;

import java.time.Instant;

/**
 * Channel-agnostic view of an inbound message for {@link InboundIngest}.
 *
 * <p>Keeps the CRM independent of the transport ({@code messaging}) package so the
 * dependency stays one-directional — {@code messaging -> crm}, never the reverse.
 */
public record InboundEnvelope(
        String wamid,
        String phoneNumberId,
        String from,
        String senderName,
        String type,
        String content,
        Instant receivedAt
) {
}
