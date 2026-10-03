package zelisline.ub.platform.installs;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.platform.logs.DesktopLogIngestProperties;

/**
 * Check-in endpoint for Kiosk Desktop installs (Super Admin → Platform →
 * Desktop licenses → Installs).
 *
 * <p>Public (see {@code SecurityConfig}). The shared
 * {@code X-Desktop-Log-Ingest-Key} is enforced only when the platform sets one
 * — an unset key keeps registration open so installs appear without any till
 * configuration. A check-in carries the till's Machine ID and shop name; the
 * cloud upserts {@code desktop_installs} and, when the shop is connected and
 * already paid, issues an activation key automatically.
 *
 * <p>Best-effort by design: the till sends this fire-and-forget when it happens
 * to be online, so nothing about it may block the shop.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/platform/desktop-installs")
@RequiredArgsConstructor
public class DesktopInstallIngestController {

    private static final int MAX_ID = 64;
    private static final int MAX_NAME = 191;

    private final DesktopLogIngestProperties properties;
    private final DesktopInstallService installService;

    public record RegisterRequest(
            String installId,
            String machineId,
            String businessName,
            String contactEmail,
            String appVersion,
            String platform,
            String cloudBusinessId,
            String licenseState,
            String plan) {}

    public record RegisterResponse(
            boolean registered,
            boolean autoActivated,
            String installId,
            Instant lastSeenAt) {}

    @PostMapping(consumes = "application/json")
    public ResponseEntity<?> register(
            @RequestHeader(value = "X-Desktop-Log-Ingest-Key", required = false) String key,
            @RequestBody RegisterRequest body,
            HttpServletRequest request) {

        // The shared key is optional: enforced when the platform sets one, open
        // otherwise so a fresh install shows up with no till configuration.
        if (!properties.getKey().isBlank() && !properties.getKey().equals(key)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(java.util.Map.of("title", "Invalid ingest key"));
        }
        String installId = trimToNull(body == null ? null : body.installId());
        String machineId = trimToNull(installId == null ? null : body.machineId());
        String businessName = trimToNull(body == null ? null : body.businessName());
        if (installId == null || installId.length() > MAX_ID) {
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of("title", "installId is required (max 64 chars)"));
        }
        if (machineId == null || machineId.length() > MAX_ID) {
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of("title", "machineId is required (max 64 chars)"));
        }
        if (businessName == null || businessName.length() > MAX_NAME) {
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of("title", "businessName is required (max 191 chars)"));
        }

        DesktopInstallService.Registration registration = new DesktopInstallService.Registration(
                installId,
                machineId,
                businessName,
                trimToNull(body.contactEmail()),
                trimToNull(body.appVersion()),
                trimToNull(body.platform()),
                trimToNull(body.cloudBusinessId()),
                trimToNull(body.licenseState()),
                trimToNull(body.plan()));
        DesktopInstall saved = installService.register(registration, request.getRemoteAddr());

        log.info("Desktop install checked in: installId={} business='{}' cloudBiz={}",
                saved.getInstallId(), saved.getBusinessName(), saved.getCloudBusinessId());
        return ResponseEntity.status(HttpStatus.CREATED).body(new RegisterResponse(
                true,
                saved.getAutoIssuedAt() != null,
                saved.getInstallId(),
                saved.getLastSeenAt()));
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
