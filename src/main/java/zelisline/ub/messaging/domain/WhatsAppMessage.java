package zelisline.ub.messaging.domain;

import java.time.Instant;

public record WhatsAppMessage(
    String messageId,
    String from,
    String senderName,
    WhatsAppMessageType type,
    String content,
    String rawPayload,
    Instant receivedAt,
    /** Meta phone_number_id that received the message (for business resolution). */
    String phoneNumberId
) {}
