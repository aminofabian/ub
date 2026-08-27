package zelisline.ub.support.api.dto;

import java.util.List;

/** Structured payload for {@code WELCOME_CARD} support messages (signup). */
public record SupportWelcomeCardDto(
        String recipientName,
        String businessName,
        String supportPhone,
        String supportEmail,
        List<String> helpItems
) {}
