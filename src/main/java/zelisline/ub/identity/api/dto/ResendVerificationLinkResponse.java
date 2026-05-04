package zelisline.ub.identity.api.dto;

/** Returned by {@code POST /api/v1/auth/resend-verification} when link exposure is enabled and a new token was issued. */
public record ResendVerificationLinkResponse(String verificationUrl) {
}
