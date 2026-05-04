package zelisline.ub.platform.security;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Slows API-key guessing: counts failed authentications per client IP (Phase 8 Slice 6).
 * Valid keys clear the window for that IP.
 */
@Component
public class InvalidApiKeyIpRateLimiter {

    private final int maxInvalidPerMinute;
    private final ConcurrentHashMap<String, List<Long>> failuresByIp = new ConcurrentHashMap<>();

    public InvalidApiKeyIpRateLimiter(
            @Value("${app.integrations.api-key.invalid-attempts-per-minute-per-ip:40}") int maxInvalidPerMinute
    ) {
        this.maxInvalidPerMinute = Math.max(1, maxInvalidPerMinute);
    }

    public boolean isBlocked(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return false;
        }
        long nowMs = Instant.now().toEpochMilli();
        long windowStart = nowMs - 60_000;
        List<Long> timestamps = failuresByIp.get(clientIp);
        if (timestamps == null) {
            return false;
        }
        long recent = timestamps.stream().filter(ts -> ts >= windowStart).count();
        return recent >= maxInvalidPerMinute;
    }

    public void recordFailure(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return;
        }
        long nowMs = Instant.now().toEpochMilli();
        long windowStart = nowMs - 60_000;
        failuresByIp.compute(clientIp, (ip, list) -> {
            List<Long> timestamps = list == null ? new ArrayList<>() : new ArrayList<>(list);
            timestamps.removeIf(ts -> ts < windowStart);
            timestamps.add(nowMs);
            return timestamps;
        });
    }

    public void clear(String clientIp) {
        if (clientIp != null && !clientIp.isBlank()) {
            failuresByIp.remove(clientIp);
        }
    }
}
