package zelisline.ub.tenancy.api.dto;

import java.util.List;

public record StoreSectionStarterKitResponse(
        String id,
        String label,
        List<String> sections
) {
}
