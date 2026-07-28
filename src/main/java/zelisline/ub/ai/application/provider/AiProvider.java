package zelisline.ub.ai.application.provider;

import zelisline.ub.ai.application.ResolvedSokoMindConfig;

public interface AiProvider {

    String id();

    boolean supports(String primaryProvider);

    AiChatCompletionResult complete(ResolvedSokoMindConfig config, AiChatCompletionRequest request);
}
