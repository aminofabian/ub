package zelisline.ub.identity.api.dto;

/**
 * Admin reveal of a user's till PIN. {@code pin} is present only when a
 * recoverable encrypted copy exists ({@code recoverable=true}). Legacy rows
 * with only {@code pin_hash} report {@code hasPin=true} and {@code recoverable=false}.
 */
public record UserPinResponse(
        boolean hasPin,
        boolean recoverable,
        String pin
) {
}
