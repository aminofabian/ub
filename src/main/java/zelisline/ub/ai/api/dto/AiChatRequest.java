package zelisline.ub.ai.api.dto;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AiChatRequest(
        @NotBlank @Size(max = 4000) String message,
        @Size(max = 64) String skill,
        AiContextPacket context,
        List<AiHistoryMessage> history
) {
    public record AiContextPacket(
            @Size(max = 128) String surface,
            @Size(max = 512) String route,
            @Size(max = 16) String locale,
            Map<String, String> entities,
            List<@Size(max = 64) String> uiHints
    ) {}

    public record AiHistoryMessage(
            @NotBlank @Size(max = 32) String role,
            @NotBlank @Size(max = 4000) String content
    ) {}
}
