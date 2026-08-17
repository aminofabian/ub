package zelisline.ub.kplc.application;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import zelisline.ub.kplc.api.dto.PublicKplcConceptResponse;
import zelisline.ub.kplc.api.dto.PublicKplcTokenResponse;
import zelisline.ub.kplc.domain.KplcConceptCodes;

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
    private static final int MAX_TOKENS = 40;

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
            List<PublicKplcTokenResponse> tokens = parse(body);
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

    private List<PublicKplcTokenResponse> parse(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body == null ? "{}" : body);
        if (root.has("success") && root.path("success").isBoolean() && !root.path("success").asBoolean()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not load tokens for this meter.");
        }
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            return List.of();
        }
        List<PublicKplcTokenResponse> out = new ArrayList<>();
        for (JsonNode row : data) {
            PublicKplcTokenResponse token = toToken(row);
            if (token != null) {
                out.add(token);
            }
        }
        out.sort(Comparator.comparing(PublicKplcTokenResponse::purchasedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        if (out.size() > MAX_TOKENS) {
            return List.copyOf(out.subList(0, MAX_TOKENS));
        }
        return List.copyOf(out);
    }

    private PublicKplcTokenResponse toToken(JsonNode row) {
        String tokenNo = text(row, "tokenNo");
        if (tokenNo == null || tokenNo.isBlank()) {
            return null;
        }
        Instant purchasedAt = null;
        if (row.path("trnTimestamp").isNumber()) {
            long ms = row.path("trnTimestamp").asLong();
            if (ms > 0) {
                purchasedAt = Instant.ofEpochMilli(ms);
            }
        }
        List<PublicKplcConceptResponse> concepts = new ArrayList<>();
        JsonNode breakdown = row.path("concepts");
        if (breakdown.isArray()) {
            for (JsonNode item : breakdown) {
                String code = text(item, "codConcept");
                BigDecimal amount = decimal(item.path("amount"));
                if (code == null && amount == null) {
                    continue;
                }
                String safeCode = code == null ? "" : code;
                concepts.add(new PublicKplcConceptResponse(
                        safeCode,
                        KplcConceptCodes.label(safeCode),
                        KplcConceptCodes.kind(safeCode),
                        amount));
            }
        }
        return new PublicKplcTokenResponse(
                purchasedAt,
                decimal(row.path("trnAmount")),
                decimal(row.path("trnUnits")),
                tokenNo.trim(),
                text(row, "recptNo"),
                text(row, "pMethod"),
                List.copyOf(concepts));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String raw = value.asText();
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    private static BigDecimal decimal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        String raw = node.asText();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
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
