package zelisline.ub.sales.application;

import zelisline.ub.sales.api.dto.SaleResponse;

public record SaleCreationOutcome(SaleResponse response, boolean createdNew) {
}
