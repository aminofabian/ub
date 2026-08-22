package zelisline.ub.desktop.license;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.application.NotificationService;

/**
 * Super Admin → Platform → Desktop licenses: issue Ed25519-signed Kiosk Desktop
 * license tokens and optionally email them to the shop owner.
 *
 * <p>Secured by {@code ROLE_SUPER_ADMIN} via {@code /api/v1/super-admin/**} in
 * {@code SecurityConfig}. The signing key is resolved by {@link DesktopLicenseIssuer}:
 * the deployment env var ({@code APP_DESKTOP_LICENSE_PRIVATE_KEY}) first, then the
 * console-managed key in {@code desktop_license_issuer_config} — so it can be
 * configured from this console without a redeploy. Without any key the issuer
 * reports unconfigured and {@code /issue} returns 503.
 */
@RestController
@RequestMapping("/api/v1/super-admin/desktop-licenses")
@RequiredArgsConstructor
public class SuperAdminDesktopLicensesController {

    private static final long MAX_DAYS = 36500; // 100 years
    private static final int MAX_LIST_LIMIT = 200;

    private final DesktopLicenseIssuer issuer;
    private final NotificationService notificationService;
    private final DesktopLicenseIssueRepository issueRepository;
    private final DesktopLicenseIssuerConfigService issuerConfigService;

    /** Issuer configuration + public-key sync hint for the console UI. */
    @GetMapping("/status")
    public DesktopLicenseIssuerConfigService.IssuerStatus status() {
        return issuerConfigService.status();
    }

    /** Store a signing key pasted from {@code generate-license.sh keys}. */
    @PostMapping("/issuer/key")
    public DesktopLicenseIssuerConfigService.IssuerStatus setIssuerKey(
            @Valid @RequestBody DesktopLicenseIssuerConfigService.SetIssuerKeyRequest body) {
        return issuerConfigService.setKey(body);
    }

    /** Generate a fresh signing key pair in the console (returns the public key). */
    @PostMapping("/issuer/generate")
    public DesktopLicenseIssuerConfigService.GenerateKeyResult generateIssuerKey() {
        return issuerConfigService.generate();
    }

    /** Remove the console-managed signing key (env var still wins if set). */
    @DeleteMapping("/issuer/key")
    public DesktopLicenseIssuerConfigService.IssuerStatus clearIssuerKey() {
        return issuerConfigService.clear();
    }

    /** Recent issued licenses, newest first. */
    @GetMapping
    public List<IssueRecord> list(
            @RequestParam(defaultValue = "50") int limit) {
        int capped = Math.min(Math.max(limit, 1), MAX_LIST_LIMIT);
        return issueRepository
            .findAllByOrderByCreatedAtDesc(PageRequest.of(0, capped))
            .stream()
            .map(SuperAdminDesktopLicensesController::toRecord)
            .toList();
    }

    /** Re-email the stored token of a previously issued license. */
    @PostMapping("/{id}/resend")
    public IssueRecord resend(@PathVariable String id) {
        DesktopLicenseIssue row = issueRepository
            .findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "License issue not found"));
        if (row.getRecipientEmail() == null || row.getRecipientEmail().isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "This license was never emailed — issue a new one with an email address."
            );
        }
        sendLicenseEmail(row.getRecipientEmail(), row.getToken(), row.getBusinessName(), row.getPlan(), row.getExpiresAt());
        row.setEmailSent(true);
        DesktopLicenseIssue saved = issueRepository.save(row);
        return toRecord(saved);
    }

    /** Sign a token; the console shows it with a copy button. */
    @PostMapping("/issue")
    public IssueResponse issue(@Valid @RequestBody IssueRequest request) {
        return doIssue(request, null);
    }

    /** Sign a token and email it to the shop owner. */
    @PostMapping("/issue-and-email")
    public IssueResponse issueAndEmail(@Valid @RequestBody IssueRequest request) {
        if (request.email() == null || request.email().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An email address is required");
        }
        return doIssue(request, request.email().trim());
    }

    private IssueResponse doIssue(IssueRequest request, String emailTo) {
        Instant expiresAt = resolveExpiry(request);
        String plan = request.plan() == null || request.plan().isBlank() ? "shop" : request.plan().trim();
        DesktopLicenseIssuer.IssuedLicense issued = issuer.issue(
            request.businessName().trim(),
            plan,
            expiresAt,
            blankToNull(request.fingerprint())
        );
        boolean emailed = false;
        if (emailTo != null) {
            sendLicenseEmail(
                emailTo,
                issued.token(),
                issued.payload().businessName(),
                issued.payload().plan(),
                issued.payload().expiresAt()
            );
            emailed = true;
        }

        // Persist the issuance so the console can list it and resend the email.
        Instant now = Instant.now();
        DesktopLicenseIssue row = new DesktopLicenseIssue();
        row.setId(UUID.randomUUID().toString());
        row.setBusinessName(issued.payload().businessName());
        row.setPlan(issued.payload().plan());
        row.setIssuedAt(issued.payload().issuedAt());
        row.setExpiresAt(issued.payload().expiresAt());
        row.setMachineFingerprint(issued.payload().machineFingerprint());
        row.setRecipientEmail(emailTo);
        row.setEmailSent(emailed);
        row.setToken(issued.token());
        row.setCreatedAt(now);
        DesktopLicenseIssue saved = issueRepository.save(row);

        return toResponse(saved, issued.token());
    }

    private Instant resolveExpiry(IssueRequest request) {
        if (Boolean.TRUE.equals(request.perpetual())) {
            return null;
        }
        if (request.days() != null) {
            if (request.days() < 1 || request.days() > MAX_DAYS) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "days must be between 1 and " + MAX_DAYS
                );
            }
            return Instant.now().plus(request.days(), ChronoUnit.DAYS);
        }
        if (request.expiresAt() != null && !request.expiresAt().isBlank()) {
            try {
                Instant at = Instant.parse(request.expiresAt().trim());
                if (!at.isAfter(Instant.now())) {
                    throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "expiresAt must be in the future"
                    );
                }
                return at;
            } catch (DateTimeParseException e) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "expiresAt must be an ISO-8601 instant (e.g. 2027-08-20T00:00:00Z)"
                );
            }
        }
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Provide one of days, expiresAt, or perpetual"
        );
    }

    private void sendLicenseEmail(
            String toEmail,
            String token,
            String businessName,
            String plan,
            Instant expiresAt) {
        String expiry = expiresAt == null ? "never (perpetual)" : expiresAt.toString();
        String subject = "Your Kiosk Desktop license for " + businessName;
        String text = "Your Kiosk Desktop license is ready.\n\n"
            + "Shop: " + businessName + "\n"
            + "Plan: " + plan + "\n"
            + "Expires: " + expiry + "\n\n"
            + "Open Kiosk Desktop → Settings → License, paste the token below, and click Apply license.\n\n"
            + "License token:\n" + token + "\n";
        String html = "<div style=\"font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;max-width:560px;margin:0 auto;padding:24px\">"
            + "<h2 style=\"margin:0 0 12px\">Your Kiosk Desktop license is ready</h2>"
            + "<table style=\"border-collapse:collapse;font-size:14px;margin:16px 0\">"
            + "<tr><td style=\"padding:4px 16px 4px 0;color:#555\">Shop</td><td style=\"font-weight:600\">" + esc(businessName) + "</td></tr>"
            + "<tr><td style=\"padding:4px 16px 4px 0;color:#555\">Plan</td><td style=\"font-weight:600\">" + esc(plan) + "</td></tr>"
            + "<tr><td style=\"padding:4px 16px 4px 0;color:#555\">Expires</td><td style=\"font-weight:600\">" + esc(expiry) + "</td></tr>"
            + "</table>"
            + "<p style=\"font-size:14px;line-height:1.6;margin:0 0 8px\">Open <b>Kiosk Desktop → Settings → License</b>, paste the token below, then click <b>Apply license</b>.</p>"
            + "<div style=\"background:#f6f8fa;border:1px solid #d0d7de;border-radius:8px;padding:12px 14px;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:12px;word-break:break-all;line-height:1.5\">"
            + esc(token)
            + "</div>"
            + "</div>";
        try {
            notificationService.sendNotificationEmail(toEmail, subject, text, html);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "The license was issued but emailing it failed (" + e.getMessage() + "). Copy the token from the response instead."
            );
        }
    }

    private static IssueResponse toResponse(DesktopLicenseIssue row, String token) {
        return new IssueResponse(
            row.getId(),
            token,
            row.getBusinessName(),
            row.getPlan(),
            row.getIssuedAt(),
            row.getExpiresAt(),
            row.getMachineFingerprint(),
            row.getRecipientEmail(),
            row.isEmailSent(),
            row.getCreatedAt()
        );
    }

    private static IssueRecord toRecord(DesktopLicenseIssue row) {
        return new IssueRecord(
            row.getId(),
            row.getBusinessName(),
            row.getPlan(),
            row.getIssuedAt(),
            row.getExpiresAt(),
            row.getMachineFingerprint(),
            row.getRecipientEmail(),
            row.isEmailSent(),
            row.getCreatedAt()
        );
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public record IssueRequest(
            @NotBlank String businessName,
            @Pattern(regexp = "counter|shop|lan", message = "plan must be counter, shop, or lan") String plan,
            Integer days,
            String expiresAt,
            Boolean perpetual,
            /** The till's Machine ID (Settings → License) — required so the key only works on that machine. */
            @NotBlank(message = "Machine ID is required — get it from the till (Settings → License)") String fingerprint,
            @Email(message = "email must be a valid address") String email
    ) {}

    public record IssueResponse(
            String id,
            String token,
            String businessName,
            String plan,
            Instant issuedAt,
            Instant expiresAt,
            String machineFingerprint,
            String emailedTo,
            boolean emailSent,
            Instant createdAt
    ) {}

    /** List/row projection (no token — the console shows tokens only right after issuing). */
    public record IssueRecord(
            String id,
            String businessName,
            String plan,
            Instant issuedAt,
            Instant expiresAt,
            String machineFingerprint,
            String recipientEmail,
            boolean emailSent,
            Instant createdAt
    ) {}
}
