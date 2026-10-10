package zelisline.ub.platform.api.dto;

/**
 * Body for {@code POST /api/v1/super-admin/platform/integrations/meta-whatsapp/verify-app-secret}.
 *
 * <p>{@code candidateAppSecret} is optional: when supplied it is verified instead of the stored
 * secret, so a super-admin can test the value from Meta's dashboard before saving it.
 */
public record VerifyMetaWhatsAppAppSecretRequest(String candidateAppSecret) {
}
