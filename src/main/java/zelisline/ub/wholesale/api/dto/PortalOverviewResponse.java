package zelisline.ub.wholesale.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The overview for a supplier in the portal, made of the purchase orders shops have sent it. It works with or
 * without a shop. {@code state} is "new" until there are enough answered orders from enough shops to say
 * anything; the median is null until then.
 */
public record PortalOverviewResponse(
        boolean enabled,
        SupplierOverviewResponse.NextThing nextThing,
        long waitingToAnswer,
        Instant oldestWaitingSince,
        String state,
        int answeredOrders,
        int shops,
        BigDecimal medianAnswerWorkingHours,
        List<String> milestones
) {

    public static PortalOverviewResponse off() {
        return new PortalOverviewResponse(false, null, 0, null, "off", 0, 0, null, List.of());
    }
}
