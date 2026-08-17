package zelisline.ub.kplc.application;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import zelisline.ub.kplc.api.dto.PublicKplcTokenResponse;

/**
 * Looks up prepaid token history for a meter. The shopper never talks to the
 * upstream service; we cache briefly so repeated taps on the same meter do not
 * hammer Kenya Power.
 */
@Component
public class KplcTokenLookupClient {

    private static final Logger log = LoggerFactory.getLogger(KplcTokenLookupClient.class);
    private static final String USER_AGENT = "Palmart/1.0";
    private static final int HTTP_CONNECT_TIMEOUT_MS = 5_000;
    private static final int HTTP_SOCKET_TIMEOUT_MS = 20_000;
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final Duration MIN_GAP = Duration.ofSeconds(5);
    private static final Duration ERROR_TTL = Duration.ofSeconds(15);

    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    public KplcTokenLookupClient(
            ObjectMapper objectMapper,
            @Value("${app.kplc.lookup.base-url:https://denniskabui.com/projects/my-kplc-token}")
            String baseUrl
    ) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
    }

    public List<PublicKplcTokenResponse> fetchHistory(String meterNumber) {
        Instant now = Instant.now();
        Cached hit = cache.get(meterNumber);
        if (hit != null && hit.freshUntil.isAfter(now)) {
            return hit.tokensOrThrow();
        }
        if (hit != null && hit.fetchedAt.plus(MIN_GAP).isAfter(now)) {
            return hit.tokensOrThrow();
        }

        if (baseUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Token history is not available right now");
        }

        String url = baseUrl + "/api/tokens/" + URLEncoder.encode(meterNumber, StandardCharsets.UTF_8);
        HttpResponse<String> response;
        try {
            response = Unirest.get(url)
                    .connectTimeout(HTTP_CONNECT_TIMEOUT_MS)
                    .socketTimeout(HTTP_SOCKET_TIMEOUT_MS)
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .asString();
        } catch (Exception e) {
            log.warn("KPLC token lookup failed meterTail={}: {}", tail(meterNumber), e.getMessage());
            cache.put(meterNumber, Cached.error(now, ERROR_TTL,
                    "Could not reach Kenya Power right now. Try again shortly."));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not reach Kenya Power right now. Try again shortly.");
        }

        int status = response.getStatus();
        String body = response.getBody();
        if (status == 404) {
            Cached miss = Cached.ok(now, CACHE_TTL, List.of());
            cache.put(meterNumber, miss);
            return List.of();
        }
        if (status == 429) {
            cache.put(meterNumber, Cached.error(now, MIN_GAP,
                    "Kenya Power is busy. Try again in a few seconds."));
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Kenya Power is busy. Try again in a few seconds.");
        }
        if (status < 200 || status >= 300) {
            String message = status >= 500
                    ? "Could not reach Kenya Power right now. Try again shortly."
                    : "Could not load tokens for this meter.";
            cache.put(meterNumber, Cached.error(now, ERROR_TTL, message));
            throw new ResponseStatusException(
                    status >= 500 ? HttpStatus.BAD_GATEWAY : HttpStatus.BAD_REQUEST, message);
        }

        try {
            List<PublicKplcTokenResponse> tokens = KplcTokenHistoryParser.parse(objectMapper, body);
            cache.put(meterNumber, Cached.ok(now, CACHE_TTL, tokens));
            return tokens;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("KPLC token lookup parse failed meterTail={}: {}", tail(meterNumber), e.getMessage());
            cache.put(meterNumber, Cached.error(now, ERROR_TTL,
                    "Could not read token history for this meter."));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not read token history for this meter.");
        }
    }

    private static String tail(String meter) {
        if (meter == null || meter.length() < 4) {
            return "****";
        }
        return meter.substring(meter.length() - 4);
    }

    private record Cached(Instant fetchedAt, Instant freshUntil, List<PublicKplcTokenResponse> tokens, String error) {
        static Cached ok(Instant now, Duration ttl, List<PublicKplcTokenResponse> tokens) {
            return new Cached(now, now.plus(ttl), tokens, null);
        }

        static Cached error(Instant now, Duration ttl, String message) {
            return new Cached(now, now.plus(ttl), null, message);
        }

        List<PublicKplcTokenResponse> tokensOrThrow() {
            if (error != null) {
                HttpStatus status = error.contains("busy")
                        ? HttpStatus.TOO_MANY_REQUESTS
                        : HttpStatus.BAD_GATEWAY;
                throw new ResponseStatusException(status, error);
            }
            return tokens;
        }
    }
}
