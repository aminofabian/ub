package zelisline.ub.support.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

public record SendSupportMessageRequest(
        /** Plain text; optional when {@code attachment} is present. */
        @Size(max = 4000) String body,
        /** Optional display name — used by guest senders to self-identify. */
        @Size(max = 120) String guestName,
        @Valid SupportAttachmentDto attachment,
        /** Reply to an existing message in the same conversation. */
        @Size(max = 36) String replyToMessageId,
        /**
         * Stable client id for this logical message. Retrying with the same id
         * returns the existing row instead of creating a duplicate.
         */
        @Size(max = 64) String clientMessageId
) {
    /** Backwards-compatible constructor for server-generated/system messages. */
    public SendSupportMessageRequest(
            String body,
            String guestName,
            SupportAttachmentDto attachment,
            String replyToMessageId
    ) {
        this(body, guestName, attachment, replyToMessageId, null);
    }
}
