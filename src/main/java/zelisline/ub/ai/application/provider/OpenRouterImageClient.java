package zelisline.ub.ai.application.provider;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import lombok.RequiredArgsConstructor;

import zelisline.ub.ai.application.ResolvedSokoMindConfig;

/**
 * OpenRouter Image API ({@code POST /images}). GLM chat uses the same key;
 * logos use whichever image model Super Admin configured (default Gemini Flash Image).
 */
@Component
@RequiredArgsConstructor
public class OpenRouterImageClient {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterImageClient.class);
    private static final int CONNECT_MS = 15_000;
    private static final int SOCKET_MS = 90_000;
    static final String DEFAULT_BASE = "https://openrouter.ai/api/v1";
    static final String HTTP_REFERER = "https://kiosk.ke";
    static final String APP_TITLE = "Kiosk SokoMind";

    private final ObjectMapper objectMapper;

    public OpenAiImageClient.GeneratedImage generate(ResolvedSokoMindConfig config, String model, String prompt) {
        String apiKey = config.openrouterApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Logo generation needs an OpenRouter key. Set it in Super Admin → Platform → SokoMind.");
        }

        String resolvedModel = (model == null || model.isBlank())
                ? "google/gemini-2.5-flash-image"
                : model.trim();
        String url = imagesUrl(config.openrouterBaseUrl());
        Map<String, Object> payload = buildPayload(resolvedModel, prompt);

        final String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "AI request body", ex);
        }

        HttpResponse<String> response;
        try {
            response = Unirest.post(url)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey.strip())
                    .header("HTTP-Referer", HTTP_REFERER)
                    .header("X-Title", APP_TITLE)
                    .connectTimeout(CONNECT_MS)
                    .socketTimeout(SOCKET_MS)
                    .body(json)
                    .asString();
        } catch (Exception ex) {
            log.warn("OpenRouter images request to {} failed", url, ex);
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "OpenRouter image service unreachable. Try again in a moment.");
        }

        if (response.getStatus() < 200 || response.getStatus() >= 300) {
            String body = truncate(response.getBody());
            log.warn("OpenRouter images HTTP {} from {}: {}", response.getStatus(), url, body);
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, userFacingImageError(response.getStatus(), body));
        }

        Parsed parsed = parse(response.getBody(), resolvedModel);
        String b64 = parsed.base64();
        if (b64 == null || b64.isBlank()) {
            b64 = downloadAsBase64(parsed.url());
        }
        return new OpenAiImageClient.GeneratedImage(
                b64, parsed.mimeType(), resolvedModel, parsed.promptTokens(), parsed.completionTokens());
    }

    static Map<String, Object> buildPayload(String model, String prompt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("prompt", prompt);
        payload.put("n", 1);
        payload.put("aspect_ratio", "1:1");
        payload.put("output_format", "png");
        payload.put("background", "transparent");
        return payload;
    }

    record Parsed(String base64, String url, String mimeType, Integer promptTokens, Integer completionTokens) {}

    static Parsed parse(String rawBody, String model) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(rawBody);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenRouter returned no image.");
            }
            JsonNode first = data.get(0);
            String b64 = stripDataUrl(textOrNull(first, "b64_json"));
            String imageUrl = textOrNull(first, "url");
            if ((b64 == null || b64.isBlank()) && (imageUrl == null || imageUrl.isBlank())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, "OpenRouter returned no image bytes.");
            }
            String mime = mimeFrom(first);
            Integer promptTokens = null;
            Integer completionTokens = null;
            JsonNode usage = root.path("usage");
            if (usage.isObject()) {
                if (usage.has("prompt_tokens")) {
                    promptTokens = usage.get("prompt_tokens").asInt();
                }
                if (usage.has("completion_tokens")) {
                    completionTokens = usage.get("completion_tokens").asInt();
                }
            }
            return new Parsed(b64, imageUrl, mime, promptTokens, completionTokens);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenRouter image response unreadable");
        }
    }

    static String userFacingImageError(int status, String body) {
        String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
        if (status == 400 && (lower.contains("content") || lower.contains("safety") || lower.contains("policy"))) {
            return "That description cannot be used. Try a simpler shop mark.";
        }
        if (status == 401 || status == 403) {
            return "The OpenRouter key looks wrong. Check it in Super Admin → Platform → SokoMind.";
        }
        if (status == 402 || lower.contains("insufficient credits") || lower.contains("credit")) {
            return "OpenRouter is out of credits. Add credits, then try again.";
        }
        if (status == 429) {
            return "Too many logo requests right now. Wait a moment and try again.";
        }
        if (status == 400 && (lower.contains("model") || lower.contains("unknown") || lower.contains("not found"))) {
            return "That OpenRouter image model is not available. Pick another slug in Super Admin → Platform → SokoMind.";
        }
        return "Could not generate a logo right now. Try again in a moment.";
    }

    static String imagesUrl(String baseUrl) {
        String base = ensureScheme(firstNonBlank(baseUrl, DEFAULT_BASE));
        if (base.endsWith("/chat/completions")) {
            base = base.substring(0, base.length() - "/chat/completions".length());
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/images")) {
            return base;
        }
        return base + "/images";
    }

    private static String downloadAsBase64(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenRouter returned no image bytes.");
        }
        String trimmed = imageUrl.strip();
        if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenRouter returned an unusable image URL.");
        }
        try {
            HttpResponse<byte[]> response = Unirest.get(trimmed)
                    .connectTimeout(CONNECT_MS)
                    .socketTimeout(SOCKET_MS)
                    .asBytes();
            if (response.getStatus() < 200 || response.getStatus() >= 300 || response.getBody() == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, "Could not download the generated logo. Try again.");
            }
            return Base64.getEncoder().encodeToString(response.getBody());
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("OpenRouter image URL download failed: {}", trimmed, ex);
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Could not download the generated logo. Try again.");
        }
    }

    private static String mimeFrom(JsonNode first) {
        String media = textOrNull(first, "media_type");
        if (media != null && media.toLowerCase(Locale.ROOT).startsWith("image/")) {
            return media;
        }
        String format = textOrNull(first, "output_format");
        if (format != null) {
            return switch (format.toLowerCase(Locale.ROOT)) {
                case "jpeg", "jpg" -> "image/jpeg";
                case "webp" -> "image/webp";
                default -> "image/png";
            };
        }
        return "image/png";
    }

    private static String stripDataUrl(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String trimmed = value.strip();
        int comma = trimmed.indexOf(";base64,");
        if (trimmed.startsWith("data:") && comma >= 0) {
            return trimmed.substring(comma + ";base64,".length());
        }
        return trimmed;
    }

    private static String ensureScheme(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        return "https://" + trimmed;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        return b;
    }

    private static String textOrNull(JsonNode node, String key) {
        JsonNode v = node.get(key);
        if (v == null || !v.isTextual()) {
            return null;
        }
        String s = v.asText();
        return s == null || s.isBlank() ? null : s;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 240 ? value : value.substring(0, 240) + "…";
    }
}
