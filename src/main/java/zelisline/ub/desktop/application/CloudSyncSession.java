package zelisline.ub.desktop.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import zelisline.ub.identity.api.dto.LoginResponse;
import zelisline.ub.identity.api.dto.RefreshRequest;

/**
 * Owns the {@code APP_DATA/conf/cloud-sync.json} mapping between the desktop
 * install and the online shop: cloud origin, cloud business id, session tokens
 * and the cloud owner user id (used to attribute pushed sales).
 *
 * <p>Written by {@link DesktopConnectService} at connect time; refreshed here
 * when the stored access token expires (the cloud's {@code /api/v1/auth/refresh}
 * exchanges the stored refresh token for a fresh pair).
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
            String ownerUserId
    ) {}

    public Optional<Session> load() {
        Path file = mappingFile();
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            JsonNode node = objectMapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            return Optional.of(new Session(
                node.path("origin").asText(null),
                node.path("cloudBusinessId").asText(null),
                node.path("accessToken").asText(null),
                node.path("refreshToken").asText(null),
                node.path("ownerUserId").asText(null)
            ));
        } catch (IOException | RuntimeException e) {
            log.warn("[CloudSync] could not read cloud-sync.json: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void persist(Session session) {
        persist(
            session.origin(),
            session.cloudBusinessId(),
            session.accessToken(),
            session.refreshToken(),
            session.ownerUserId()
        );
    }

    public void persist(
            String origin,
            String cloudBusinessId,
            String accessToken,
            String refreshToken,
            String ownerUserId) {
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
            LoginResponse resp = client
                .post()
                .uri("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new RefreshRequest(current.refreshToken()))
                .retrieve()
                .body(LoginResponse.class);
            if (resp == null || resp.accessToken() == null || resp.accessToken().isBlank()) {
                return Optional.empty();
            }
            Session next = new Session(
                current.origin(),
                current.cloudBusinessId(),
                resp.accessToken(),
                resp.refreshToken() == null ? current.refreshToken() : resp.refreshToken(),
                current.ownerUserId()
            );
            persist(next);
            log.info("[CloudSync] refreshed cloud session for {}", current.origin());
            return Optional.of(next);
        } catch (Exception e) {
            log.warn("[CloudSync] token refresh failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Path mappingFile() {
        return Path.of(appData).resolve("conf/cloud-sync.json");
    }
}
