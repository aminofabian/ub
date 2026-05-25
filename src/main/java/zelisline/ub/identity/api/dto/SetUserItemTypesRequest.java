package zelisline.ub.identity.api.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;

/**
 * Replace the set of item types (departments) a user is restricted to. An
 * empty list removes all assignments — for {@code grocery_clerk} this means
 * the user will see no items until departments are reassigned.
 */
public record SetUserItemTypesRequest(
        @NotNull List<String> itemTypeIds
) {
}
