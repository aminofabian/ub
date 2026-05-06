package zelisline.ub.storefront.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record ShopperLedgerLineResponse(
        Instant occurredAt,
        String kind,
        String memo,
        BigDecimal debit,
        BigDecimal credit
) {
}
