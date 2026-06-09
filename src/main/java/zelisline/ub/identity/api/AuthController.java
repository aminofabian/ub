package zelisline.ub.identity.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import zelisline.ub.identity.api.dto.LoginPinRequest;
import zelisline.ub.identity.api.dto.LoginRequest;
import zelisline.ub.identity.api.dto.LoginResponse;
import zelisline.ub.identity.api.dto.PasswordChangeRequest;
import zelisline.ub.identity.api.dto.PasswordForgotRequest;
import zelisline.ub.identity.api.dto.PasswordResetRequest;
import zelisline.ub.identity.api.dto.PublicBranchResponse;
import zelisline.ub.identity.api.dto.RefreshRequest;
import zelisline.ub.identity.api.dto.RegisterRequest;
import zelisline.ub.identity.api.dto.RegisterResponse;
import zelisline.ub.identity.api.dto.ResendVerificationLinkResponse;
import zelisline.ub.identity.api.dto.VerifyEmailRequest;
import zelisline.ub.identity.application.AuthRegistrationService;
import zelisline.ub.identity.application.AuthService;
import zelisline.ub.identity.application.LoginBranchDirectoryService;
import zelisline.ub.identity.application.RefreshTokenCookieSupport;
import zelisline.ub.platform.security.CurrentTenantUser;

@Validated
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuthRegistrationService authRegistrationService;
    private final LoginBranchDirectoryService loginBranchDirectoryService;
    private final RefreshTokenCookieSupport refreshTokenCookieSupport;

    @Value("${app.auth.return-verification-link-in-register-response:false}")
    private boolean returnVerificationLinkInRegisterResponse;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@Valid @RequestBody RegisterRequest body, HttpServletRequest http) {
        return authRegistrationService.register(http, body);
    }

    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyEmail(@Valid @RequestBody VerifyEmailRequest body, HttpServletRequest http) {
        authRegistrationService.verifyEmail(http, body);
    }

    /**
     * Same anti-enumeration contract as {@link #passwordForgot}: missing/unknown email → {@code 204}.
     * When {@code app.auth.return-verification-link-in-register-response} is true and a new token is issued,
     * returns {@code 200} with {@link ResendVerificationLinkResponse} so the UI can show the link without mail.
     */
    @PostMapping("/resend-verification")
    public ResponseEntity<?> resendVerification(HttpServletRequest http, @RequestBody(required = false) PasswordForgotRequest body) {
        var link = authRegistrationService.resendVerification(http, body);
        if (returnVerificationLinkInRegisterResponse) {
            return link.<ResponseEntity<?>>map(url -> ResponseEntity.ok(new ResendVerificationLinkResponse(url)))
                    .orElse(ResponseEntity.noContent().build());
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return toSessionResponse(authService.login(http, request));
    }

    @PostMapping("/login-pin")
    public ResponseEntity<LoginResponse> loginPin(@Valid @RequestBody LoginPinRequest request, HttpServletRequest http) {
        return toSessionResponse(authService.loginPin(http, request));
    }

    /**
     * Active branches (id + name) for the request's tenant, so the PIN login
     * screen can offer a branch picker instead of a raw UUID field. Public:
     * returns {@code []} when no tenant is resolvable.
     */
    @GetMapping("/branches")
    public List<PublicBranchResponse> branches(HttpServletRequest http) {
        return loginBranchDirectoryService.listForTenant(http);
    }

    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(
            HttpServletRequest http,
            @RequestBody(required = false) RefreshRequest request
    ) {
        return toSessionResponse(authService.refresh(http, request == null ? new RefreshRequest(null) : request));
    }

    @PostMapping("/logout")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> logout(HttpServletRequest http) {
        authService.logout(CurrentTenantUser.requireHuman(http));
        return ResponseEntity.noContent()
                .headers(refreshTokenCookieSupport.clearCookieHeaders())
                .build();
    }

    /** Clears the httpOnly refresh cookie without requiring a valid access token. */
    @PostMapping("/clear-session-cookie")
    public ResponseEntity<Void> clearSessionCookie() {
        return ResponseEntity.noContent()
                .headers(refreshTokenCookieSupport.clearCookieHeaders())
                .build();
    }

    @PostMapping("/logout-all")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> logoutAll(HttpServletRequest http) {
        authService.logoutAll(CurrentTenantUser.requireHuman(http));
        return ResponseEntity.noContent()
                .headers(refreshTokenCookieSupport.clearCookieHeaders())
                .build();
    }

    /**
     * Always returns {@code 204} — does not reveal whether the email exists
     * (PHASE_1_PLAN.md §3.3).
     */
    @PostMapping("/password/forgot")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void passwordForgot(
            HttpServletRequest http,
            @RequestBody(required = false) PasswordForgotRequest request
    ) {
        authService.passwordForgot(http, request);
    }

    @PostMapping("/password/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void passwordReset(@Valid @RequestBody PasswordResetRequest request, HttpServletRequest http) {
        authService.passwordReset(http, request);
    }

    @PostMapping("/password/change")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void passwordChange(@Valid @RequestBody PasswordChangeRequest request, HttpServletRequest http) {
        authService.passwordChange(http, CurrentTenantUser.requireHuman(http), request);
    }

    private ResponseEntity<LoginResponse> toSessionResponse(LoginResponse tokens) {
        if (!refreshTokenCookieSupport.isEnabled()) {
            return ResponseEntity.ok(tokens);
        }
        LoginResponse body = new LoginResponse(tokens.accessToken(), null, tokens.user());
        return ResponseEntity.ok()
                .headers(refreshTokenCookieSupport.cookieHeaders(tokens.refreshToken()))
                .body(body);
    }
}
