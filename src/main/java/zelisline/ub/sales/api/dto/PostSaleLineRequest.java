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
        String airtimeNetwork,
        Boolean sellAtRegular,
        /** For {@code kind = SERVICE}: the service sold. */
        String serviceId,
        /** For {@code kind = SERVICE}: the staff profile that performed it (not the cashier). */
        String performerStaffProfileId
) {
    /** Shape used before service lines existed; older queued sales and callers still build it. */
    public PostSaleLineRequest(
            String itemId,
            BigDecimal quantity,
            BigDecimal unitPrice,
            String kind,
            String label,
            String airtimePhone,
            String airtimeNetwork,
            Boolean sellAtRegular
    ) {
        this(itemId, quantity, unitPrice, kind, label, airtimePhone, airtimeNetwork, sellAtRegular, null, null);
    }

    public static PostSaleLineRequest catalogItem(
            String itemId,
            BigDecimal quantity,
            BigDecimal unitPrice
    ) {
        return new PostSaleLineRequest(itemId, quantity, unitPrice, null, null, null, null, null);
    }

    public boolean sellAtRegularFlag() {
        return Boolean.TRUE.equals(sellAtRegular);
    }

    public boolean isAirtime() {
        return SaleLineKinds.isAirtime(kind);
    }

    public boolean isService() {
        return SaleLineKinds.isService(kind);
    }
}
