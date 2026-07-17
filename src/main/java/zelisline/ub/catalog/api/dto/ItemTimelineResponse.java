package zelisline.ub.catalog.api.dto;

import java.util.List;

public record ItemTimelineResponse(
        String itemId,
        List<ItemTimelineEntryResponse> entries
) {
}
