package zelisline.ub.ai.application.provider;

import java.util.List;

/** Chat completion request for a single SokoMind turn (non-streaming). */
public record AiChatCompletionRequest(
        String model,
        List<AiChatMessage> messages,
        double temperature,
        Integer maxTokens
) {
    public record AiChatMessage(String role, String content) {}
}
