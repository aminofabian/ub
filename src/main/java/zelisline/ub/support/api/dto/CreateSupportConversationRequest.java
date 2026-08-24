package zelisline.ub.support.api.dto;

import jakarta.validation.constraints.Size;

public record CreateSupportConversationRequest(
        @Size(max = 191) String subject
) {}
