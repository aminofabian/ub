package zelisline.ub.catalog.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CatalogRowTypeCountsResponse(
        @JsonProperty("parents") long parents,
        @JsonProperty("variants") long variants,
        @JsonProperty("standalones") long standalones,
        @JsonProperty("missingBarcode") long missingBarcode,
        @JsonProperty("inactive") long inactive,
        @JsonProperty("missingPrice") long missingPrice,
        @JsonProperty("zeroStock") long zeroStock,
        @JsonProperty("lowStock") long lowStock
) {
}
