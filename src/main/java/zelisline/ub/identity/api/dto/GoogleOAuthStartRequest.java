package zelisline.ub.identity.api.dto;

import jakarta.validation.constraints.NotBlank;

public record GoogleOAuthStartRequest(
        @NotBlank String intent,
        String next,
        String businessId,
        /** Optional browser host to return to after OAuth (custom domain / tenant subdomain). */
        String returnHost
) {}
