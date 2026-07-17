package zelisline.ub.catalog.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RecordItemScanRequest(
        @NotBlank @Size(max = 40) String source,
        @Size(max = 128) String barcode,
        @Size(max = 36) String branchId,
        @Size(max = 36) String sessionId
) {
}
