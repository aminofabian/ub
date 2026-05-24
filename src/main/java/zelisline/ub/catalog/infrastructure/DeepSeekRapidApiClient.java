package zelisline.ub.catalog.infrastructure;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;

import zelisline.ub.platform.application.PlatformIntegrationSettingsService;
import zelisline.ub.platform.application.ResolvedCatalogAiConfig;

@Component
@RequiredArgsConstructor
public class DeepSeekRapidApiClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekRapidApiClient.class);

    private final PlatformIntegrationSettingsService platformIntegrationSettingsService;
    private final ObjectMapper objectMapper;

    public String complete(String systemPrompt, String userPrompt) {
        ResolvedCatalogAiConfig properties =
                platformIntegrationSettingsService.resolveCatalogAi();
        if (!properties.configured()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "AI description generation is not configured. Set a RapidAPI DeepSeek key in "
                            + "Super Admin → Platform → Integrations, or set RAPIDAPI_DEEPSEEK_KEY "
                            + "(or RAPIDAPI_KEY) in the server environment.");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.model());
        List<Map<String, String>> messages = new java.util.ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt.strip()));
        }
        messages.add(Map.of("role", "user", "content", userPrompt.strip()));
        payload.put("messages", messages);

        final String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "AI request body", ex);
        }

        HttpResponse<String> response;
        try {
            response = Unirest.post(properties.url())
                    .header("Content-Type", "application/json")
                    .header("x-rapidapi-host", properties.host())
                    .header("x-rapidapi-key", properties.apiKey().strip())
                    .body(json)
                    .asString();
        } catch (Exception ex) {
            log.warn("DeepSeek request failed: {}", ex.getMessage());
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "AI provider unreachable");
        }

        if (response.getStatus() < 200 || response.getStatus() >= 300) {
            log.warn("DeepSeek HTTP {}: {}", response.getStatus(), truncate(response.getBody()));
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "AI provider returned an error");
        }

        String content = extractContent(response.getBody());
        if (content == null || content.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "AI provider returned empty content");
        }
        return content.trim();
    }

    private String extractContent(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode message = choices.get(0).path("message");
                if (message.hasNonNull("content")) {
                    return message.get("content").asText();
                }
            }
            if (root.hasNonNull("content")) {
                return root.get("content").asText();
            }
            if (root.hasNonNull("result")) {
                return root.get("result").asText();
            }
        } catch (Exception ex) {
            log.warn("DeepSeek response parse failed: {}", ex.getMessage());
        }
        return null;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 200 ? value : value.substring(0, 200) + "…";
    }
}
