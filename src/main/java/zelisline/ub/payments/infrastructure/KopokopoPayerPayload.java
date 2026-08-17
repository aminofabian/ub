package zelisline.ub.payments.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;

import zelisline.ub.credits.domain.MaskedMsisdn;
import zelisline.ub.credits.domain.PayerNameNormalizer;

/**
 * Pull first name, last name, and phone from stored KopoKopo webhook JSON
 * (root JSON:API, K2Connect topic, or incoming-payment resource).
 */
public final class KopokopoPayerPayload {

    public record Extracted(
            String firstName,
            String lastName,
            String phoneRaw,
            boolean masked
    ) {
        public boolean hasName() {
            return (firstName != null && !firstName.isBlank())
                    || (lastName != null && !lastName.isBlank());
        }
    }

    private KopokopoPayerPayload() {
    }

    public static Extracted extract(JsonNode root) {
        if (root == null || root.isNull()) {
            return new Extracted(null, null, null, false);
        }
        JsonNode[] nodes = {
                root.path("attributes"),
                root.path("event").path("resource"),
                root.path("data").path("attributes"),
                root.path("data").path("attributes").path("event").path("resource"),
                root
        };
        String first = null;
        String last = null;
        String phone = null;
        for (JsonNode node : nodes) {
            if (node == null || node.isMissingNode() || node.isNull() || !node.isObject()) {
                continue;
            }
            if (first == null) {
                first = firstNonBlank(text(node, "first_name"), text(node, "sender_first_name"));
            }
            if (last == null) {
                last = firstNonBlank(text(node, "last_name"), text(node, "sender_last_name"));
            }
            if (phone == null) {
                phone = firstNonBlank(text(node, "phone_number"), text(node, "sender_phone_number"));
            }
        }
        boolean masked = MaskedMsisdn.isMasked(phone);
        return new Extracted(
                blankToNull(PayerNameNormalizer.normalize(first)),
                blankToNull(PayerNameNormalizer.normalize(last)),
                blankToNull(phone),
                masked);
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        String v = node.get(field).asText();
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }

    private static String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }
}
