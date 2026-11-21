package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

import zelisline.ub.sales.domain.CashDrawerOpenReason;
import zelisline.ub.sales.domain.CashDrawerOpenSource;

public record CashDrawerOpenLogRow(
        String id,
        Instant openedAt,
        Instant closedAt,
        CashDrawerOpenSource source,
        CashDrawerOpenReason reason,
        boolean matched,
        String matchKind,
        String matchLabel,
        BigDecimal matchAmount,
        Instant matchAt
) {}
