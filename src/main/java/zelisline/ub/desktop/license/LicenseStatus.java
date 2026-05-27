package zelisline.ub.desktop.license;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Current license state returned to the frontend by
 * {@code GET /api/v1/license/status} (see {@code DESKTOP_INSTALLATION.md} §10).
 *
 * <p>The frontend uses the {@link #readOnly} flag to disable write operations:
 * no new sales, no stock receipts, no inventory adjustments. Reports and
 * history remain visible.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LicenseStatus(
        /** {@code active}, {@code trial}, {@code expired}, or {@code invalid}. */
        String state,
        /** Human‑readable message for the banner / status bar. */
        String message,
        /** License plan tier: {@code counter}, {@code shop}, {@code lan}, or null. */
        String plan,
        /** Days remaining until expiry (trial or licensed). Negative = expired. */
        Long daysRemaining,
        /** ISO‑8601 expiry instant, or null for perpetual / pre‑setup. */
        Instant expiresAt,
        /** When true, the UI must prevent any write operation. */
        boolean readOnly
) {
    // ── factory methods ────────────────────────────────────────────────

    static LicenseStatus valid(String plan, Instant expiresAt, long daysRemaining) {
        return new LicenseStatus(
                "active",
                "Licensed — " + plan + " plan" + (expiresAt != null ? " (expires " + expiresAt + ")" : ""),
                plan,
                daysRemaining,
                expiresAt,
                false
        );
    }

    static LicenseStatus expired(String plan, Instant expiresAt) {
        return new LicenseStatus(
                "expired",
                plan + " license expired on " + expiresAt + ". Please renew to continue.",
                plan,
                0L,
                expiresAt,
                true
        );
    }

    static LicenseStatus invalid(String message) {
        return new LicenseStatus(
                "invalid",
                message,
                null,
                null,
                null,
                true
        );
    }

    static LicenseStatus trialActive(long daysRemaining) {
        return new LicenseStatus(
                "trial",
                "Free trial — " + daysRemaining + " day" + (daysRemaining == 1 ? "" : "s") + " remaining.",
                null,
                daysRemaining,
                null,
                false
        );
    }

    static LicenseStatus trialExpired(Instant expiredAt) {
        return new LicenseStatus(
                "trial_expired",
                "Your 30‑day trial has ended. Enter a license key to continue.",
                null,
                0L,
                expiredAt,
                true
        );
    }
}
