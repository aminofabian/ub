package zelisline.ub.desktop.license;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import kong.unirest.Unirest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the till's license verification key aligned with the Super Admin
 * console's signing key (see {@code DESKTOP_INSTALLATION.md} §10).
 *
 * <p>Offline-first: the till never blocks on this. When the machine happens to
 * be online it polls the platform's {@code GET /api/v1/platform/desktop-license-public-key}
 * endpoint, validates the returned Ed25519 public key, caches it locally in
 * {@code APP_DATA/conf/license-public-key}, and pushes it into {@link
 * LicenseService} — so tokens signed by the console verify without a new bake +
 * release each time the vendor rotates the key. The baked
 * {@code app.desktop.license.public-key} remains the offline fallback for first
 * use before any sync.
 *
 * <p>Never raises and always returns fast: a failed sync (offline, unreachable
 * platform, malformed key) keeps whatever key the till already has and logs a
 * warning at most.
 */
@Component
@Profile("desktop")
public class DesktopLicenseKeySyncer {

    private static final Logger log = LoggerFactory.getLogger(DesktopLicenseKeySyncer.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Minimum spacing between sync attempts (scheduled + paste-triggered). */
    private static final long MIN_SYNC_GAP_MS = 15_000L;

    private final LicenseService licenseService;
    private final boolean enabled;
    private final String syncUrl;
    private final Path keyFile;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private volatile long lastSyncAt = 0L;
    private volatile boolean lastSyncOk = false;

    /** When the till last attempted a key sync (null = never). */
    public Instant lastSyncAt() {
        return lastSyncAt == 0L ? null : Instant.ofEpochMilli(lastSyncAt);
    }

    /** Whether the last key-sync attempt reached the platform (null = never synced). */
    public Boolean lastSyncOk() {
        return lastSyncAt == 0L ? null : lastSyncOk;
    }

    public DesktopLicenseKeySyncer(
            LicenseService licenseService,
            @Value("${APP_DATA:${user.home}/.palmart}") String appDataDir,
            @Value("${app.desktop.license.sync-url:https://kiosk.ke/api/v1/platform/desktop-license-public-key}") String syncUrl,
            @Value("${app.desktop.license.sync-enabled:true}") boolean enabled,
            @Value("${app.desktop.license.sync-connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${app.desktop.license.sync-read-timeout-ms:5000}") int readTimeoutMs) {
        this.licenseService = licenseService;
        this.enabled = enabled;
        this.syncUrl = syncUrl;
        this.keyFile = Path.of(appDataDir).resolve("conf").resolve("license-public-key");
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    /** Loads the last synced key at boot so verification works before the first fetch. */
    @PostConstruct
    void loadPersistedKey() {
        if (!Files.isReadable(keyFile)) {
            return;
        }
        try {
            String base64 = Files.readString(keyFile, StandardCharsets.UTF_8).trim();
            if (!base64.isBlank()) {
                licenseService.updateSyncedPublicKey(LicenseService.decodePublicKey(base64));
            }
        } catch (Exception e) {
            log.warn("[License] ignoring unreadable synced license key at {}: {}", keyFile, e.getMessage());
        }
    }

    @Scheduled(
            fixedDelayString = "${app.desktop.license.sync-interval-ms:21600000}",
            initialDelayString = "${app.desktop.license.sync-initial-delay-ms:120000}")
    public void scheduledSync() {
        syncNow();
    }

    /**
     * Fetches the console's current signing public key and caches it locally.
     * Safe to call from request threads (e.g. {@link LicenseController} retries a
     * failed license paste after syncing). Never throws.
     *
     * @return true when a new key was fetched and activated
     */
    public boolean syncNow() {
        if (!enabled) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastSyncAt < MIN_SYNC_GAP_MS) {
            return false;
        }
        if (!inFlight.compareAndSet(false, true)) {
            return false;
        }
        try {
            lastSyncAt = now;
            var resp = Unirest.get(syncUrl)
                    .connectTimeout(connectTimeoutMs)
                    .socketTimeout(readTimeoutMs)
                    .asString();
            if (!resp.isSuccess()) {
                lastSyncOk = false;
                log.warn("[License] key sync rejected by {} (HTTP {}: {})",
                        syncUrl, resp.getStatus(),
                        resp.getBody() == null ? "" : resp.getBody().trim());
                return false;
            }
            String publicKey = parsePublicKey(resp.getBody());
            if (publicKey == null) {
                // Reached the platform — it just has no console-managed key.
                lastSyncOk = true;
                log.info("[License] platform reports no console-managed license key — keeping the current key.");
                return false;
            }
            PublicKey key = LicenseService.decodePublicKey(publicKey);
            Files.createDirectories(keyFile.getParent());
            Files.writeString(keyFile, publicKey, StandardCharsets.UTF_8);
            licenseService.updateSyncedPublicKey(key);
            lastSyncOk = true;
            log.info("[License] synced console license public key to {}", keyFile);
            return true;
        } catch (Exception e) {
            lastSyncOk = false;
            log.warn("[License] key sync failed (offline?): {}", e.getMessage());
            return false;
        } finally {
            inFlight.set(false);
        }
    }

    /** Extracts and validates {@code publicKey} from the endpoint's JSON body. */
    private String parsePublicKey(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode keyNode = root.get("publicKey");
            if (keyNode == null || !keyNode.isTextual() || keyNode.asText().isBlank()) {
                return null;
            }
            String publicKey = keyNode.asText().trim();
            LicenseService.decodePublicKey(publicKey); // throws if not a valid Ed25519 key
            return publicKey;
        } catch (Exception e) {
            log.warn("[License] key sync returned an invalid public key: {}", e.getMessage());
            return null;
        }
    }
}
