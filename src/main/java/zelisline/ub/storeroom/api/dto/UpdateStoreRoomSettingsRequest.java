package zelisline.ub.storeroom.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The merchant's answer to "should your store room follow inventory?". Accepted as a
 * string rather than an enum so an unknown value produces a readable 400.
 */
public record UpdateStoreRoomSettingsRequest(
        @NotBlank @Size(max = 16) String mode
) {
}
