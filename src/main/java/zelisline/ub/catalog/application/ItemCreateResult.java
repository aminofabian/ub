package zelisline.ub.catalog.application;

import zelisline.ub.catalog.api.dto.ItemResponse;

public record ItemCreateResult(int httpStatus, ItemResponse body) {
}
