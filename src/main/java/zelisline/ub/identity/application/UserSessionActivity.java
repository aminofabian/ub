package zelisline.ub.identity.application;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

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
            @Value("${app.auth.idle-timeout-hours:24}") long idleTimeoutHours
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

    /**
     * Active session for this access jti, including predecessors whose refresh
     * was rotated. Logout (revoke with no {@code rotatedTo}) still rejects.
     * Without this, a 3-minute background refresh instantly 401s every in-flight
     * request still carrying the previous access JWT.
     */
    public Optional<UserSession> findLiveSessionForAccessJti(String accessJti) {
        if (accessJti == null || accessJti.isBlank()) {
            return Optional.empty();
        }
        String jti = accessJti.trim();
        Optional<UserSession> active = userSessionRepository.findByAccessTokenJtiAndRevokedAtIsNull(jti);
        if (active.isPresent()) {
            return active;
        }
        Optional<UserSession> previous =
                userSessionRepository.findByPreviousAccessTokenJtiAndRevokedAtIsNull(jti);
        if (previous.isPresent()) {
            return previous;
        }
        UserSession cursor = userSessionRepository.findByAccessTokenJti(jti).orElse(null);
        if (cursor == null) {
            return Optional.empty();
        }
        for (int hop = 0; hop < 8; hop++) {
            String nextId = cursor.getRotatedToId();
            if (nextId == null || nextId.isBlank()) {
                return Optional.empty();
            }
            UserSession next = userSessionRepository.findById(nextId).orElse(null);
            if (next == null) {
                return Optional.empty();
            }
            if (next.getRevokedAt() == null) {
                return Optional.of(next);
            }
            cursor = next;
        }
        return Optional.empty();
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
