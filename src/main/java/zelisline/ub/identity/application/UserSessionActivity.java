package zelisline.ub.identity.application;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import zelisline.ub.identity.domain.UserSession;
import zelisline.ub.identity.repository.UserSessionRepository;

/**
 * Throttled {@code user_sessions.last_seen_at} updates for the idle window.
 * Authenticated API traffic and refresh both keep the sliding window alive.
 * Idle expiry is enforced on refresh and on access-token requests.
 */
@Service
public class UserSessionActivity {

    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(5);

    private final UserSessionRepository userSessionRepository;
    private final long idleTimeoutHours;

    public UserSessionActivity(
            UserSessionRepository userSessionRepository,
            @Value("${app.auth.idle-timeout-hours:12}") long idleTimeoutHours
    ) {
        this.userSessionRepository = userSessionRepository;
        this.idleTimeoutHours = Math.max(1, idleTimeoutHours);
    }

    public boolean isIdleExpired(UserSession session) {
        if (session == null) {
            return true;
        }
        Instant lastSeen = session.getLastSeenAt() != null
                ? session.getLastSeenAt()
                : session.getIssuedAt();
        if (lastSeen == null) {
            return true;
        }
        return lastSeen.plus(idleTimeoutHours, ChronoUnit.HOURS).isBefore(Instant.now());
    }

    /**
     * Revokes the session when the idle window has elapsed.
     *
     * @return {@code true} when the session was idle and is now revoked
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean revokeIfIdle(UserSession session) {
        if (session == null || session.getRevokedAt() != null || !isIdleExpired(session)) {
            return false;
        }
        session.setRevokedAt(Instant.now());
        userSessionRepository.save(session);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordActivity(String accessJti) {
        if (accessJti == null || accessJti.isBlank()) {
            return;
        }
        Instant now = Instant.now();
        userSessionRepository.touchLastSeenIfStale(
                accessJti.trim(),
                now,
                now.minus(TOUCH_INTERVAL));
    }
}
