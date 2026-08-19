package zelisline.ub.credits.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import zelisline.ub.identity.api.dto.AuthUserResponse;

public record ShopperPhoneSessionResponse(
        String accessToken,
        @JsonInclude(JsonInclude.Include.NON_NULL) String refreshToken,
        AuthUserResponse user,
        String tabPhone,
        @JsonInclude(JsonInclude.Include.NON_NULL) String unlockToken,
        boolean pinCreated
) {
}
