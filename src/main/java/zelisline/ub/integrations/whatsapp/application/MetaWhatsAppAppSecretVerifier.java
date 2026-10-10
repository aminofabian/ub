package zelisline.ub.integrations.whatsapp.application;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import zelisline.ub.platform.application.ResolvedMetaWhatsAppConfig;

/**
 * Checks whether a Meta app secret really belongs to the app that owns the configured access
 * token, by calling the Graph API with an {@code appsecret_proof}.
 *
 * <p>Meta computes {@code appsecret_proof = HMAC-SHA256(access_token, app_secret)} and rejects a
 * proof built with the wrong secret. That makes it the one check that answers the question the
 * webhook endpoint cannot: "is the app secret I stored the same one Meta is signing
 * {@code X-Hub-Signature-256} with?" — without needing a captured signed payload.
 *
 * <p>Surfaced to super-admins from {@code /api/v1/super-admin/platform/integrations}
 * ({@code .../meta-whatsapp/verify-app-secret}) so a mismatch is caught before Meta's retries fill
 * the request log, instead of only after a webhook is silently rejected with 403.
 */
@Component
public class MetaWhatsAppAppSecretVerifier {

    private static final Logger log = LoggerFactory.getLogger(MetaWhatsAppAppSecretVerifier.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_GRAPH_BASE_URL = "https://graph.facebook.com";
    private static final String DEFAULT_GRAPH_VERSION = "v25.0";

    public enum Status {
        /** Meta accepted the proof — the secret matches the token's app. */
        VALID,
        /** Meta explicitly rejected the proof — the secret does not match the token's app. */
        INVALID,
        /** Not enough configuration to run the check (token / phone id / secret missing). */
        UNCONFIGURED,
        /** The check could not complete (network, or Meta rejected the call for another reason). */
        ERROR
    }

    public record Result(
            Status status,
            boolean usedCandidate,
            boolean configuredSecretPresent,
            String detail
    ) {
        public boolean verified() {
            return status == Status.VALID;
        }
    }

    /**
     * @param config          resolved platform Meta settings (access token, phone number id, secret)
     * @param candidateSecret when present, verify this secret instead of the stored one — lets a
     *                        super-admin test the value from Meta's dashboard before saving it
     */
    public Result verify(ResolvedMetaWhatsAppConfig config, String candidateSecret) {
        String accessToken = trimToNull(config == null ? null : config.accessToken());
        String phoneNumberId = trimToNull(config == null ? null : config.phoneNumberId());
        String configuredSecret = trimToNull(config == null ? null : config.appSecret());

        boolean usedCandidate = candidateSecret != null && !candidateSecret.isBlank();
        boolean configuredSecretPresent = configuredSecret != null;
        String secret = usedCandidate ? candidateSecret.trim() : configuredSecret;

        if (accessToken == null) {
            return new Result(Status.UNCONFIGURED, usedCandidate, configuredSecretPresent,
                    "No Meta access token configured. Save the platform access token, then verify.");
        }
        if (phoneNumberId == null) {
            return new Result(Status.UNCONFIGURED, usedCandidate, configuredSecretPresent,
                    "No Meta phone number ID configured. Save it, then verify.");
        }
        if (secret == null) {
            return new Result(Status.UNCONFIGURED, usedCandidate, configuredSecretPresent,
                    "No app secret to verify. Paste the secret from Meta, or save one first.");
        }

        String version = firstNonBlank(config.graphVersion(), DEFAULT_GRAPH_VERSION);
        String proof = hmacSha256Hex(secret, accessToken);
        String url = DEFAULT_GRAPH_BASE_URL + "/" + version + "/"
                + URLEncoder.encode(phoneNumberId, StandardCharsets.UTF_8)
                + "?fields=id&appsecret_proof=" + proof;

        try {
            HttpResponse<String> response = Unirest.get(url)
                    .header("Authorization", "Bearer " + accessToken)
                    .asString();
            int status = response.getStatus();
            if (status >= 200 && status < 300) {
                return new Result(Status.VALID, usedCandidate, configuredSecretPresent,
                        "Meta accepted this app secret (appsecret_proof valid).");
            }
            String metaError = metaErrorMessage(response.getBody());
            if (isAppSecretProofError(response.getBody())) {
                return new Result(Status.INVALID, usedCandidate, configuredSecretPresent,
                        "Meta rejected this app secret — it does not match the platform Meta app. "
                                + metaError);
            }
            return new Result(Status.ERROR, usedCandidate, configuredSecretPresent,
                    "Meta call failed (HTTP " + status + "). " + metaError);
        } catch (Exception ex) {
            log.warn("Meta app-secret verification failed: {}", ex.getMessage());
            return new Result(Status.ERROR, usedCandidate, configuredSecretPresent,
                    "Could not reach Meta: " + ex.getMessage());
        }
    }

    private static boolean isAppSecretProofError(String body) {
        return body != null && body.toLowerCase(Locale.ROOT).contains("appsecret_proof");
    }

    private static String metaErrorMessage(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode error = MAPPER.readTree(body).path("error");
            String message = text(error, "message");
            if (message != null) {
                return message;
            }
        } catch (Exception ignored) {
            // Fall through to the raw body.
        }
        return body.length() > 300 ? body.substring(0, 300) : body;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        String value = node.get(field).asText("").trim();
        return value.isEmpty() ? null : value;
    }

    private static String hmacSha256Hex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA256 failed", ex);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
