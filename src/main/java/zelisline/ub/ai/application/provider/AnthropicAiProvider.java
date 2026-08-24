package zelisline.ub.ai.application.provider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
import zelisline.ub.platform.domain.PlatformSokoMindSettings;

@Component
@RequiredArgsConstructor
public class AnthropicAiProvider implements AiProvider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicAiProvider.class);
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final ObjectMapper objectMapper;

    @Override
    public String id() {
        return PlatformSokoMindSettings.PROVIDER_ANTHROPIC;
    }

    @Override
    public boolean supports(String primaryProvider) {
        return PlatformSokoMindSettings.PROVIDER_ANTHROPIC.equalsIgnoreCase(primaryProvider);
    }

    @Override
    public AiChatCompletionResult complete(ResolvedSokoMindConfig config, AiChatCompletionRequest request) {
        String apiKey = config.anthropicApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Anthropic API key is not configured. Set it in Super Admin → Platform → SokoMind.");
        }

        String model = request.model();
        if (model == null || model.isBlank()) {
            model = config.anthropicMiniModel();
        }

        String system = null;
        List<Map<String, String>> messages = new ArrayList<>();
        for (AiChatCompletionRequest.AiChatMessage msg : request.messages()) {
            if ("system".equalsIgnoreCase(msg.role())) {
                system = system == null ? msg.content() : system + "\n\n" + msg.content();
            } else {
                String role = "assistant".equalsIgnoreCase(msg.role()) ? "assistant" : "user";
                messages.add(Map.of("role", role, "content", msg.content()));
            }
        }
        if (messages.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No user messages for Anthropic");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("max_tokens", request.maxTokens() != null && request.maxTokens() > 0 ? request.maxTokens() : 1024);
        payload.put("temperature", request.temperature());
        if (system != null && !system.isBlank()) {
            payload.put("system", system);
        }
        payload.put("messages", messages);

        final String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "AI request body", ex);
        }

        String base = ensureScheme(firstNonBlank(config.anthropicBaseUrl(), "https://api.anthropic.com"));
        String url = base.endsWith("/") ? base + "v1/messages" : base + "/v1/messages";

        HttpResponse<String> response;
        try {
            response = Unirest.post(url)
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey.strip())
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .body(json)
                    .asString();
        } catch (Exception ex) {
            log.warn("SokoMind Anthropic request to {} failed", url, ex);
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "AI provider 'anthropic' unreachable at " + url + " — "
                            + failureSummary(ex)
                            + (isUrlProblem(ex)
                                    ? " The configured base URL is invalid — fix it in Super Admin → Platform → SokoMind."
                                    : " Check that the server can reach the provider host (egress/firewall)."));
        }

        if (response.getStatus() < 200 || response.getStatus() >= 300) {
            String body = truncate(response.getBody());
            log.warn("SokoMind Anthropic HTTP {} from {}: {}", response.getStatus(), url, body);
            int status = response.getStatus();
            String hint = switch (status) {
                case 400 -> " — the provider rejected the request (invalid model name?)";
                case 401, 403 -> " — the API key looks wrong";
                case 429 -> " — rate limited, try again later";
                default -> "";
            };
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "AI provider 'anthropic' returned HTTP " + status + hint
                            + (body.isEmpty() ? "" : ": " + body));
        }

        return parse(response.getBody(), model);
    }

    private AiChatCompletionResult parse(String rawBody, String model) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            StringBuilder content = new StringBuilder();
            JsonNode blocks = root.path("content");
            if (blocks.isArray()) {
                for (JsonNode block : blocks) {
                    if ("text".equals(block.path("type").asText()) && block.hasNonNull("text")) {
                        if (content.length() > 0) {
                            content.append('\n');
                        }
                        content.append(block.get("text").asText());
                    }
                }
            }
            if (content.length() == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI provider returned empty content");
            }
            Integer promptTokens = null;
            Integer completionTokens = null;
            JsonNode usage = root.path("usage");
            if (usage.isObject()) {
                if (usage.has("input_tokens")) {
                    promptTokens = usage.get("input_tokens").asInt();
                }
                if (usage.has("output_tokens")) {
                    completionTokens = usage.get("output_tokens").asInt();
                }
            }
            return new AiChatCompletionResult(
                    content.toString().trim(),
                    PlatformSokoMindSettings.PROVIDER_ANTHROPIC,
                    model,
                    promptTokens,
                    completionTokens);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("SokoMind Anthropic parse failed: {}", ex.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI provider response unreadable");
        }
    }

    /** Prepend https:// when a stored base URL is missing its scheme. */
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

    private static String failureSummary(Exception ex) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = ex;
        while (cur != null) {
            if (sb.length() > 0) {
                sb.append(" → ");
            }
            sb.append(cur.getClass().getSimpleName());
            String msg = cur.getMessage();
            if (msg != null && !msg.isBlank()) {
                sb.append(": ").append(msg);
            }
            if (sb.length() > 400) {
                break;
            }
            cur = cur.getCause();
        }
        return sb.toString();
    }

    /** True when the failure is a malformed URL rather than a network problem. */
    private static boolean isUrlProblem(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            String name = cur.getClass().getSimpleName();
            if ("ClientProtocolException".equals(name) || "URISyntaxException".equals(name)) {
                return true;
            }
            String msg = cur.getMessage();
            if (msg != null && (msg.contains("valid host") || msg.contains("no protocol"))) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
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
