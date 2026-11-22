package zelisline.ub.wholesale.api.dto;

import java.math.BigDecimal;
import java.util.List;

/** What buyers owe, by how late it is. Days are days past the due date. */
public record ReceivablesAgeingResponse(
        String currency,
        BigDecimal total,
        List<Row> buyers
) {
    public record Row(
            String buyer,
            String buyerPhone,
            BigDecimal notYetDue,
            BigDecimal days1To30,
            BigDecimal days31To60,
            BigDecimal days61To90,
            BigDecimal over90,
            BigDecimal total
    ) {
    }
}
