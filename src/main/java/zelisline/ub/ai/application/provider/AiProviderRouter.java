package zelisline.ub.ai.application.provider;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

import zelisline.ub.ai.application.ResolvedSokoMindConfig;
import zelisline.ub.ai.application.SokoMindRuntimeService;

@Service
@RequiredArgsConstructor
public class AiProviderRouter {

    private final List<AiProvider> providers;
    private final SokoMindRuntimeService runtimeService;

    public AiChatCompletionResult completeMini(AiChatCompletionRequest request) {
        ResolvedSokoMindConfig config = runtimeService.config();
        return complete(config, withModel(request, pickMiniModel(config)));
    }

    public AiChatCompletionResult completeSmart(AiChatCompletionRequest request) {
        ResolvedSokoMindConfig config = runtimeService.config();
        return complete(config, withModel(request, pickSmartModel(config)));
    }

    public AiChatCompletionResult complete(ResolvedSokoMindConfig config, AiChatCompletionRequest request) {
        if (!config.enabled()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "SokoMind is disabled. Enable it in Super Admin → Platform → SokoMind.");
        }
        if (!config.primaryProviderConfigured()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "No API key configured for provider '"
                            + config.primaryProvider()
                            + "'. Set it in Super Admin → Platform → SokoMind.");
        }
        AiProvider provider = providers.stream()
                .filter(p -> p.supports(config.primaryProvider()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Unsupported SokoMind provider: " + config.primaryProvider()));
        return provider.complete(config, request);
    }

    private static String pickMiniModel(ResolvedSokoMindConfig config) {
        String provider = config.primaryProvider() == null ? "" : config.primaryProvider().toLowerCase();
        return switch (provider) {
            case "anthropic" -> config.anthropicMiniModel();
            case "deepseek", "rapidapi_deepseek" -> config.deepseekModel();
            default -> config.openaiMiniModel();
        };
    }

    private static String pickSmartModel(ResolvedSokoMindConfig config) {
        String provider = config.primaryProvider() == null ? "" : config.primaryProvider().toLowerCase();
        return switch (provider) {
            case "anthropic" -> config.anthropicSmartModel();
            case "deepseek", "rapidapi_deepseek" -> config.deepseekModel();
            default -> config.openaiSmartModel();
        };
    }

    private static AiChatCompletionRequest withModel(AiChatCompletionRequest request, String model) {
        if (request.model() != null && !request.model().isBlank()) {
            return request;
        }
        return new AiChatCompletionRequest(model, request.messages(), request.temperature(), request.maxTokens());
    }
}
