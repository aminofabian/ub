package zelisline.ub.wholesale.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** The supplier's dispatch lead time, in working days: one to three (SUPPLIER_ENGAGEMENT_DESIGN.md D5). */
public record SetDispatchLeadTimeRequest(@Min(1) @Max(3) int workingDays) {
}
