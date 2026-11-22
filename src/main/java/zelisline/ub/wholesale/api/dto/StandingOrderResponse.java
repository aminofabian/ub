package zelisline.ub.wholesale.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A repeating order. The supplier's view has the buyer's shop; the buyer's view has the supplier's. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StandingOrderResponse(
        String id,
        String supplierName,
        String buyerBusinessName,
        int everyDays,
        LocalDate nextRunOn,
        String lastResult,
        List<Line> lines
) {
    public record Line(String itemName, BigDecimal quantity) {
    }
}
