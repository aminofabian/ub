package zelisline.ub.till.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A signed-in person asking for the phone they are using to be made a till at their branch.
 *
 * The business is never taken from the body: it comes from the signed-in session, so a phone cannot ask on behalf of
 * another shop. `deviceKey` is what the till already sends when it registers; `deviceName` is only shown to the owner
 * so they can recognise the phone in the approval message.
 */
public record TillAccessRequestCreateBody(
        @NotBlank @Size(max = 64) String branchId,
        @Size(max = 191) String deviceKey,
        @Size(max = 191) String deviceName
) {
}
