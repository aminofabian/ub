package zelisline.ub.desktop.license;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.application.NotificationService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Signs and persists Kiosk Desktop license tokens from the cloud side.
 *
 * <p>Extracted from {@link SuperAdminDesktopLicensesController} so the same
 * issuance + email path serves both the manual console ("Issue &amp; email") and
 * the automatic activation of a paid shop's install (Super Admin → Platform →
 * Desktop licenses → Installs).
 */
@Service
@RequiredArgsConstructor
public class DesktopLicenseIssuanceService {

    public static final long MAX_DAYS = 36500; // 100 years

    private final DesktopLicenseIssuer issuer;
    private final NotificationService notificationService;
    private final DesktopLicenseIssueRepository issueRepository;
    private final BusinessRepository businessRepository;

    /**
     * Signs a token and stores its history row. When {@code emailTo} is present
     * the token is also emailed before persisting; a delivery failure surfaces
     * as 502 so the console can show the token instead.
     *
     * @return the persisted issue row (its {@code token} field is the signed token)
     */
    public DesktopLicenseIssue issue(
            String businessName,
            String plan,
            Integer days,
            String expiresAt,
            Boolean perpetual,
            String fingerprint,
            String emailTo) {
        String name = businessName.trim();
        Instant expires = resolveExpiry(days, expiresAt, perpetual);
        String resolvedPlan = resolvePlan(plan, name);
        DesktopLicenseIssuer.IssuedLicense issued = issuer.issue(
            name,
            resolvedPlan,
            expires,
            blankToNull(fingerprint)
        );

        boolean emailed = false;
        if (emailTo != null && !emailTo.isBlank()) {
            sendLicenseEmail(
                emailTo.trim(),
                issued.token(),
                issued.payload().businessName(),
                issued.payload().plan(),
                issued.payload().expiresAt()
            );
            emailed = true;
        }

        Instant now = Instant.now();
        DesktopLicenseIssue row = new DesktopLicenseIssue();
        row.setId(UUID.randomUUID().toString());
        row.setBusinessName(issued.payload().businessName());
        row.setPlan(issued.payload().plan());
        row.setIssuedAt(issued.payload().issuedAt());
        row.setExpiresAt(issued.payload().expiresAt());
        row.setMachineFingerprint(issued.payload().machineFingerprint());
        row.setRecipientEmail(emailTo == null || emailTo.isBlank() ? null : emailTo.trim());
        row.setEmailSent(emailed);
        row.setToken(issued.token());
        row.setCreatedAt(now);
        return issueRepository.save(row);
    }

    /**
     * Whether a machine already holds a token that is still valid (perpetual or
     * not yet expired) — used to avoid re-issuing on every check-in.
     */
    public boolean hasActiveLicenseFor(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return false;
        }
        return issueRepository.countActiveByMachineFingerprint(fingerprint.trim(), Instant.now()) > 0;
    }

    /**
     * The desktop license plan mirrors the shop's cloud subscription tier so the
     * till and the online shop always agree. A blank plan uses the business's
     * current tier (fallback: {@code starter}).
     */
    public String resolvePlan(String plan, String businessName) {
        if (plan != null && !plan.isBlank()) {
            return plan.trim().toLowerCase(Locale.ROOT);
        }
        return businessRepository
            .findFirstByNameIgnoreCaseAndDeletedAtIsNull(businessName)
            .map(Business::getSubscriptionTier)
            .filter(t -> t != null && !t.isBlank())
            .map(t -> t.trim().toLowerCase(Locale.ROOT))
            .orElse("starter");
    }

    public Instant resolveExpiry(Integer days, String expiresAt, Boolean perpetual) {
        if (Boolean.TRUE.equals(perpetual)) {
            return null;
        }
        if (days != null) {
            if (days < 1 || days > MAX_DAYS) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "days must be between 1 and " + MAX_DAYS
                );
            }
            return Instant.now().plus(days, ChronoUnit.DAYS);
        }
        if (expiresAt != null && !expiresAt.isBlank()) {
            try {
                Instant at = Instant.parse(expiresAt.trim());
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

    public void sendLicenseEmail(
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

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
