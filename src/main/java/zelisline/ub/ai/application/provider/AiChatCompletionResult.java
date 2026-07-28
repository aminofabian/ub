package zelisline.ub.ai.application.provider;

/** Result of a provider chat completion. */
public record AiChatCompletionResult(
        String content,
        String provider,
        String model,
        Integer promptTokens,
        Integer completionTokens
) {}
