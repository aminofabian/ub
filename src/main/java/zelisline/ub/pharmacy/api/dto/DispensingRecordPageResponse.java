package zelisline.ub.pharmacy.api.dto;

import java.util.List;

public record DispensingRecordPageResponse(
        List<DispensingRecordResponse> records,
        long total,
        int page,
        int size
) {
}
