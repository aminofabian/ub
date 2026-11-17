package zelisline.ub.support.api.dto;

/**
 * One emoji's reaction summary on a message: the emoji, how many participants
 * reacted with it, and whether the requesting participant is one of them.
 */
public record SupportReactionDto(
        String emoji,
        int count,
        boolean mine
) {}
