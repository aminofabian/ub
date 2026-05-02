package zelisline.ub.identity.api.dto;

import java.util.List;

public record RoleResponse(
        String id,
        String businessId,
        String key,
        String name,
        String description,
        boolean system,
        List<String> permissionKeys
) {
}
