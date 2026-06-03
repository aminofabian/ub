package zelisline.ub.identity.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

public record LoginResponse(
        String accessToken,
        @JsonInclude(JsonInclude.Include.NON_NULL) String refreshToken,
        AuthUserResponse user
) {
}
