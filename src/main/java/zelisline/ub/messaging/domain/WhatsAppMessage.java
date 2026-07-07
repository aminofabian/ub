package zelisline.ub.messaging.domain;

import java.time.Instant;

public record WhatsAppMessage(
    String messageId,
    String from,
    String senderName,
    WhatsAppMessageType type,
    String content,
    String rawPayload,
    Instant receivedAt
) {}
