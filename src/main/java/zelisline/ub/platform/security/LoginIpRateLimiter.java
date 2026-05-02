package zelisline.ub.platform.security;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * In-memory per-IP login rate limit (PHASE_1_PLAN.md §3.4, {@code RATE_LIMIT_LOGIN}).
 */
@Component
public class LoginIpRateLimiter {

    private final int maxPerMinute;
    private final ConcurrentHashMap<String, List<Long>> hitsByIp = new ConcurrentHashMap<>();

    public LoginIpRateLimiter(@Value("${app.security.login-rate-limit-per-minute:5}") int maxPerMinute) {
        this.maxPerMinute = Math.max(1, maxPerMinute);
    }

    /**
     * @return {@code true} if the request is allowed; {@code false} if rate limited
     */
    public boolean tryConsume(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return true;
        }
        long nowMs = Instant.now().toEpochMilli();
        long windowStart = nowMs - 60_000;
        boolean[] allowed = {true};
        hitsByIp.compute(clientIp, (ip, list) -> {
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
