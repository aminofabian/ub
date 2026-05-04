package zelisline.ub.platform.security;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Per–API-key sliding window cap for external automation traffic (Phase 8 Slice 1). */
@Component
public class ApiKeyRateLimiter {

    private final int maxPerMinute;
    private final ConcurrentHashMap<String, List<Long>> hitsByKeyId = new ConcurrentHashMap<>();

    public ApiKeyRateLimiter(
            @Value("${app.integrations.api-key.requests-per-minute:120}") int maxPerMinute
    ) {
        this.maxPerMinute = Math.max(1, maxPerMinute);
    }

    public boolean tryConsume(String apiKeyId) {
        if (apiKeyId == null || apiKeyId.isBlank()) {
            return true;
        }
        long nowMs = Instant.now().toEpochMilli();
        long windowStart = nowMs - 60_000;
        boolean[] allowed = {true};
        hitsByKeyId.compute(apiKeyId, (id, list) -> {
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
