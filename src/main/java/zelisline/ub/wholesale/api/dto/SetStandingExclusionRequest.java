package zelisline.ub.wholesale.api.dto;

import jakarta.validation.constraints.Size;

/** Platform support's decision on one order. A note is required to exclude; restoring keeps the last note. */
public record SetStandingExclusionRequest(
        boolean excluded,
        @Size(max = 500) String note
) {
}
