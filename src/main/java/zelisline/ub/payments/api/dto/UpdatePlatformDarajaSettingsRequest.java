package zelisline.ub.payments.api.dto;

/**
 * Super-admin update body for platform Daraja. Null fields are left unchanged.
 * Secret fields are write-only; omit to keep existing encrypted values.
 *
 * <p>{@code initiatorName} / {@code initiatorPassword} enable Daraja B2B disburse
 * (custody settle to a tenant paybill/till). {@code clearDisburseCredentials} removes
 * them.
 */
public record UpdatePlatformDarajaSettingsRequest(
        Boolean enabled,
        String environment,
        String shortcodeType,
        String shortcode,
        String consumerKey,
        String consumerSecret,
        String passkey,
        String initiatorName,
        String initiatorPassword,
        String b2bShortcode,
        String b2bRequester,
        Boolean clearDisburseCredentials,
        Boolean clearCredentials
) {
}
