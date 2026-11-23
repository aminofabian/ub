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
 * Cursor for the cloud -> till supplier-trade pull (trade orders, and in later
 * slices accounts and invoices), kept in its own file under {@code APP_DATA/conf}
 * so it does not have to thread through the {@link CloudSyncSession} record and
 * every one of its {@code persist} overloads -- the same choice
 * {@link DesktopStockCursor} already makes for the stock-movement replay.
 *
 * <p>Each stream keeps one {@code updated_at} high-water mark. A missing cursor
 * means "first run after trade sync was switched on"; the pull seeds from the
 * epoch so the till mirrors every open order once, then advances.
 *
 * <p>See {@code DESKTOP_SUPPLIER_TRADE_SYNC_SCOPE.md}.
 */
@Service
@Profile("desktop")
@RequiredArgsConstructor
public class DesktopTradeCursor {

    private static final Logger log = LoggerFactory.getLogger(DesktopTradeCursor.class);

    private final ObjectMapper objectMapper;

    @Value("${APP_DATA:${user.home}/.palmart}")
    private String appData;

    /** The last cloud {@code updated_at} this till has mirrored for trade orders. */
    public Optional<Instant> loadTradeOrders() {
        return read("tradeOrders");
    }

    public void saveTradeOrders(Instant cursor) {
        write("tradeOrders", cursor);
    }

    public Optional<Instant> loadTradeAccounts() {
        return read("tradeAccounts");
    }

    public void saveTradeAccounts(Instant cursor) {
        write("tradeAccounts", cursor);
    }

    public Optional<Instant> loadTradeInvoices() {
        return read("tradeInvoices");
    }

    public void saveTradeInvoices(Instant cursor) {
        write("tradeInvoices", cursor);
    }

    private Optional<Instant> read(String field) {
        Path file = cursorFile();
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            JsonNode node = objectMapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            String at = node.path(field).asText(null);
            if (at == null || at.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(Instant.parse(at));
        } catch (IOException | RuntimeException e) {
            log.warn("[DesktopTrade] could not read trade cursor: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Merge one field into the cursor file, keeping every other field already
     * there, so advancing one stream never resets another.
     */
    private synchronized void write(String field, Instant cursor) {
        if (cursor == null) {
            return;
        }
        try {
            Path confDir = Path.of(appData).resolve("conf");
            Files.createDirectories(confDir);
            Path file = cursorFile();
            ObjectNode node = Files.exists(file)
                ? (ObjectNode) objectMapper.readTree(Files.readString(file, StandardCharsets.UTF_8))
                : objectMapper.createObjectNode();
            node.put(field, cursor.toString());
            Files.writeString(
                file,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node),
                StandardCharsets.UTF_8
            );
        } catch (IOException | RuntimeException e) {
            log.warn("[DesktopTrade] could not write trade cursor: {}", e.getMessage());
        }
    }

    private Path cursorFile() {
        return Path.of(appData).resolve("conf/trade-cursor.json");
    }
}
