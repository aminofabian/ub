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
 *
 * <p>The {@code keySource}/{@code keySyncedAt}/{@code keySyncOk} fields are
 * verification diagnostics for support: which public key the till is verifying
 * against (console-synced vs baked fallback) and whether its platform key sync
 * is working. They are null when there is nothing to report (e.g. never synced).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LicenseStatus(
        /** {@code active}, {@code trial}, {@code expired}, or {@code invalid}. */
        String state,
        /** Human‑readable message for the banner / status bar. */
        String message,
        /** License plan: the shop's cloud subscription tier ({@code free}, {@code starter},
         *  {@code business}, {@code growth}, {@code enterprise}), or null pre‑license. */
        String plan,
        /** Days remaining until expiry (trial or licensed). Negative = expired. */
        Long daysRemaining,
        /** ISO‑8601 expiry instant, or null for perpetual / pre‑setup. */
        Instant expiresAt,
        /** When true, the UI must prevent any write operation. */
        boolean readOnly,
        /** This machine's fingerprint (SHA‑256 hex) — the vendor needs it to issue a bound license. */
        String machineId,
        /**
         * Which public key verification runs against: {@code synced} (console
         * key synced from the platform), {@code baked} (the in‑JAR fallback), or
         * {@code none} (trial‑only).
         */
        String keySource,
        /** When the till last attempted a key sync from the platform (null = never). */
        Instant keySyncedAt,
        /** Whether the last key‑sync attempt reached the platform (null = never synced). */
        Boolean keySyncOk
) {
    // ── factory methods ────────────────────────────────────────────────

    static LicenseStatus valid(String plan, Instant expiresAt, long daysRemaining) {
        return new LicenseStatus(
                "active",
                "Licensed — " + plan + " plan" + (expiresAt != null ? " (expires " + expiresAt + ")" : ""),
                plan,
                daysRemaining,
                expiresAt,
                false,
                null,
                null,
                null,
                null
        );
    }

    static LicenseStatus expired(String plan, Instant expiresAt) {
        return new LicenseStatus(
                "expired",
                plan + " license expired on " + expiresAt + ". Please renew to continue.",
                plan,
                0L,
                expiresAt,
                true,
                null,
                null,
                null,
                null
        );
    }

    static LicenseStatus invalid(String message) {
        return new LicenseStatus(
                "invalid",
                message,
                null,
                null,
                null,
                true,
                null,
                null,
                null,
                null
        );
    }

    static LicenseStatus trialActive(long daysRemaining) {
        return new LicenseStatus(
                "trial",
                "Free trial — " + daysRemaining + " day" + (daysRemaining == 1 ? "" : "s") + " remaining.",
                null,
                daysRemaining,
                null,
                false,
                null,
                null,
                null,
                null
        );
    }

    static LicenseStatus trialExpired(Instant expiredAt) {
        return new LicenseStatus(
                "trial_expired",
                "Your 30‑day trial has ended. Enter a license key to continue.",
                null,
                0L,
                expiredAt,
                true,
                null,
                null,
                null,
                null
        );
    }

    /** Copy with the machine id attached (the Settings page shows it regardless of state). */
    LicenseStatus withMachineId(String machineId) {
        return new LicenseStatus(
            state, message, plan, daysRemaining, expiresAt, readOnly, machineId,
            keySource, keySyncedAt, keySyncOk);
    }

    /**
     * Copy with the plan replaced by the shop's current cloud tier. The plan
     * inside a signed token is a snapshot from issue time — when the shop
     * upgrades on the cloud afterwards, the till must still report the new
     * tier (the token's plan is only a fallback for offline shops).
     */
    LicenseStatus withPlan(String newPlan) {
        if (newPlan == null || newPlan.isBlank() || newPlan.equals(plan)) {
            return this;
        }
        String refreshedMessage = switch (state) {
            case "active" -> "Licensed — " + newPlan + " plan"
                    + (expiresAt != null ? " (expires " + expiresAt + ")" : "");
            case "expired" -> newPlan + " license expired on " + expiresAt + ". Please renew to continue.";
            default -> message;
        };
        return new LicenseStatus(
            state, refreshedMessage, newPlan, daysRemaining, expiresAt, readOnly, machineId,
            keySource, keySyncedAt, keySyncOk);
    }

    /** Copy with the verification-key diagnostics attached (status endpoint). */
    LicenseStatus withVerificationDetails(String keySource, Instant keySyncedAt, Boolean keySyncOk) {
        return new LicenseStatus(
            state, message, plan, daysRemaining, expiresAt, readOnly, machineId,
            keySource, keySyncedAt, keySyncOk);
    }
}
