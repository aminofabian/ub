package zelisline.ub.support.api.dto;

/** Snapshot of the message being quoted in a threaded reply. */
public record SupportMessageReplyDto(
        String messageId,
        String senderType,
        String senderName,
        String body,
        String messageKind
) {}
