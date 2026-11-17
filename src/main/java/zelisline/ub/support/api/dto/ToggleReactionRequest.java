package zelisline.ub.support.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Toggle one emoji reaction on a message (add if absent, remove if present). */
public record ToggleReactionRequest(
        @NotBlank @Size(max = 32) String emoji
) {}
