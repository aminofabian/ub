package zelisline.ub.storefront.application;

import java.util.Locale;

import zelisline.ub.credits.domain.KenyanPhoneForms;

/**
 * Placeholder inbox for phone-first shoppers. Never used for mail; the Kenyan
 * mobile is the login identifier.
 */
public final class ShopperPhoneEmails {

    public static final String DOMAIN = "phone.invalid";

    private ShopperPhoneEmails() {
    }

    public static String forLocal07(String local07) {
        String digits = KenyanPhoneForms.toLocal07(local07);
        if (digits == null) {
            digits = local07 == null ? "" : local07.trim();
        }
        return "shopper." + digits.toLowerCase(Locale.ROOT) + "@" + DOMAIN;
    }

    public static boolean isSynthetic(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        return email.trim().toLowerCase(Locale.ROOT).endsWith("@" + DOMAIN);
    }
}
