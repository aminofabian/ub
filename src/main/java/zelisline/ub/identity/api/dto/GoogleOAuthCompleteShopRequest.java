package zelisline.ub.identity.api.dto;

import jakarta.validation.constraints.NotBlank;

/** Shop slug chosen after Google sign-in found more than one staff membership. */
public record GoogleOAuthCompleteShopRequest(@NotBlank String slug) {}
