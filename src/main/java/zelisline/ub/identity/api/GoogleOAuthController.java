package zelisline.ub.identity.api;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.api.dto.GoogleOAuthCompleteShopRequest;
import zelisline.ub.identity.api.dto.GoogleOAuthExchangeRequest;
import zelisline.ub.identity.api.dto.GoogleOAuthExchangeResponse;
import zelisline.ub.identity.api.dto.GoogleOAuthPublicConfigResponse;
import zelisline.ub.identity.api.dto.GoogleOAuthShopChoiceResponse;
import zelisline.ub.identity.api.dto.GoogleOAuthStartRequest;
import zelisline.ub.identity.api.dto.GoogleOAuthStartResponse;
import zelisline.ub.identity.application.GoogleOAuthService;

@Validated
@RestController
@RequiredArgsConstructor
public class GoogleOAuthController {

    private final GoogleOAuthService googleOAuthService;

    @GetMapping("/api/v1/public/auth/oauth/google")
    public GoogleOAuthPublicConfigResponse publicConfig() {
        return googleOAuthService.publicConfig();
    }

    @PostMapping("/api/v1/auth/oauth/google/start")
    public ResponseEntity<GoogleOAuthStartResponse> start(
            HttpServletRequest http, @Valid @RequestBody GoogleOAuthStartRequest body) {
        return googleOAuthService.start(http, body);
    }

    @GetMapping("/api/v1/auth/oauth/google/callback")
    public ResponseEntity<Void> callback(
            HttpServletRequest http,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state) {
        return googleOAuthService.callback(http, code, state);
    }

    /**
     * Same-host BFF relay: the Next.js callback handler posts the browser's code/state
     * (plus its cookies, for browser binding) and receives the session as 200 JSON, so
     * cookies are minted on the frontend host without relying on a proxied 302's
     * {@code Set-Cookie} surviving the hop.
     */
    @PostMapping("/api/v1/auth/oauth/google/exchange")
    public ResponseEntity<GoogleOAuthExchangeResponse> exchange(
            HttpServletRequest http, @Valid @RequestBody GoogleOAuthExchangeRequest body) {
        return googleOAuthService.exchange(http, body.code(), body.state());
    }

    /** Staff shops for a Google sign-in that paused because the email is on more than one. */
    @GetMapping("/api/v1/auth/oauth/google/shops")
    public GoogleOAuthShopChoiceResponse shops(HttpServletRequest http) {
        return googleOAuthService.shops(http);
    }

    /** Finish Google sign-in after the person picks one of those shops. */
    @PostMapping("/api/v1/auth/oauth/google/complete")
    public ResponseEntity<GoogleOAuthExchangeResponse> completeShop(
            HttpServletRequest http, @Valid @RequestBody GoogleOAuthCompleteShopRequest body) {
        return googleOAuthService.completeShop(http, body.slug());
    }
}
