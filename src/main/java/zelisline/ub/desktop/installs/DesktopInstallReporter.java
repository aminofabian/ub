package zelisline.ub.desktop.installs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import kong.unirest.Unirest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.UbApplication;
import zelisline.ub.desktop.application.CloudSyncSession;
import zelisline.ub.desktop.license.DesktopLicenseGuard;
import zelisline.ub.desktop.license.LicenseStatus;
import zelisline.ub.desktop.license.MachineFingerprintProvider;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Best-effort check-in for Kiosk Desktop installs.
 *
 * <p>The till runs fully offline-first; this reporter only acts when the machine
 * happens to have internet. It POSTs the install id, Machine ID and shop name to
 * the platform so the install appears in Super Admin → Platform → Desktop
 * licenses → Installs, ready for a one-click (or automatic, when paid)
 * activation key — no manual "please send me your Machine ID" step.
 *
 * <p>Every attempt is short-timeout, fire-and-forget and never raises: a failed
 * check-in must never disturb the shop. It shows the install once per launch and
 * then daily, so a till that is usually offline still registers the first time it
 * sees the network. An ingest key is sent when one is configured; the platform
 * keeps registration open when it has none.
 */
@Component
@Profile("desktop")
@ConditionalOnProperty(
        name = "app.desktop.install-reporting.enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class DesktopInstallReporter {

    private static final Logger log = LoggerFactory.getLogger(DesktopInstallReporter.class);
    private static final String OWNER_ROLE_KEY = "owner";

    private final MachineFingerprintProvider fingerprintProvider;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final CloudSyncSession cloudSyncSession;
    private final DesktopLicenseGuard licenseGuard;
    private final ObjectMapper objectMapper;

    @Value("${APP_DATA:${user.home}/.palmart}")
    private String appDataDir;

    @Value("${app.desktop.install-reporting.ingest-url:https://kiosk.ke/api/v1/platform/desktop-installs}")
    private String ingestUrl;

    @Value("${app.desktop.install-reporting.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    @Value("${app.desktop.install-reporting.read-timeout-ms:10000}")
    private int readTimeoutMs;

    @Value("${app.desktop.business-id:}")
    private String businessId;

    @Scheduled(
            initialDelayString = "${app.desktop.install-reporting.initial-delay-ms:60000}",
            fixedDelayString = "${app.desktop.install-reporting.interval-ms:86400000}")
    public void scheduledCheckIn() {
        checkIn();
    }

    /** Sends one check-in; swallows every failure (offline / not set up yet). */
    public void checkIn() {
        try {
            String localBusinessId = businessId == null ? "" : businessId.trim();
            if (localBusinessId.isEmpty()) {
                log.debug("Desktop install reporting skipped — not set up yet.");
                return;
            }
            Business business = businessRepository
                .findByIdAndDeletedAtIsNull(localBusinessId)
                .orElse(null);
            if (business == null || business.getName() == null || business.getName().isBlank()) {
                log.debug("Desktop install reporting skipped — business not found.");
                return;
            }

            String key = resolveIngestKey();
            ObjectNode payload = buildPayload(localBusinessId, business);
            var request = Unirest.post(ingestUrl)
                .header("Content-Type", "application/json")
                .connectTimeout(connectTimeoutMs)
                .socketTimeout(readTimeoutMs);
            if (!key.isBlank()) {
                request.header("X-Desktop-Log-Ingest-Key", key);
            }
            var response = request
                .body(objectMapper.writeValueAsString(payload))
                .asString();
            if (response.isSuccess()) {
                log.info("Desktop install reported to {} (HTTP {})", ingestUrl, response.getStatus());
            } else {
                log.warn("Desktop install check-in rejected by {} (HTTP {}: {})",
                    ingestUrl, response.getStatus(), response.getBody());
            }
        } catch (Exception e) {
            log.debug("Desktop install reporting failed (offline?): {}", e.getMessage());
        }
    }

    private ObjectNode buildPayload(String localBusinessId, Business business) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("installId", resolveInstallIdQuietly());
        payload.put("machineId", fingerprintProvider.get());
        payload.put("businessName", business.getName());
        payload.put("appVersion", appVersion());
        payload.put("platform", platformName());
        String ownerEmail = ownerEmail(localBusinessId);
        if (ownerEmail != null) {
            payload.put("contactEmail", ownerEmail);
        }
        String cloudBusinessId = cloudSyncSession.load()
            .map(CloudSyncSession.Session::cloudBusinessId)
            .orElse(null);
        if (cloudBusinessId != null && !cloudBusinessId.isBlank()) {
            payload.put("cloudBusinessId", cloudBusinessId);
        }
        try {
            LicenseStatus status = licenseGuard.currentStatus();
            if (status != null) {
                if (status.state() != null) {
                    payload.put("licenseState", status.state());
                }
                if (status.plan() != null) {
                    payload.put("plan", status.plan());
                }
            }
        } catch (RuntimeException e) {
            log.debug("Desktop install reporting: license status unavailable ({})", e.getMessage());
        }
        return payload;
    }

    private String ownerEmail(String localBusinessId) {
        return userRepository
            .findActiveByRoleKeyOrderByCreatedAtAsc(localBusinessId, OWNER_ROLE_KEY)
            .stream()
            .map(User::getEmail)
            .filter(e -> e != null && !e.isBlank())
            .findFirst()
            .orElse(null);
    }

    /** Env var first, then a per-install key file — same source as the log reporter. */
    private String resolveIngestKey() {
        String env = System.getenv("APP_DESKTOP_LOG_INGEST_KEY");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        Path keyFile = Path.of(appDataDir).resolve("conf").resolve("log-ingest-key");
        if (Files.isReadable(keyFile)) {
            try {
                return Files.readString(keyFile).trim();
            } catch (IOException e) {
                log.debug("Could not read log-ingest-key file: {}", e.getMessage());
            }
        }
        return "";
    }

    /** Stable per-install id: read {@code APP_DATA/conf/install-id} or create it. */
    private String resolveInstallIdQuietly() {
        Path conf = Path.of(appDataDir).resolve("conf");
        Path file = conf.resolve("install-id");
        try {
            if (Files.isReadable(file)) {
                String existing = Files.readString(file).trim();
                if (!existing.isBlank()) {
                    return existing;
                }
            }
            Files.createDirectories(conf);
            String id = UUID.randomUUID().toString();
            Files.writeString(file, id, StandardCharsets.UTF_8);
            return id;
        } catch (IOException e) {
            // Fall back to an ephemeral id rather than failing the check-in.
            return "unknown-" + UUID.randomUUID();
        }
    }

    private static String appVersion() {
        String v = UbApplication.class.getPackage().getImplementationVersion();
        return (v == null || v.isBlank()) ? "unknown" : v;
    }

    private static String platformName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows";
        if (os.contains("mac")) return "macos";
        if (os.contains("linux")) return "linux";
        return os.isBlank() ? "unknown" : os;
    }
}
