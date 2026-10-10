package zelisline.ub.integrations.whatsapp.application;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
 * Explains why Meta rejects a webhook with a bad {@code X-Hub-Signature-256}.
 *
 * <p>Two facts together identify the cause:
 * <ol>
 *   <li>{@code appsecret_proof} — HMAC-SHA256(access_token, app_secret) — tells us whether the
 *       stored secret belongs to the app that issued the access token. Meta rejects a proof built
 *       with the wrong secret.</li>
 *   <li>{@code GET /{waba-id}/subscribed_apps} lists the app(s) subscribed to the WhatsApp Business
 *       Account. Meta signs each delivery with the secret of the <em>subscribed</em> app, which may
 *       differ from the token's app.</li>
 * </ol>
 * Surface to super-admins from {@code /api/v1/super-admin/platform/integrations}
 * ({@code .../meta-whatsapp/verify-app-secret}).
 */
@Component
public class MetaWhatsAppAppSecretVerifier {

    private static final Logger log = LoggerFactory.getLogger(MetaWhatsAppAppSecretVerifier.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String GRAPH_BASE_URL = "https://graph.facebook.com";
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

    /** An app subscribed to the WABA — the one whose secret signs inbound webhooks. */
    public record SubscribedApp(String id, String name) {
    }

    public record Result(
            Status status,
            boolean usedCandidate,
            boolean configuredSecretPresent,
            String configuredSecretFingerprint,
            String detail,
            String wabaId,
            String wabaName,
            List<SubscribedApp> subscribedApps
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
        String configuredFingerprint = fingerprint(configuredSecret);
        String secret = usedCandidate ? candidateSecret.trim() : configuredSecret;

        if (accessToken == null) {
            return unconfigured(usedCandidate, configuredSecretPresent, configuredFingerprint,
                    "No Meta access token configured. Save the platform access token, then verify.");
        }
        if (phoneNumberId == null) {
            return unconfigured(usedCandidate, configuredSecretPresent, configuredFingerprint,
                    "No Meta phone number ID configured. Save it, then verify.");
        }
        if (secret == null) {
            return unconfigured(usedCandidate, configuredSecretPresent, configuredFingerprint,
                    "No app secret to verify. Paste the secret from Meta, or save one first.");
        }

        String version = firstNonBlank(config.graphVersion(), DEFAULT_GRAPH_VERSION);
        Status status;
        String detail;
        String proof = hmacSha256Hex(secret, accessToken);
        String url = GRAPH_BASE_URL + "/" + version + "/"
                + URLEncoder.encode(phoneNumberId, StandardCharsets.UTF_8)
                + "?fields=id&appsecret_proof=" + proof;
        try {
            HttpResponse<String> response = Unirest.get(url)
                    .header("Authorization", "Bearer " + accessToken)
                    .asString();
            int code = response.getStatus();
            if (code >= 200 && code < 300) {
                status = Status.VALID;
                detail = "Meta accepted this app secret (appsecret_proof valid).";
            } else if (isAppSecretProofError(response.getBody())) {
                status = Status.INVALID;
                detail = "Meta rejected this app secret — it does not match the app that issued the "
                        + "access token. " + metaErrorMessage(response.getBody());
            } else {
                status = Status.ERROR;
                detail = "Meta call failed (HTTP " + code + "). " + metaErrorMessage(response.getBody());
            }
        } catch (Exception ex) {
            log.warn("Meta app-secret verification failed: {}", ex.getMessage());
            return new Result(Status.ERROR, usedCandidate, configuredSecretPresent, configuredFingerprint,
                    "Could not reach Meta: " + ex.getMessage(), null, null, List.of());
        }

        // Best-effort: which app(s) are actually subscribed to the WABA. A subscription under a
        // *different* app is the usual reason a signature fails while the stored secret is fine.
        WabaApps waba = fetchSubscribedApps(accessToken, version, phoneNumberId);
        if (waba.error() != null) {
            detail += " (Could not list subscribed apps: " + waba.error() + ")";
        } else if (waba.subscribedApps().size() > 1) {
            detail += " " + waba.subscribedApps().size()
                    + " apps are subscribed to this WABA — the delivery is signed by whichever app "
                    + "sent it, so every subscribed app's secret must be configured.";
        }

        return new Result(status, usedCandidate, configuredSecretPresent, configuredFingerprint,
                detail, waba.wabaId(), waba.wabaName(), waba.subscribedApps());
    }

    private record WabaApps(String wabaId, String wabaName, List<SubscribedApp> subscribedApps, String error) {
    }

    private WabaApps fetchSubscribedApps(String accessToken, String version, String phoneNumberId) {
        try {
            String nodeUrl = GRAPH_BASE_URL + "/" + version + "/"
                    + URLEncoder.encode(phoneNumberId, StandardCharsets.UTF_8)
                    + "?fields=display_phone_number,verified_name,whatsapp_business_account{id,name}";
            JsonNode node = getJson(nodeUrl, accessToken);
            if (node == null) {
                return new WabaApps(null, null, List.of(), "phone number lookup failed");
            }
            JsonNode waba = node.path("whatsapp_business_account");
            String wabaId = text(waba, "id");
            String wabaName = text(waba, "name");
            if (wabaId == null) {
                return new WabaApps(null, null, List.of(), "no WhatsApp Business Account on this phone number");
            }
            String subsUrl = GRAPH_BASE_URL + "/" + version + "/"
                    + URLEncoder.encode(wabaId, StandardCharsets.UTF_8) + "/subscribed_apps";
            JsonNode subs = getJson(subsUrl, accessToken);
            List<SubscribedApp> apps = new ArrayList<>();
            if (subs != null) {
                for (JsonNode item : subs.path("data")) {
                    JsonNode data = item.has("whatsapp_business_api_data")
                            ? item.get("whatsapp_business_api_data")
                            : item;
                    apps.add(new SubscribedApp(text(data, "id"), text(data, "name")));
                }
            }
            return new WabaApps(wabaId, wabaName, List.copyOf(apps), null);
        } catch (Exception ex) {
            log.warn("Meta subscribed_apps lookup failed: {}", ex.getMessage());
            return new WabaApps(null, null, List.of(), ex.getMessage());
        }
    }

    private static JsonNode getJson(String url, String accessToken) {
        try {
            HttpResponse<String> response = Unirest.get(url)
                    .header("Authorization", "Bearer " + accessToken)
                    .asString();
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                return null;
            }
            return MAPPER.readTree(response.getBody());
        } catch (Exception ex) {
            return null;
        }
    }

    private static Result unconfigured(
            boolean usedCandidate, boolean configuredSecretPresent, String fingerprint, String detail) {
        return new Result(Status.UNCONFIGURED, usedCandidate, configuredSecretPresent, fingerprint,
                detail, null, null, List.of());
    }

    private static boolean isAppSecretProofError(String body) {
        return body != null && body.toLowerCase(Locale.ROOT).contains("appsecret_proof");
    }

    private static String metaErrorMessage(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            String message = text(MAPPER.readTree(body).path("error"), "message");
            if (message != null) {
                return message;
            }
        } catch (Exception ignored) {
            // Fall through to the raw body.
        }
        return body.length() > 300 ? body.substring(0, 300) : body;
    }

    private static String fingerprint(String secret) {
        if (secret == null || secret.isBlank()) {
            return null;
        }
        String trimmed = secret.trim();
        return trimmed.length() <= 4 ? "****" : "…" + trimmed.substring(trimmed.length() - 4);
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
