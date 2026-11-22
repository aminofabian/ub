package zelisline.ub.platform.security;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Sliding one-minute windows for the unauthenticated supplier page endpoints
 * ({@code /api/v1/public/suppliers/**}); the caller names the key and its budget.
 */
@Component
public class PublicSupplierPageRateLimiter {

    private final ConcurrentHashMap<String, List<Long>> hitsByKey = new ConcurrentHashMap<>();

    public boolean tryConsume(String rateLimitKey, int maxPerMinute) {
        if (rateLimitKey == null || rateLimitKey.isBlank()) {
            return true;
        }
        int budget = Math.max(1, maxPerMinute);
        long nowMs = Instant.now().toEpochMilli();
        long windowStart = nowMs - 60_000;
        boolean[] allowed = {true};
        hitsByKey.compute(rateLimitKey, (k, list) -> {
            List<Long> timestamps = list == null ? new ArrayList<>() : new ArrayList<>(list);
            timestamps.removeIf(ts -> ts < windowStart);
            if (timestamps.size() >= budget) {
                allowed[0] = false;
                return timestamps;
            }
            timestamps.add(nowMs);
            return timestamps;
        });
        return allowed[0];
    }
}
