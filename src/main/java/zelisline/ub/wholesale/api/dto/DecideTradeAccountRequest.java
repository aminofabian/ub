package zelisline.ub.wholesale.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** The supplier's decision on an account. The limit and the days to pay are kept with an approval. */
public record DecideTradeAccountRequest(
        @NotBlank @Pattern(regexp = "approved|declined|suspended") String status,
        @DecimalMin("0") BigDecimal creditLimit,
        @Min(0) @Max(365) Integer termsDays,
        /** A trade discount, 0 to 90. Kept only on an account that names a shop. */
        @DecimalMin("0") @jakarta.validation.constraints.DecimalMax("90") BigDecimal discountPercent
) {
    public DecideTradeAccountRequest(String status, BigDecimal creditLimit, Integer termsDays) {
        this(status, creditLimit, termsDays, null);
    }

}
