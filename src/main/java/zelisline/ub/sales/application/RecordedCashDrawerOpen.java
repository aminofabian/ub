package zelisline.ub.sales.application;

import zelisline.ub.sales.api.dto.CashDrawerOpenResponse;

/** {@code created} is false when this client event was already stored. */
public record RecordedCashDrawerOpen(CashDrawerOpenResponse open, boolean created) {
}
