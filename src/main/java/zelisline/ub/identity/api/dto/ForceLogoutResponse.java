package zelisline.ub.identity.api.dto;

/**
 * Result of an admin remote sign-out. {@code revokedSessions} is the number of
 * session rows that were still active, so {@code 0} means the user was already
 * signed out everywhere.
 */
public record ForceLogoutResponse(
        String userId,
        int revokedSessions
) {
}
