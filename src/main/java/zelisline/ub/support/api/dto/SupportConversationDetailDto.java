package zelisline.ub.support.api.dto;

import java.util.List;

public record SupportConversationDetailDto(
        SupportConversationDto conversation,
        List<SupportMessageDto> messages
) {}
