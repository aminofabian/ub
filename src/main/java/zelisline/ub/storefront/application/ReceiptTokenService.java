package zelisline.ub.storefront.application;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.application.TokenHasher;
import zelisline.ub.storefront.domain.WebOrder;
import zelisline.ub.storefront.repository.WebOrderRepository;

/**
 * One-tap receipt links (Phase 5, §17): a short-lived, single-use token per web
 * order that proves the link's holder owns the order's phone — replacing the
 * weak code + phone-last-4 gate for the WhatsApp/SMS receipt link.
 *
 * <p>The raw token travels only in the link; the order stores its SHA-256 hash,
 * an expiry, and a consumed marker. Verification is constant-time and
 * single-use, and the caller must NOT distinguish "unknown order" from "bad
 * token" to the client — both surface as a generic miss (§12 posture).
 */
@Service
@RequiredArgsConstructor
public class ReceiptTokenService {

    static final Duration TOKEN_TTL = Duration.ofMinutes(15);

    private static final SecureRandom RANDOM = new SecureRandom();
    /** Crockford-ish alphabet without I/O/0/1 so the token survives chat copy. */
    private static final String TOKEN_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int TOKEN_LENGTH = 24;

    private final WebOrderRepository webOrderRepository;

    /**
     * Mints a fresh token for the order (replacing any previous one) and returns
     * the raw value for the receipt link. Persists via the repository — call
     * within the checkout transaction.
     */
    @Transactional
    public String mint(WebOrder order) {
        String token = randomToken();
        order.setReceiptTokenHash(TokenHasher.sha256Hex(token));
        order.setReceiptTokenExpiresAt(Instant.now().plus(TOKEN_TTL));
        order.setReceiptTokenConsumedAt(null);
        webOrderRepository.save(order);
        return token;
    }

    /**
     * True only when the token matches the order's stored hash, has not expired,
     * and has not been used; consuming marks it used. A wrong, expired, or reused
     * token returns false — callers map that to the generic "Order not found".
     */
    @Transactional
    public boolean verifyAndConsume(WebOrder order, String rawToken) {
        String token = rawToken == null ? "" : rawToken.trim();
        if (token.isEmpty() || order.getReceiptTokenHash() == null) {
            return false;
        }
        Instant now = Instant.now();
        if (order.getReceiptTokenConsumedAt() != null) {
            return false;
        }
        if (order.getReceiptTokenExpiresAt() == null
                || order.getReceiptTokenExpiresAt().isBefore(now)) {
            return false;
        }
        if (!constantTimeEquals(order.getReceiptTokenHash(), TokenHasher.sha256Hex(token))) {
            return false;
        }
        order.setReceiptTokenConsumedAt(now);
        order.setUpdatedAt(now);
        webOrderRepository.save(order);
        return true;
    }

    private static String randomToken() {
        StringBuilder sb = new StringBuilder(TOKEN_LENGTH);
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            sb.append(TOKEN_ALPHABET.charAt(RANDOM.nextInt(TOKEN_ALPHABET.length())));
        }
        return sb.toString();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        return MessageDigest.isEqual(
                a.toLowerCase(Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.toLowerCase(Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
