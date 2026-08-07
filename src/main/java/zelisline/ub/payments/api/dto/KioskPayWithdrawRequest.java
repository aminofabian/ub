package zelisline.ub.payments.api.dto;

import java.math.BigDecimal;

public record KioskPayWithdrawRequest(
        BigDecimal amount,
        String phoneNumber,
        String idempotencyKey
) {
}
