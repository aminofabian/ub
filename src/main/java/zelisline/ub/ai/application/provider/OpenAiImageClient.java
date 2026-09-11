package zelisline.ub.ai.application.provider;

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
 * OpenAI Images API ({@code /v1/images/generations}). Chat providers such as
 * DeepSeek and Anthropic cannot draw logos, so this always uses the OpenAI key.
 */
@Component
@RequiredArgsConstructor
public class OpenAiImageClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiImageClient.class);
    private static final int CONNECT_MS = 15_000;
    private static final int SOCKET_MS = 90_000;

    private final ObjectMapper objectMapper;

    public record GeneratedImage(
            String base64,
            String mimeType,
            String model,
            Integer promptTokens,
            Integer completionTokens
    ) {}

    public GeneratedImage generateOpaque(ResolvedSokoMindConfig config, String model, String prompt) {
        return generateWithBackground(config, model, prompt, false);
    }

    public GeneratedImage generate(ResolvedSokoMindConfig config, String model, String prompt) {
        return generateWithBackground(config, model, prompt, true);
    }

    private GeneratedImage generateWithBackground(
            ResolvedSokoMindConfig config,
            String model,
            String prompt,
            boolean transparent
    ) {
        String apiKey = config.openaiApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Logo generation needs an OpenAI key. Set it in Super Admin → Platform → SokoMind.");
        }

        String resolvedModel = (model == null || model.isBlank()) ? "gpt-image-1" : model.trim();
        String url = imagesUrl(config.openaiBaseUrl());
        Map<String, Object> payload = buildPayload(resolvedModel, prompt, transparent);

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
                    .connectTimeout(CONNECT_MS)
                    .socketTimeout(SOCKET_MS)
                    .body(json)
                    .asString();
        } catch (Exception ex) {
            log.warn("OpenAI images request to {} failed", url, ex);
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "OpenAI image service unreachable. Try again in a moment.");
        }

        if (response.getStatus() < 200 || response.getStatus() >= 300) {
            String body = truncate(response.getBody());
            log.warn("OpenAI images HTTP {} from {}: {}", response.getStatus(), url, body);
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, userFacingImageError(response.getStatus(), body));
        }

        return parse(response.getBody(), resolvedModel);
    }

    /**
     * Image-to-image: rebuild {@code imageBytes} as a home-screen icon.
     * Uses {@code /v1/images/edits} so the shop's logo stays the same mark.
     */
    public GeneratedImage edit(
            ResolvedSokoMindConfig config,
            String model,
            String prompt,
            byte[] imageBytes,
            String filename
    ) {
        return edit(config, model, prompt, imageBytes, filename, false);
    }

    /**
     * Image-to-image edit. Pass {@code transparent=true} to recolor a logo
     * (light → dark) without an opaque plate.
     */
    public GeneratedImage edit(
            ResolvedSokoMindConfig config,
            String model,
            String prompt,
            byte[] imageBytes,
            String filename,
            boolean transparent
    ) {
        String apiKey = config.openaiApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Logo generation needs an OpenAI key. Set it in Super Admin → Platform → SokoMind.");
        }
        if (imageBytes == null || imageBytes.length < 32) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The shop logo could not be read.");
        }

        String resolvedModel = (model == null || model.isBlank()) ? "gpt-image-1" : model.trim();
        String url = editsUrl(config.openaiBaseUrl());
        String safeName = filename == null || filename.isBlank() ? "logo.png" : filename.trim();

        HttpResponse<String> response;
        try {
            var request = Unirest.post(url)
                    .header("Authorization", "Bearer " + apiKey.strip())
                    .connectTimeout(CONNECT_MS)
                    .socketTimeout(SOCKET_MS)
                    .field("model", resolvedModel)
                    .field("prompt", prompt)
                    .field("n", "1")
                    .field("size", "1024x1024");
            if (!isDallE(resolvedModel)) {
                request = request
                        .field("background", transparent ? "transparent" : "opaque")
                        .field("output_format", "png");
            }
            response = request
                    .field(
                            "image",
                            new java.io.ByteArrayInputStream(imageBytes),
                            kong.unirest.ContentType.APPLICATION_OCTET_STREAM,
                            safeName)
                    .asString();
        } catch (Exception ex) {
            log.warn("OpenAI image edit request to {} failed", url, ex);
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "OpenAI image service unreachable. Try again in a moment.");
        }

        if (response.getStatus() < 200 || response.getStatus() >= 300) {
            String body = truncate(response.getBody());
            log.warn("OpenAI image edit HTTP {} from {}: {}", response.getStatus(), url, body);
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, userFacingImageError(response.getStatus(), body));
        }

        return parse(response.getBody(), resolvedModel);
    }

    static Map<String, Object> buildPayload(String model, String prompt) {
        return buildPayload(model, prompt, true);
    }

    static Map<String, Object> buildPayload(String model, String prompt, boolean transparent) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("prompt", prompt);
        payload.put("n", 1);
        payload.put("size", "1024x1024");
        if (isDallE(model)) {
            payload.put("quality", "standard");
            payload.put("response_format", "b64_json");
        } else {
            payload.put("quality", "medium");
            payload.put("background", transparent ? "transparent" : "opaque");
            payload.put("output_format", "png");
        }
        return payload;
    }

    static GeneratedImage parse(String rawBody, String model) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(rawBody);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenAI returned no image.");
            }
            JsonNode first = data.get(0);
            String b64 = textOrNull(first, "b64_json");
            if (b64 == null || b64.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, "OpenAI returned an image URL instead of bytes.");
            }
            String mime = isDallE(model) ? "image/png" : mimeFromOutput(first);
            Integer promptTokens = null;
            Integer completionTokens = null;
            JsonNode usage = root.path("usage");
            if (usage.isObject()) {
                if (usage.has("input_tokens")) {
                    promptTokens = usage.get("input_tokens").asInt();
                } else if (usage.has("prompt_tokens")) {
                    promptTokens = usage.get("prompt_tokens").asInt();
                }
                if (usage.has("output_tokens")) {
                    completionTokens = usage.get("output_tokens").asInt();
                } else if (usage.has("completion_tokens")) {
                    completionTokens = usage.get("completion_tokens").asInt();
                }
            }
            return new GeneratedImage(b64.strip(), mime, model, promptTokens, completionTokens);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenAI image response unreadable");
        }
    }

    static String userFacingImageError(int status, String body) {
        String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
        if (status == 400 && (lower.contains("content") || lower.contains("safety") || lower.contains("policy"))) {
            return "That description cannot be used. Try a simpler shop mark.";
        }
        if (status == 401 || status == 403) {
            return "The OpenAI key looks wrong. Check it in Super Admin → Platform → SokoMind.";
        }
        if (status == 429) {
            return "Too many logo requests right now. Wait a moment and try again.";
        }
        if (status == 400 && (lower.contains("model") || lower.contains("unknown"))) {
            return "The logo model is not available on this OpenAI account. Set SOKOMIND_OPENAI_IMAGE_MODEL to dall-e-3.";
        }
        return "Could not generate a logo right now. Try again in a moment.";
    }

    private static String mimeFromOutput(JsonNode first) {
        String format = textOrNull(first, "output_format");
        if (format == null) {
            return "image/png";
        }
        return switch (format.toLowerCase(Locale.ROOT)) {
            case "jpeg", "jpg" -> "image/jpeg";
            case "webp" -> "image/webp";
            default -> "image/png";
        };
    }

    private static boolean isDallE(String model) {
        return model != null && model.toLowerCase(Locale.ROOT).startsWith("dall-e");
    }

    private static String imagesUrl(String baseUrl) {
        String base = ensureScheme(firstNonBlank(baseUrl, "https://api.openai.com/v1"));
        if (base.endsWith("/")) {
            return base + "images/generations";
        }
        return base + "/images/generations";
    }

    static String editsUrl(String baseUrl) {
        String generations = imagesUrl(baseUrl);
        if (generations.endsWith("/images/generations")) {
            return generations.substring(0, generations.length() - "/images/generations".length())
                    + "/images/edits";
        }
        return generations.replace("/images/generations", "/images/edits");
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
