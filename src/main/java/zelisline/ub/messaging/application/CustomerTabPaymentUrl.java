package zelisline.ub.messaging.application;

import zelisline.ub.credits.domain.KenyanPhoneForms;

/**
 * Builds public customer tab pay links like {@code https://palmart.co.ke/0714282874}.
 */
public final class CustomerTabPaymentUrl {

    private CustomerTabPaymentUrl() {
    }

    /**
     * @param paymentAccountUrlSetting tenant/platform setting — treated as a site origin
     *        (any path is stripped). Falls back to {@code https://palmart.co.ke}.
     * @param phoneRaw customer phone in any common Kenyan form
     */
    public static String build(String paymentAccountUrlSetting, String phoneRaw) {
        String local = KenyanPhoneForms.toLocal07(phoneRaw);
        if (local == null) {
            String origin = originOf(paymentAccountUrlSetting);
            return origin.isEmpty() ? "https://palmart.co.ke/shop/account" : origin + "/shop/account";
        }
        String origin = originOf(paymentAccountUrlSetting);
        if (origin.isEmpty()) {
            origin = "https://palmart.co.ke";
        }
        return origin + "/" + local;
    }

    static String originOf(String urlOrOrigin) {
        if (urlOrOrigin == null || urlOrOrigin.isBlank()) {
            return "";
        }
        String trimmed = urlOrOrigin.trim().replaceAll("/+$", "");
        int scheme = trimmed.indexOf("://");
        if (scheme < 0) {
            // bare host
            return "https://" + trimmed.replaceAll("/.*$", "");
        }
        int pathStart = trimmed.indexOf('/', scheme + 3);
        if (pathStart < 0) {
            return trimmed;
        }
        return trimmed.substring(0, pathStart);
    }
}
