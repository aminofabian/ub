package zelisline.ub.serviceops.api.dto;

import java.math.BigDecimal;
import java.util.List;

public record ServiceResponse(
        String id,
        String categoryId,
        String name,
        String description,
        int durationMinutes,
        BigDecimal price,
        boolean priceIsVariable,
        String commissionType,
        BigDecimal commissionValue,
        int sortOrder,
        boolean active,
        /** Staff profiles that may perform this service; empty means anyone. */
        List<String> staffProfileIds,
        long version
) {
    /** The same service without its commission override, for callers who only sell. */
    public ServiceResponse withoutCommission() {
        return new ServiceResponse(id, categoryId, name, description, durationMinutes, price, priceIsVariable,
                null, null, sortOrder, active, staffProfileIds, version);
    }
}
