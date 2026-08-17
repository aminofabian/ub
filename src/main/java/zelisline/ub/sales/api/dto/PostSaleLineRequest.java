package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import zelisline.ub.sales.domain.SaleLineKinds;

public record PostSaleLineRequest(
        String itemId,
        @NotNull @Positive BigDecimal quantity,
        @NotNull BigDecimal unitPrice,
        String kind,
        String label,
        String airtimePhone,
        String airtimeNetwork
) {
    public static PostSaleLineRequest catalogItem(
            String itemId,
            BigDecimal quantity,
            BigDecimal unitPrice
    ) {
        return new PostSaleLineRequest(itemId, quantity, unitPrice, null, null, null, null);
    }

    public boolean isAirtime() {
        return SaleLineKinds.isAirtime(kind);
    }
}
