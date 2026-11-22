package zelisline.ub.wholesale.api.dto;

import jakarta.validation.constraints.Size;

/** A supplier's reason for appealing one counted order. Sent into the supplier's support thread. */
public record AppealSalesOrderRequest(
        @Size(max = 1000) String reason
) {
}
