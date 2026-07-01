package zelisline.ub.storefront.api.dto;

import java.util.List;

public record PublicDepartmentListResponse(
        List<PublicDepartmentResponse> types
) {
}
