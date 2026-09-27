package zelisline.ub.integrations.pickupmtaani.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Result of a public destination lookup. {@code kind} tells the storefront which
 * picker to render; {@code options} is the list for that level.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PickupMtaaniDestinationsResponse(
        String kind,
        List<PickupMtaaniGeoOption> options
) {
}
