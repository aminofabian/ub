package zelisline.ub.trade.api.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** What the buying shop says arrived, for every line of the order. */
public record RecordReceivedRequest(@NotEmpty @Size(max = 200) @Valid List<Line> lines) {

    public record Line(@Min(1) int lineNo, @NotNull @DecimalMin("0") BigDecimal receivedQty) {
    }
}
