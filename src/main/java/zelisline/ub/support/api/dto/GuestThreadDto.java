package zelisline.ub.support.api.dto;

import java.util.List;

/**
 * Guest-facing thread payload. {@code token} is only present when the thread
 * was created just now — on resume the browser already holds the secret.
 */
public record GuestThreadDto(
        SupportConversationDto conversation,
        String token,
        List<SupportMessageDto> messages
) {}
