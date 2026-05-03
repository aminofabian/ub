package zelisline.ub.platform.security;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Sliding-window limit for anonymous storefront catalog traffic (per composite key, e.g. IP + slug).
 */
@Component
public class PublicStorefrontIpRateLimiter {

    private final int maxPerMinute;
    private final ConcurrentHashMap<String, List<Long>> hitsByKey = new ConcurrentHashMap<>();

    public PublicStorefrontIpRateLimiter(
            @Value("${app.security.public-storefront-rate-limit-per-minute:120}") int maxPerMinute
    ) {
        this.maxPerMinute = Math.max(1, maxPerMinute);
    }

    /**
     * @return {@code true} if the request is allowed; {@code false} if rate limited
     */
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
