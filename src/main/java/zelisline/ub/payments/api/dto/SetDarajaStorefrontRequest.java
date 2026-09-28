package zelisline.ub.payments.api.dto;

/**
 * Merchant ask to show (or hide) Daraja on the public shop.
 * Turning it on records a request; the shop stays dark until Super Admin approves.
 */
public record SetDarajaStorefrontRequest(boolean enabled) {
}
