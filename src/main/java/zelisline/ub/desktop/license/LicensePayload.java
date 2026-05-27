package zelisline.ub.desktop.license;

import java.time.Instant;

/**
 * The JSON payload inside a signed license token (see {@code DESKTOP_INSTALLATION.md} §10).
 * Serialised to canonical JSON, signed with the vendor's Ed25519 private key,
 * and shipped as a base64url-encoded token the user pastes into the first-run
 * wizard or the Settings → License screen.
 *
 * @param businessName     the name the license was issued to (must match the installed business)
 * @param plan             tier: {@code counter}, {@code shop}, or {@code lan}
 * @param issuedAt         ISO‑8601 instant the license was issued
 * @param expiresAt        ISO‑8601 instant the license expires (null = perpetual)
 * @param machineFingerprint optional SHA‑256 hash of MAC + disk UUID; if present
 *                          the runtime verifier checks it against the current machine
 */
public record LicensePayload(
        String businessName,
        String plan,
        Instant issuedAt,
        Instant expiresAt,
        String machineFingerprint
) {
}
