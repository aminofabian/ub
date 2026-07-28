package zelisline.ub.ai.api.dto;

import java.util.List;

public record AiChatResponse(
        String requestId,
        String reply,
        String skill,
        String surface,
        List<String> suggestions,
        String provider,
        String model,
        long latencyMs,
        List<String> toolsUsed,
        boolean usedLiveData,
        String draftBody
) {}
