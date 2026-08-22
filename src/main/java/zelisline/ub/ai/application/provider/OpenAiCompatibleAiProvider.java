package zelisline.ub.ai.application.provider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import zelisline.ub.platform.domain.PlatformSokoMindSettings;

/**
 * OpenAI-compatible chat completions (OpenAI, DeepSeek direct, RapidAPI DeepSeek).
 */
@Component
@RequiredArgsConstructor
public class OpenAiCompatibleAiProvider implements AiProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleAiProvider.class);
    private static final Set<String> IDS = Set.of(
            PlatformSokoMindSettings.PROVIDER_OPENAI,
            PlatformSokoMindSettings.PROVIDER_DEEPSEEK,
            PlatformSokoMindSettings.PROVIDER_RAPIDAPI_DEEPSEEK);

    private final ObjectMapper objectMapper;

    @Override
    public String id() {
        return "openai_compatible";
    }

    @Override
    public boolean supports(String primaryProvider) {
        return primaryProvider != null && IDS.contains(primaryProvider.toLowerCase());
    }

    @Override
    public AiChatCompletionResult complete(ResolvedSokoMindConfig config, AiChatCompletionRequest request) {
        String provider = config.primaryProvider() == null
                ? PlatformSokoMindSettings.PROVIDER_OPENAI
                : config.primaryProvider().toLowerCase();

        String apiKey;
        String url;
        String rapidHost = null;
        String model = request.model();

        switch (provider) {
            case PlatformSokoMindSettings.PROVIDER_DEEPSEEK -> {
                // Direct DeepSeek API — Bearer auth against api.deepseek.com.
                apiKey = config.deepseekApiKey();
                String base = firstNonBlank(
                        config.deepseekBaseUrl(),
                        "https://api.deepseek.com/chat/completions");
                url = base.endsWith("/chat/completions")
                        ? base
                        : base.endsWith("/")
                                ? base + "chat/completions"
                                : base + "/chat/completions";
                if (model == null || model.isBlank()) {
                    model = config.deepseekModel();
                }
            }
            case PlatformSokoMindSettings.PROVIDER_RAPIDAPI_DEEPSEEK -> {
                // RapidAPI proxy — x-rapidapi-key + x-rapidapi-host, its own key.
                apiKey = config.rapidapiDeepseekApiKey();
                String host = firstNonBlank(
                        config.deepseekHost(),
                        "deepseek-v31.p.rapidapi.com");
                rapidHost = host;
                url = "https://" + host + "/chat/completions";
                if (model == null || model.isBlank()) {
                    model = config.deepseekModel();
                }
            }
            default -> {
                apiKey = config.openaiApiKey();
                String base = firstNonBlank(config.openaiBaseUrl(), "https://api.openai.com/v1");
                url = base.endsWith("/")
                        ? base + "chat/completions"
                        : base + "/chat/completions";
                if (model == null || model.isBlank()) {
                    model = config.openaiMiniModel();
                }
            }
        }

        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "SokoMind provider '" + provider + "' has no API key configured. "
                            + "Set it in Super Admin → Platform → SokoMind.");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        List<Map<String, String>> messages = new ArrayList<>();
        for (AiChatCompletionRequest.AiChatMessage msg : request.messages()) {
            messages.add(Map.of("role", msg.role(), "content", msg.content()));
        }
        payload.put("messages", messages);
        payload.put("temperature", request.temperature());
        if (request.maxTokens() != null && request.maxTokens() > 0) {
            payload.put("max_tokens", request.maxTokens());
        }

        final String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "AI request body", ex);
        }

        var post = Unirest.post(url).header("Content-Type", "application/json");
        if (rapidHost != null && !rapidHost.isBlank()) {
            post = post.header("x-rapidapi-host", rapidHost.strip())
                    .header("x-rapidapi-key", apiKey.strip());
        } else {
            post = post.header("Authorization", "Bearer " + apiKey.strip());
        }

        HttpResponse<String> response;
        try {
            response = post.body(json).asString();
        } catch (Exception ex) {
            log.warn("SokoMind OpenAI-compatible request failed: {}", ex.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI provider unreachable");
        }

        if (response.getStatus() < 200 || response.getStatus() >= 300) {
            log.warn("SokoMind OpenAI-compatible HTTP {}: {}", response.getStatus(), truncate(response.getBody()));
            int status = response.getStatus();
            String hint = switch (status) {
                case 401, 403 -> " — the API key looks wrong";
                case 429 -> " — rate limited, try again later";
                default -> "";
            };
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "AI provider '" + provider + "' returned HTTP " + status + hint
                            + ". Check the key in Super Admin → Platform → SokoMind.");
        }

        return parse(response.getBody(), provider, model);
    }

    private AiChatCompletionResult parse(String rawBody, String provider, String model) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            String content = null;
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode message = choices.get(0).path("message");
                if (message.hasNonNull("content")) {
                    content = message.get("content").asText();
                }
            }
            if (content == null || content.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI provider returned empty content");
            }
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
            return new AiChatCompletionResult(content.trim(), provider, model, promptTokens, completionTokens);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("SokoMind OpenAI-compatible parse failed: {}", ex.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI provider response unreadable");
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        return b;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 200 ? value : value.substring(0, 200) + "…";
    }
}
