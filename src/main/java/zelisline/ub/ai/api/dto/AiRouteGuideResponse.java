package zelisline.ub.ai.api.dto;

import java.util.List;

public record AiRouteGuideResponse(
        String surface,
        String title,
        String summary,
        List<String> suggestions
) {}
