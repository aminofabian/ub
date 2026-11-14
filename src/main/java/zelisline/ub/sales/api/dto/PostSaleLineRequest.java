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
        String performerStaffProfileId,
        /** For {@code kind = SERVICE}: a second staff member who shared the work, if any. */
        String assistantStaffProfileId,
        /** The second staff member's share of the line, 1 to 99; half when left out. */
        BigDecimal assistantSharePercent,
        /** For {@code kind = SERVICE}: everyone who shared the work with the performer. */
        @jakarta.validation.Valid java.util.List<PostSaleLineHelper> helpers,
        /**
         * For {@code kind = SERVICE}: what the performer is paid for this service, in whole
         * shillings, instead of what their plan gives. Needs permission to set commission.
         */
        BigDecimal performerAmount
) {
    public PostSaleLineRequest(
            String itemId, BigDecimal quantity, BigDecimal unitPrice, String kind, String label,
            String airtimePhone, String airtimeNetwork, Boolean sellAtRegular, String serviceId,
            String performerStaffProfileId, String assistantStaffProfileId, BigDecimal assistantSharePercent,
            java.util.List<PostSaleLineHelper> helpers) {
        this(itemId, quantity, unitPrice, kind, label, airtimePhone, airtimeNetwork, sellAtRegular,
                serviceId, performerStaffProfileId, assistantStaffProfileId, assistantSharePercent, helpers, null);
    }

    public PostSaleLineRequest(
            String itemId, BigDecimal quantity, BigDecimal unitPrice, String kind, String label,
            String airtimePhone, String airtimeNetwork, Boolean sellAtRegular, String serviceId,
            String performerStaffProfileId, String assistantStaffProfileId, BigDecimal assistantSharePercent) {
        this(itemId, quantity, unitPrice, kind, label, airtimePhone, airtimeNetwork, sellAtRegular,
                serviceId, performerStaffProfileId, assistantStaffProfileId, assistantSharePercent, null);
    }

    /** A service line done by one person. */
    public PostSaleLineRequest(
            String itemId,
            BigDecimal quantity,
            BigDecimal unitPrice,
            String kind,
            String label,
            String airtimePhone,
            String airtimeNetwork,
            Boolean sellAtRegular,
            String serviceId,
            String performerStaffProfileId
    ) {
        this(itemId, quantity, unitPrice, kind, label, airtimePhone, airtimeNetwork, sellAtRegular,
                serviceId, performerStaffProfileId, null, null);
    }

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
        this(itemId, quantity, unitPrice, kind, label, airtimePhone, airtimeNetwork, sellAtRegular,
                null, null, null, null);
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
