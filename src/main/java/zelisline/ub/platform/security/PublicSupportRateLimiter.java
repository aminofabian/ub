package zelisline.ub.platform.security;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Per-IP sliding-window limit for the unauthenticated guest chat
 * ({@code /api/v1/public/support/**}). Chat is more conversational than the
 * contact form, so the budget is higher per minute.
 */
@Component
public class PublicSupportRateLimiter {

    private final int maxPerMinute;
    private final ConcurrentHashMap<String, List<Long>> hitsByKey = new ConcurrentHashMap<>();

    public PublicSupportRateLimiter(
            @Value("${app.security.public-support-rate-limit-per-minute:30}") int maxPerMinute
    ) {
        this.maxPerMinute = Math.max(1, maxPerMinute);
    }

    public boolean tryConsume(String rateLimitKey) {
        if (rateLimitKey == null || rateLimitKey.isBlank()) {
            return true;
        }
        long nowMs = Instant.now().toEpochMilli();
        long windowStart = nowMs - 60_000;
        boolean[] allowed = {true};
        hitsByKey.compute(rateLimitKey, (k, list) -> {
            List<Long> timestamps = list == null ? new ArrayList<>() : new ArrayList<>(list);
            timestamps.removeIf(ts -> ts < windowStart);
            if (timestamps.size() >= maxPerMinute) {
                allowed[0] = false;
                return timestamps;
            }
            timestamps.add(nowMs);
            return timestamps;
        });
        return allowed[0];
    }
}
