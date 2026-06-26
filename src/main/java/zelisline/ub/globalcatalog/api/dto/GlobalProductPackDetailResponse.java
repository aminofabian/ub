package zelisline.ub.globalcatalog.api.dto;

import java.util.List;

public record GlobalProductPackDetailResponse(
        String id,
        String code,
        String name,
        String description,
        List<GlobalProductResponse> products
) {
}
