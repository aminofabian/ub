package zelisline.ub.identity.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Browser-supplied Google callback parameters, relayed by the same-host BFF so the
 * session can be minted on a 200 JSON response instead of a proxied 302.
 */
public record GoogleOAuthExchangeRequest(
        @NotBlank String code,
        @NotBlank String state
) {}
