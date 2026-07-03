package zelisline.ub.identity.application;

import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.repository.UserSessionRepository;

/**
 * Throttled {@code user_sessions.last_seen_at} updates for the 12-hour idle window.
 * Authenticated API traffic and refresh both keep the sliding window alive.
 */
@Service
@RequiredArgsConstructor
public class UserSessionActivity {

    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(5);

    private final UserSessionRepository userSessionRepository;

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
