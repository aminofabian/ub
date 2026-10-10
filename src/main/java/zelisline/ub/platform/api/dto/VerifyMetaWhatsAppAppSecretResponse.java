package zelisline.ub.platform.api.dto;

/**
 * Result of verifying a Meta app secret against the platform access token.
 *
 * @param verified                true when Meta accepted the {@code appsecret_proof}
 * @param status                  one of {@code VALID}, {@code INVALID}, {@code UNCONFIGURED}, {@code ERROR}
 * @param usedCandidate           true when the submitted candidate secret was checked (not the stored one)
 * @param configuredSecretPresent true when a stored app secret exists
 * @param detail                  human-readable explanation, including Meta's own error message
 */
public record VerifyMetaWhatsAppAppSecretResponse(
        boolean verified,
        String status,
        boolean usedCandidate,
        boolean configuredSecretPresent,
        String detail
) {
}
