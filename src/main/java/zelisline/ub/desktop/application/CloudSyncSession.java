package zelisline.ub.desktop.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import zelisline.ub.identity.api.dto.LoginResponse;
import zelisline.ub.identity.api.dto.RefreshRequest;
import zelisline.ub.identity.application.RefreshTokenCookieSupport;

/**
 * Owns the {@code APP_DATA/conf/cloud-sync.json} mapping between the desktop
 * install and the online shop: cloud origin, cloud business id, session tokens
 * and the cloud owner user id (used to attribute pushed sales).
 *
 * <p>Written by {@link DesktopConnectService} at connect time; refreshed here
 * when the stored access token expires (the cloud's {@code /api/v1/auth/refresh}
 * exchanges the stored refresh token for a fresh pair).
 *
 * <p>Also persists the incremental pull cursors so a re-sync resumes where it
 * left off: {@code lastSalesPullAt} (cloud sales), {@code lastMessagesPullAt}
 * (storefront Talk to Us messages + their reply threads — advanced to the
 * newest activity timestamp seen), {@code lastSuppliesPullAt} (cloud-entered
 * Path B supplies) and {@code lastWebOrdersPullAt} (storefront web orders).
 */
@Service
@Profile("desktop")
@RequiredArgsConstructor
public class CloudSyncSession {

    private static final Logger log = LoggerFactory.getLogger(CloudSyncSession.class);

    private final ObjectMapper objectMapper;

    @Value("${APP_DATA:${user.home}/.palmart}")
    private String appData;

    public record Session(
            String origin,
            String cloudBusinessId,
            String accessToken,
            String refreshToken,
            String ownerUserId,
            List<String> staffIds,
            java.time.Instant lastSalesPullAt,
            java.time.Instant lastMessagesPullAt,
            java.time.Instant lastSuppliesPullAt,
            java.time.Instant lastWebOrdersPullAt
    ) {
        public Session {
            staffIds = staffIds == null ? List.of() : List.copyOf(staffIds);
        }
    }

    public Optional<Session> load() {
        Path file = mappingFile();
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            JsonNode node = objectMapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            List<String> staffIds = new ArrayList<>();
            JsonNode staffNode = node.path("staffIds");
            if (staffNode.isArray()) {
                staffNode.forEach(id -> {
                    String value = id.asText(null);
                    if (value != null && !value.isBlank()) {
                        staffIds.add(value);
                    }
                });
            }
            return Optional.of(new Session(
                node.path("origin").asText(null),
                node.path("cloudBusinessId").asText(null),
                node.path("accessToken").asText(null),
                node.path("refreshToken").asText(null),
                node.path("ownerUserId").asText(null),
                staffIds,
                readInstant(node, "lastSalesPullAt"),
                readInstant(node, "lastMessagesPullAt"),
                readInstant(node, "lastSuppliesPullAt"),
                readInstant(node, "lastWebOrdersPullAt")
            ));
        } catch (IOException | RuntimeException e) {
            log.warn("[CloudSync] could not read cloud-sync.json: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static java.time.Instant readInstant(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value == null ? null : java.time.Instant.parse(value);
    }

    public void persist(Session session) {
        persist(
            session.origin(),
            session.cloudBusinessId(),
            session.accessToken(),
            session.refreshToken(),
            session.ownerUserId(),
            session.staffIds(),
            session.lastSalesPullAt(),
            session.lastMessagesPullAt(),
            session.lastSuppliesPullAt(),
            session.lastWebOrdersPullAt()
        );
    }

    /** Re-persist the session with a fresh staff id list (after a pull). */
    public void persistStaffIds(Session current, List<String> staffIds) {
        persist(
            current.origin(),
            current.cloudBusinessId(),
            current.accessToken(),
            current.refreshToken(),
            current.ownerUserId(),
            staffIds,
            current.lastSalesPullAt(),
            current.lastMessagesPullAt(),
            current.lastSuppliesPullAt(),
            current.lastWebOrdersPullAt()
        );
    }

    /** Re-persist the session with the cloud-sales pull cursor advanced. */
    public void persistLastSalesPullAt(Session current, java.time.Instant lastSalesPullAt) {
        persist(
            current.origin(),
            current.cloudBusinessId(),
            current.accessToken(),
            current.refreshToken(),
            current.ownerUserId(),
            current.staffIds(),
            lastSalesPullAt,
            current.lastMessagesPullAt(),
            current.lastSuppliesPullAt(),
            current.lastWebOrdersPullAt()
        );
    }

    /** Re-persist the session with the cloud-messages pull cursor advanced. */
    public void persistLastMessagesPullAt(Session current, java.time.Instant lastMessagesPullAt) {
        persist(
            current.origin(),
            current.cloudBusinessId(),
            current.accessToken(),
            current.refreshToken(),
            current.ownerUserId(),
            current.staffIds(),
            current.lastSalesPullAt(),
            lastMessagesPullAt,
            current.lastSuppliesPullAt(),
            current.lastWebOrdersPullAt()
        );
    }

    /** Re-persist the session with the cloud-supplies pull cursor advanced. */
    public void persistLastSuppliesPullAt(Session current, java.time.Instant lastSuppliesPullAt) {
        persist(
            current.origin(),
            current.cloudBusinessId(),
            current.accessToken(),
            current.refreshToken(),
            current.ownerUserId(),
            current.staffIds(),
            current.lastSalesPullAt(),
            current.lastMessagesPullAt(),
            lastSuppliesPullAt,
            current.lastWebOrdersPullAt()
        );
    }

    /** Re-persist the session with the cloud web-orders pull cursor advanced. */
    public void persistLastWebOrdersPullAt(Session current, java.time.Instant lastWebOrdersPullAt) {
        persist(
            current.origin(),
            current.cloudBusinessId(),
            current.accessToken(),
            current.refreshToken(),
            current.ownerUserId(),
            current.staffIds(),
            current.lastSalesPullAt(),
            current.lastMessagesPullAt(),
            current.lastSuppliesPullAt(),
            lastWebOrdersPullAt
        );
    }

    public void persist(
            String origin,
            String cloudBusinessId,
            String accessToken,
            String refreshToken,
            String ownerUserId) {
        persist(origin, cloudBusinessId, accessToken, refreshToken, ownerUserId, List.of(), null, null, null, null);
    }

    public void persist(
            String origin,
            String cloudBusinessId,
            String accessToken,
            String refreshToken,
            String ownerUserId,
            List<String> staffIds) {
        persist(origin, cloudBusinessId, accessToken, refreshToken, ownerUserId, staffIds, null, null, null, null);
    }

    public void persist(
            String origin,
            String cloudBusinessId,
            String accessToken,
            String refreshToken,
            String ownerUserId,
            List<String> staffIds,
            java.time.Instant lastSalesPullAt) {
        persist(origin, cloudBusinessId, accessToken, refreshToken, ownerUserId, staffIds,
            lastSalesPullAt, null, null, null);
    }

    public void persist(
            String origin,
            String cloudBusinessId,
            String accessToken,
            String refreshToken,
            String ownerUserId,
            List<String> staffIds,
            java.time.Instant lastSalesPullAt,
            java.time.Instant lastMessagesPullAt) {
        persist(origin, cloudBusinessId, accessToken, refreshToken, ownerUserId, staffIds,
            lastSalesPullAt, lastMessagesPullAt, null, null);
    }

    public void persist(
            String origin,
            String cloudBusinessId,
            String accessToken,
            String refreshToken,
            String ownerUserId,
            List<String> staffIds,
            java.time.Instant lastSalesPullAt,
            java.time.Instant lastMessagesPullAt,
            java.time.Instant lastSuppliesPullAt) {
        persist(origin, cloudBusinessId, accessToken, refreshToken, ownerUserId, staffIds,
            lastSalesPullAt, lastMessagesPullAt, lastSuppliesPullAt, null);
    }

    public void persist(
            String origin,
            String cloudBusinessId,
            String accessToken,
            String refreshToken,
            String ownerUserId,
            List<String> staffIds,
            java.time.Instant lastSalesPullAt,
            java.time.Instant lastMessagesPullAt,
            java.time.Instant lastSuppliesPullAt,
            java.time.Instant lastWebOrdersPullAt) {
        try {
            Path confDir = Path.of(appData).resolve("conf");
            Files.createDirectories(confDir);
            ObjectNode node = objectMapper.createObjectNode();
            node.put("origin", origin);
            node.put("cloudBusinessId", cloudBusinessId);
            if (accessToken != null && !accessToken.isBlank()) {
                node.put("accessToken", accessToken);
            }
            if (refreshToken != null && !refreshToken.isBlank()) {
                node.put("refreshToken", refreshToken);
            }
            if (ownerUserId != null && !ownerUserId.isBlank()) {
                node.put("ownerUserId", ownerUserId);
            }
            if (staffIds != null) {
                com.fasterxml.jackson.databind.node.ArrayNode arr = node.putArray("staffIds");
                staffIds.stream().filter(id -> id != null && !id.isBlank()).forEach(arr::add);
            }
            if (lastSalesPullAt != null) {
                node.put("lastSalesPullAt", lastSalesPullAt.toString());
            }
            if (lastMessagesPullAt != null) {
                node.put("lastMessagesPullAt", lastMessagesPullAt.toString());
            }
            if (lastSuppliesPullAt != null) {
                node.put("lastSuppliesPullAt", lastSuppliesPullAt.toString());
            }
            if (lastWebOrdersPullAt != null) {
                node.put("lastWebOrdersPullAt", lastWebOrdersPullAt.toString());
            }
            node.put("connectedAt", Instant.now().toString());
            Files.writeString(
                mappingFile(),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node),
                StandardCharsets.UTF_8
            );
        } catch (IOException e) {
            log.warn("[CloudSync] could not write cloud-sync.json: {}", e.getMessage());
        }
    }

    /**
     * Exchange the stored refresh token for a fresh access/refresh pair.
     * Returns an empty session when there is no refresh token or the cloud
     * rejects it (the owner must reconnect).
     */
    public Optional<Session> refresh(RestClient client, Session current) {
        if (current.refreshToken() == null || current.refreshToken().isBlank()) {
            return Optional.empty();
        }
        try {
            // Platform apex hosts (palmart.co.ke, kiosk.zelisline.com) need the
            // X-Tenant-Id header to route the refresh to the right tenant. The
            // refresh token is sent in the body; when cookie-mode auth is on,
            // the response rotates it via the httpOnly `ub.refresh` cookie.
            ResponseEntity<LoginResponse> response = client
                .post()
                .uri("/api/v1/auth/refresh")
                .header("X-Tenant-Id", current.cloudBusinessId())
                .header("X-Kiosk-Client", "native")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new RefreshRequest(current.refreshToken()))
                .retrieve()
                .toEntity(LoginResponse.class);
            LoginResponse resp = response.getBody();
            if (resp == null || resp.accessToken() == null || resp.accessToken().isBlank()) {
                return Optional.empty();
            }
            String refreshToken = resp.refreshToken();
            if (refreshToken == null || refreshToken.isBlank()) {
                refreshToken = extractRefreshCookie(response.getHeaders());
            }
            if (refreshToken == null || refreshToken.isBlank()) {
                refreshToken = current.refreshToken();
            }
            Session next = new Session(
                current.origin(),
                current.cloudBusinessId(),
                resp.accessToken(),
                refreshToken,
                current.ownerUserId(),
                current.staffIds(),
                current.lastSalesPullAt(),
                current.lastMessagesPullAt(),
                current.lastSuppliesPullAt(),
                current.lastWebOrdersPullAt()
            );
            persist(next);
            log.info("[CloudSync] refreshed cloud session for {}", current.origin());
            return Optional.of(next);
        } catch (Exception e) {
            log.warn("[CloudSync] token refresh failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static String extractRefreshCookie(HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        java.util.List<String> setCookies = headers.get(HttpHeaders.SET_COOKIE);
        if (setCookies == null) {
            return null;
        }
        String prefix = RefreshTokenCookieSupport.COOKIE_NAME + "=";
        for (String cookie : setCookies) {
            String first = cookie.trim();
            int semi = first.indexOf(';');
            if (semi >= 0) {
                first = first.substring(0, semi);
            }
            if (first.startsWith(prefix)) {
                return first.substring(prefix.length());
            }
        }
        return null;
    }

    private Path mappingFile() {
        return Path.of(appData).resolve("conf/cloud-sync.json");
    }
}
