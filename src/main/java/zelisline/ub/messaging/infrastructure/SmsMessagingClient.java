package zelisline.ub.messaging.infrastructure;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import zelisline.ub.messaging.application.SmsCreditGuard;
import zelisline.ub.messaging.application.SmsCreditsDepletedException;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.messaging.domain.SmsSendReason;

/**
 * SMS fallback for credit tab reminders (Africa's Talking, Sozuri, TextSMS.co.ke, or
 * Ping Africa). Metered at this single choke point: every real tenant-scoped send
 * consumes one credit via {@link SmsCreditGuard} (SMS_CREDITS_SCOPE.md §8). Stub sends
 * and platform-scoped sends (no business id) are free.
 */
@Component
public class SmsMessagingClient {

    private static final Logger log = LoggerFactory.getLogger(SmsMessagingClient.class);
    private static final String DEFAULT_SOZURI_URL = "https://sozuri.net/api/v1/messaging";
    private static final String DEFAULT_TEXTSMS_URL = "https://sms.textsms.co.ke/api/services/sendsms/";
    /** API root; {@code /sms/send} is appended. */
    private static final String DEFAULT_PINGAFRICA_URL = "https://bulk.ping.africa/api";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SmsCreditGuard smsCreditGuard;

    public SmsMessagingClient(SmsCreditGuard smsCreditGuard) {
        this.smsCreditGuard = smsCreditGuard;
    }

    /**
     * @param toE164 recipient with leading + (e.g. +254712345678)
     */
    public SendResult sendText(TenantMessagingConfig cfg, String toE164, String body) {
        return sendText(cfg, toE164, body, null, null);
    }

    /**
     * Metered send. {@code reason}/{@code referenceId} classify the ledger entry;
     * when null the config's default reason (or {@code general}) is used.
     */
    public SendResult sendText(
            TenantMessagingConfig cfg,
            String toE164,
            String body,
            SmsSendReason reason,
            String referenceId
    ) {
        if (!cfg.smsConfigured()) {
            log.info("SMS stub (provider={} effective={}): to={} message={}",
                    cfg.smsProvider(),
                    cfg.effectiveSmsProvider(),
                    mask(toE164),
                    truncate(body));
            return SendResult.stubLogged();
        }
        String businessId = cfg.businessId();
        if (businessId != null && !businessId.isBlank()) {
            SmsSendReason effectiveReason = reason != null ? reason : cfg.smsReason();
            // Deduct-on-success: block at zero before the provider call, then consume
            // one credit only after the provider accepts (scope §8, §19 #2).
            smsCreditGuard.checkBeforeSend(businessId, effectiveReason, referenceId);
        }
        SendResult result;
        String provider = cfg.effectiveSmsProvider();
        if ("sozuri".equalsIgnoreCase(provider)) {
            result = sendSozuri(toE164, body, cfg);
        } else if ("textsms".equalsIgnoreCase(provider)) {
            result = sendTextSms(toE164, body, cfg);
        } else if ("ping_africa".equalsIgnoreCase(provider)) {
            result = sendPingAfrica(toE164, body, cfg);
        } else {
            result = sendAfricasTalking(toE164, body, cfg);
        }
        if (result.sent() && businessId != null && !businessId.isBlank()) {
            smsCreditGuard.debitOnSuccess(businessId,
                    reason != null ? reason : cfg.smsReason(), referenceId);
        }
        return result;
    }

    private SendResult sendAfricasTalking(String toE164, String body, TenantMessagingConfig cfg) {
        try {
            HttpResponse<String> response = Unirest.post("https://api.africastalking.com/version1/messaging")
                    .header("apiKey", cfg.smsApiKey())
                    .header("Accept", "application/json")
                    .field("username", cfg.smsUsername())
                    .field("to", toE164)
                    .field("message", body)
                    .asString();
            if (response.getStatus() >= 200 && response.getStatus() < 300) {
                return SendResult.sent("africas_talking");
            }
            log.warn("Africa's Talking SMS HTTP {} body={}", response.getStatus(), truncate(response.getBody()));
            return SendResult.failed("http_" + response.getStatus());
        } catch (Exception ex) {
            log.warn("Africa's Talking SMS failed: {}", ex.getMessage());
            return SendResult.failed("error");
        }
    }

    private SendResult sendSozuri(String toE164, String body, TenantMessagingConfig cfg) {
        String url = blankToDefault(cfg.smsSozuriApiUrl(), DEFAULT_SOZURI_URL);
        String from = blankToDefault(cfg.smsSozuriFrom(), "Sozuri");
        String type = blankToDefault(cfg.smsSozuriType(), "transactional");
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("project", cfg.smsSozuriProject());
            payload.put("from", from);
            payload.put("to", toE164);
            payload.put("message", body);
            payload.put("channel", "sms");
            payload.put("type", type);
            String json = objectMapper.writeValueAsString(payload);
            HttpResponse<String> response = Unirest.post(url)
                    .header("Authorization", "Bearer " + cfg.smsSozuriApiKey().strip())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .body(json)
                    .asString();
            if (response.getStatus() >= 200 && response.getStatus() < 300) {
                return SendResult.sent("sozuri");
            }
            log.warn("Sozuri SMS HTTP {} body={}", response.getStatus(), truncate(response.getBody()));
            return SendResult.failed("http_" + response.getStatus());
        } catch (Exception ex) {
            log.warn("Sozuri SMS failed: {}", ex.getMessage());
            return SendResult.failed("error");
        }
    }

    private SendResult sendTextSms(String toE164, String body, TenantMessagingConfig cfg) {
        String url = blankToDefault(cfg.smsTextsmsApiUrl(), DEFAULT_TEXTSMS_URL);
        String mobile = digitsOnly(toE164);
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("apikey", cfg.smsTextsmsApiKey().strip());
            payload.put("partnerID", cfg.smsTextsmsPartnerId().strip());
            payload.put("mobile", mobile);
            payload.put("message", body);
            payload.put("shortcode", cfg.smsTextsmsShortcode().strip());
            payload.put("pass_type", "plain");
            String json = objectMapper.writeValueAsString(payload);
            HttpResponse<String> response = Unirest.post(url)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .body(json)
                    .asString();
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                log.warn("TextSMS HTTP {} body={}", response.getStatus(), truncate(response.getBody()));
                return SendResult.failed("http_" + response.getStatus());
            }
            String detail = parseTextSmsResponse(response.getBody());
            if (detail == null) {
                return SendResult.sent("textsms");
            }
            log.warn("TextSMS rejected for {}: {}", mask(toE164), detail);
            return SendResult.failed(detail);
        } catch (Exception ex) {
            log.warn("TextSMS failed: {}", ex.getMessage());
            return SendResult.failed("error");
        }
    }

    /**
     * Ping Africa Bulk SMS — {@code POST {baseUrl}/sms/send} with a Bearer token.
     * Accepts E.164 with the leading {@code +} (their documented form). The optional
     * {@code sender_id} is only sent when configured, so the account default applies
     * otherwise.
     *
     * @see <a href="https://bulk.ping.africa/docs/api">Ping Africa API reference</a>
     */
    private SendResult sendPingAfrica(String toE164, String body, TenantMessagingConfig cfg) {
        String url = stripTrailingSlashes(
                blankToDefault(cfg.smsPingAfricaBaseUrl(), DEFAULT_PINGAFRICA_URL)) + "/sms/send";
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("recipient", toE164);
            payload.put("message", body);
            String senderId = cfg.smsPingAfricaSenderId();
            if (senderId != null && !senderId.isBlank()) {
                payload.put("sender_id", senderId.trim());
            }
            String json = objectMapper.writeValueAsString(payload);
            HttpResponse<String> response = Unirest.post(url)
                    .header("Authorization", "Bearer " + stripBearerPrefix(cfg.smsPingAfricaApiKey()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .body(json)
                    .asString();
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                String reason = parsePingAfricaError(response.getBody());
                log.warn("Ping Africa SMS HTTP {} body={}", response.getStatus(), truncate(response.getBody()));
                return SendResult.failed(
                        reason == null ? "http_" + response.getStatus()
                                : "http_" + response.getStatus() + ":" + reason);
            }
            String detail = parsePingAfricaResponse(response.getBody());
            if (detail == null) {
                return SendResult.sent("ping_africa");
            }
            log.warn("Ping Africa rejected for {}: {}", mask(toE164), detail);
            return SendResult.failed(detail);
        } catch (Exception ex) {
            log.warn("Ping Africa SMS failed: {}", ex.getMessage());
            return SendResult.failed("error");
        }
    }

    /**
     * @return null when accepted; otherwise a short failure detail. Ping answers
     *     {@code 201 { success: true, message: { id, status } }} on success.
     */
    private String parsePingAfricaResponse(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            JsonNode success = root.get("success");
            if (success != null && success.isBoolean() && !success.asBoolean()) {
                String detail = parsePingAfricaError(rawBody);
                return detail == null ? "rejected" : detail;
            }
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    /** Best-effort provider explanation from an error body ({@code error} / {@code message} / {@code messages}). */
    private String parsePingAfricaError(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            JsonNode error = root.get("error");
            if (error != null && !error.isNull()) {
                String text = error.isTextual() ? error.asText().trim() : error.toString();
                if (!text.isBlank()) {
                    return truncate(text);
                }
            }
            JsonNode message = root.get("message");
            if (message != null && message.isTextual()) {
                return truncate(message.asText(""));
            }
            JsonNode messages = root.get("messages");
            if (messages != null && messages.isArray() && !messages.isEmpty()) {
                JsonNode first = messages.get(0);
                String text = first.isTextual() ? first.asText("") : first.toString();
                return truncate(text.isBlank() ? messages.toString() : text);
            }
            if (message != null && message.isObject()) {
                String status = message.path("status").asText("");
                return status.isBlank() ? null : truncate(status);
            }
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    private static String stripBearerPrefix(String token) {
        if (token == null) {
            return "";
        }
        return token.trim().replaceFirst("(?i)^Bearer\\s+", "").trim();
    }

    private static String stripTrailingSlashes(String url) {
        if (url == null) {
            return "";
        }
        return url.trim().replaceAll("/+$", "");
    }

    /**
     * @return null when accepted; otherwise a short failure detail
     */
    private String parseTextSmsResponse(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            JsonNode responses = root.get("responses");
            if (responses == null || !responses.isArray() || responses.isEmpty()) {
                return null;
            }
            JsonNode first = responses.get(0);
            int code = first.path("respose-code").asInt(
                    first.path("response-code").asInt(0));
            if (code == 200 || code == 0) {
                return null;
            }
            String description = first.path("response-description").asText("").trim();
            if (description.isEmpty()) {
                return "code_" + code;
            }
            return "code_" + code + ":" + description;
        } catch (Exception ex) {
            return null;
        }
    }

    private static String digitsOnly(String phone) {
        if (phone == null) {
            return "";
        }
        return phone.replaceAll("\\D+", "");
    }

    private static String blankToDefault(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static String mask(String phone) {
        if (phone == null || phone.length() <= 6) {
            return "***";
        }
        return phone.substring(0, 4) + "…";
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    public record SendResult(boolean sent, boolean stub, boolean blocked, String channel, String detail) {
        public static SendResult sent(String channel) {
            return new SendResult(true, false, false, channel, "sent");
        }

        public static SendResult stubLogged() {
            return new SendResult(false, true, false, "sms_stub", "logged");
        }

        /** SMS credits depleted — provider was never called. */
        public static SendResult blocked(String detail) {
            return new SendResult(false, false, true, "sms_blocked", detail);
        }

        public static SendResult failed(String detail) {
            return new SendResult(false, false, false, "sms", detail);
        }
    }
}
