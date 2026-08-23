package zelisline.ub.tenancy.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Merchant "Orders on WhatsApp" storefront settings (all themes).
 *
 * <p>Stored under {@code storefront.whatsappCheckout} in the settings document.
 * {@code number} falls back to {@code landingContent.whatsapp} at read time so
 * merchants who configured a Milk Run number are switched on without touching
 * settings (see scope D7).
 */
public record WhatsAppCheckoutSettings(
        @Size(max = 40) String number,
        @Size(max = 16) String mode,
        @Size(max = 120) String greeting,
        @Min(15) @Max(10080) Integer expiryMins
) {

    public static final String MODE_OFF = "off";
    public static final String MODE_FALLBACK = "fallback";
    public static final String MODE_ALWAYS = "always";
    public static final int DEFAULT_EXPIRY_MINS = 180;

    /** V1 modes: off / fallback / always. */
    public static boolean isValidMode(String mode) {
        return MODE_OFF.equals(mode)
                || MODE_FALLBACK.equals(mode)
                || MODE_ALWAYS.equals(mode);
    }
}
