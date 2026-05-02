package zelisline.ub.identity.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.api.dto.LoginPinRequest;
import zelisline.ub.identity.api.dto.LoginRequest;
import zelisline.ub.identity.api.dto.LoginResponse;
import zelisline.ub.identity.api.dto.PasswordChangeRequest;
import zelisline.ub.identity.api.dto.PasswordForgotRequest;
import zelisline.ub.identity.api.dto.PasswordResetRequest;
import zelisline.ub.identity.api.dto.RefreshRequest;
import zelisline.ub.identity.api.dto.RegisterRequest;
import zelisline.ub.identity.api.dto.RegisterResponse;
import zelisline.ub.identity.api.dto.VerifyEmailRequest;
import zelisline.ub.identity.application.AuthRegistrationService;
import zelisline.ub.identity.application.AuthService;
import zelisline.ub.platform.security.CurrentTenantUser;

@Validated
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuthRegistrationService authRegistrationService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@Valid @RequestBody RegisterRequest body, HttpServletRequest http) {
        return authRegistrationService.register(http, body);
    }

    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyEmail(@Valid @RequestBody VerifyEmailRequest body) {
        authRegistrationService.verifyEmail(body);
    }

    /**
     * Same anti-enumeration contract as {@link #passwordForgot}: missing/unknown email → {@code 204}.
     */
    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resendVerification(HttpServletRequest http, @RequestBody(required = false) PasswordForgotRequest body) {
        authRegistrationService.resendVerification(http, body);
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return authService.login(http, request);
    }

    @PostMapping("/login-pin")
    public LoginResponse loginPin(@Valid @RequestBody LoginPinRequest request, HttpServletRequest http) {
        return authService.loginPin(http, request);
    }

    @PostMapping("/refresh")
    public LoginResponse refresh(@Valid @RequestBody RefreshRequest request, HttpServletRequest http) {
        return authService.refresh(http, request);
    }

    @PostMapping("/logout")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> logout(HttpServletRequest http) {
        authService.logout(CurrentTenantUser.require(http));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout-all")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> logoutAll(HttpServletRequest http) {
        authService.logoutAll(CurrentTenantUser.require(http));
        return ResponseEntity.noContent().build();
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
    public void passwordReset(@Valid @RequestBody PasswordResetRequest request) {
        authService.passwordReset(request);
    }

    @PostMapping("/password/change")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void passwordChange(@Valid @RequestBody PasswordChangeRequest request, HttpServletRequest http) {
        authService.passwordChange(http, CurrentTenantUser.require(http), request);
    }
}
