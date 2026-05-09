package zelisline.ub.inventory.api.dto;

import jakarta.validation.constraints.Size;

public record PatchSupplyBatchRequest(
        @Size(max = 255) String batchName,
        String status
) {
}
