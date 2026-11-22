package zelisline.ub.wholesale.api.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The supplier's answer to a placed order: for every line, how much it will supply. The full
 * quantity accepts the line, less part-accepts it, zero rejects it.
 */
public record AnswerSalesOrderRequest(
        @NotEmpty @Size(max = 50) @Valid List<Line> lines
) {
    public record Line(
            @NotBlank @Size(max = 36) String lineId,
            @NotNull @DecimalMin("0") BigDecimal acceptedQty
    ) {
    }
}
