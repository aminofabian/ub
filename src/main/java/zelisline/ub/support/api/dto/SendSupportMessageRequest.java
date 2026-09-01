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
        @Size(max = 36) String replyToMessageId
) {}
