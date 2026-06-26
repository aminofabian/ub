package zelisline.ub.globalcatalog.api.dto;

import java.util.List;

public record AdoptResponse(
        int importedCount,
        int skippedCount,
        List<AdoptResultLineResponse> lines
) {
}
