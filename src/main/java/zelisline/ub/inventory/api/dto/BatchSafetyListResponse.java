package zelisline.ub.inventory.api.dto;

import java.util.List;

public record BatchSafetyListResponse(String view, List<BatchSafetyRow> rows, long total) {
}
