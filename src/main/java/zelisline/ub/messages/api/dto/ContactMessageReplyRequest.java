package zelisline.ub.messages.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import zelisline.ub.messages.domain.ContactReplyChannel;

public record ContactMessageReplyRequest(
        @NotNull ContactReplyChannel channel,
        @NotBlank @Size(max = 4000) String body
) {}
