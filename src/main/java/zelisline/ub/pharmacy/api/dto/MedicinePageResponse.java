package zelisline.ub.pharmacy.api.dto;

import java.util.List;

public record MedicinePageResponse(
        List<MedicineDetailsResponse> items,
        long total,
        int page,
        int size
) {
}
