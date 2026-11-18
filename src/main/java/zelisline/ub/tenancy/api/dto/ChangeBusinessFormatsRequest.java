package zelisline.ub.tenancy.api.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

/**
 * The full new set of shop formats a business should run (a replace, not an add). Each code must be a
 * known store format; the service forbids an empty set and guards any removal that has live work.
 */
public record ChangeBusinessFormatsRequest(
        @NotEmpty List<@Pattern(regexp = StoreTypeCodes.PATTERN) String> storeTypes) {
}
