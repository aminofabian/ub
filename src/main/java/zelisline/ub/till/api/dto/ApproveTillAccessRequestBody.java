package zelisline.ub.till.api.dto;

import jakarta.validation.constraints.Size;

public record ApproveTillAccessRequestBody(@Size(max = 80) String label) {
}
