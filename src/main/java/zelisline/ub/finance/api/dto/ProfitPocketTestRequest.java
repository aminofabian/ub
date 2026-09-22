package zelisline.ub.finance.api.dto;

import jakarta.validation.constraints.Size;

/** Optional body for Profit Pocket destination test. */
public record ProfitPocketTestRequest(
        /** Override settings stkPhone for this test (Daraja Express). */
        @Size(max = 32) String phoneNumber
) {
}
