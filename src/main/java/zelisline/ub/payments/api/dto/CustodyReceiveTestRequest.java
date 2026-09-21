package zelisline.ub.payments.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Onboarding / settings: save a till-paybill destination and send a tiny STK
 * so the merchant can confirm money landed on their till, paybill, or bank.
 */
public record CustodyReceiveTestRequest(
        @NotBlank
        @Pattern(regexp = "till|paybill")
        String type,

        @Size(max = 32)
        String tillNumber,

        @Size(max = 32)
        String businessNumber,

        @Size(max = 64)
        String accountNumber,

        @Size(max = 100)
        String label,

        @NotBlank
        @Size(max = 32)
        String phoneNumber,

        /** Defaults to KES 1 — enough to prove the rail without a big float. */
        @DecimalMin("1")
        BigDecimal amount
) {
}
