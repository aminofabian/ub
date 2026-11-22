package zelisline.ub.wholesale.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Money received against an invoice. The date defaults to today in Nairobi. */
public record RecordSalesReceiptRequest(
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotBlank @Pattern(regexp = "cash|mpesa|bank") String method,
        @Size(max = 128) String reference,
        LocalDate receivedOn
) {
}
