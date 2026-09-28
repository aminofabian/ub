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
import org.springframework.stereotype.Service;

/**
 * Cursor for the cloud→till stock-movement replay, kept in its own file under
 * {@code APP_DATA/conf} so it does not have to thread through the
 * {@link CloudSyncSession} record and every one of its {@code persist}
 * overloads.
 *
 * <p>The cursor is a {@code (createdAt, id)} tuple so paging stays exact when
 * many movements share a timestamp. A missing cursor means "first run after
 * this feature shipped" — the pull seeds it to now rather than replaying
 * history the till already absorbed at connect.
 */
@Service
@Profile("desktop")
@RequiredArgsConstructor
public class DesktopStockCursor {

    private static final Logger log = LoggerFactory.getLogger(DesktopStockCursor.class);

    private final ObjectMapper objectMapper;

    @Value("${APP_DATA:${user.home}/.palmart}")
    private String appData;

    public record Cursor(Instant createdAt, String id) {}

    public Optional<Cursor> load() {
        Path file = cursorFile();
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            JsonNode node = objectMapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            String at = node.path("createdAt").asText(null);
            if (at == null || at.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new Cursor(Instant.parse(at), node.path("id").asText("")));
        } catch (IOException | RuntimeException e) {
            log.warn("[DesktopStock] could not read stock-movements cursor: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void save(Cursor cursor) {
        try {
            Path confDir = Path.of(appData).resolve("conf");
            Files.createDirectories(confDir);
            ObjectNode node = objectMapper.createObjectNode();
            node.put("createdAt", cursor.createdAt().toString());
            node.put("id", cursor.id() == null ? "" : cursor.id());
            Files.writeString(
                cursorFile(),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node),
                StandardCharsets.UTF_8
            );
        } catch (IOException e) {
            log.warn("[DesktopStock] could not write stock-movements cursor: {}", e.getMessage());
        }
    }

    private Path cursorFile() {
        return Path.of(appData).resolve("conf/stock-movements-cursor.json");
    }

    private Path reconciledMarker() {
        return Path.of(appData).resolve("conf/stock-reconciled");
    }

    /** True once the till has pushed its authoritative stock to the cloud. */
    public boolean isReconciled() {
        return Files.exists(reconciledMarker());
    }

    public void markReconciled() {
        try {
            Files.createDirectories(Path.of(appData).resolve("conf"));
            Files.writeString(
                reconciledMarker(), Instant.now().toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[DesktopStock] could not write reconcile marker: {}", e.getMessage());
        }
    }
}
