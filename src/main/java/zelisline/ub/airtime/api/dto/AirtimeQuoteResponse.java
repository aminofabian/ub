package zelisline.ub.airtime.api.dto;

import java.math.BigDecimal;

/**
 * What a given amount costs and earns, resolved server-side so the till never
 * has to compute margin itself.
 */
public record AirtimeQuoteResponse(
        boolean sellable,
        String phoneNumber,
        String network,
        BigDecimal amount,
        BigDecimal cost,
        BigDecimal commission,
        BigDecimal walletBalanceAfter,
        String currency,
        String reason
) {
}
