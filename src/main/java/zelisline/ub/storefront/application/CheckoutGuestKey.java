package zelisline.ub.storefront.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

import zelisline.ub.credits.domain.CustomerPhoneNormalizer;

/** Stable guest profile key from contact email + phone (per tenant). */
public final class CheckoutGuestKey {

    private CheckoutGuestKey() {
    }

    public static String derive(String email, String areaCode, String phone) {
        String emailNorm = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        String codeNorm = areaCode == null ? "" : areaCode.trim();
        String phoneNorm = CustomerPhoneNormalizer.normalize(phone);
        String material = emailNorm + "|" + codeNorm + "|" + phoneNorm;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
