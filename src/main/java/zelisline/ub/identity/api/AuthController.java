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
import zelisline.ub.identity.api.dto.EmailLookupRequest;
import zelisline.ub.identity.api.dto.EmailLookupResponse;
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
    private final zelisline.ub.marketplace.application.SupplierShopSignInService supplierShopSignInService;
    private final AuthRegistrationService authRegistrationService;
    private final LoginBranchDirectoryService loginBranchDirectoryService;
    private final RefreshTokenCookieSupport refreshTokenCookieSupport;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@Valid @RequestBody RegisterRequest body, HttpServletRequest http) {
        return authRegistrationService.register(http, body);
    }

    @PostMapping("/email-lookup")
    public EmailLookupResponse emailLookup(
            @Valid @RequestBody EmailLookupRequest body,
            HttpServletRequest http
    ) {
        return authRegistrationService.lookupEmail(http, body);
    }

    @PostMapping("/verify-email")
    public ResponseEntity<LoginResponse> verifyEmail(
            @Valid @RequestBody VerifyEmailRequest body,
            HttpServletRequest http
    ) {
        return toSessionResponse(authRegistrationService.verifyEmail(http, body));
    }

    /**
     * Same anti-enumeration contract as {@link #passwordForgot}: missing/unknown email → {@code 204}.
     * When a new token is issued and the deployment would expose the link on register
     * (explicit flag or no mail provider), returns {@code 200} with
     * {@link ResendVerificationLinkResponse} so the UI can show the link without mail.
     */
    @PostMapping("/resend-verification")
    public ResponseEntity<?> resendVerification(HttpServletRequest http, @RequestBody(required = false) PasswordForgotRequest body) {
        var link = authRegistrationService.resendVerification(http, body);
        if (authRegistrationService.shouldExposeVerificationLinkInApiResponse()) {
            return link.<ResponseEntity<?>>map(url -> ResponseEntity.ok(new ResendVerificationLinkResponse(url)))
                    .orElse(ResponseEntity.noContent().build());
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        try {
            return toSessionResponse(authService.login(http, request));
        } catch (org.springframework.web.server.ResponseStatusException refused) {
            // A supplier's portal team may use their portal credentials on their own shop (P1-3). This is tried
            // only after the normal login has refused, and any problem with it reports the original refusal.
            int code = refused.getStatusCode().value();
            if (code != 401 && code != 403) {
                throw refused;
            }
            java.util.Optional<LoginResponse> viaPortal;
            try {
                viaPortal = supplierShopSignInService.signInToShop(http, request.email(), request.password());
            } catch (RuntimeException ignored) {
                throw refused;
            }
            if (viaPortal.isEmpty()) {
                throw refused;
            }
            return toSessionResponse(viaPortal.get());
        }
    }

    /**
     * A supplier's portal team signing in to their own shop with their portal details: email or phone number, with
     * the portal password or PIN. The normal login takes an email only, so a phone number comes through here.
     */
    @PostMapping("/login-supplier")
    public ResponseEntity<LoginResponse> loginSupplier(
            @Valid @RequestBody zelisline.ub.marketplace.api.dto.SupplierPortalLoginRequest request,
            HttpServletRequest http
    ) {
        return toSessionResponse(supplierShopSignInService
                .signInToShop(http, request.identifier(), request.password())
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED, "Incorrect phone/email or PIN/password.")));
    }

    @PostMapping("/login-pin")
    public ResponseEntity<LoginResponse> loginPin(@Valid @RequestBody LoginPinRequest request, HttpServletRequest http) {
        return toSessionResponse(authService.loginPin(http, request));
    }

    /**
     * Same-cashier till unlock: PIN against the current refresh session, new access
     * JWT only (no refresh rotation). Fall back to {@code /login-pin} when no session.
     */
    @PostMapping("/unlock-pin")
    public ResponseEntity<LoginResponse> unlockPin(@Valid @RequestBody LoginPinRequest request, HttpServletRequest http) {
        return toSessionResponse(authService.unlockPin(http, request));
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
        authService.logout(CurrentTenantUser.requireHuman(http), http);
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
        authService.logoutAll(CurrentTenantUser.requireHuman(http), http);
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
        LoginResponse body = new LoginResponse(
                tokens.accessToken(), null, tokens.user(), tokens.billing());
        return ResponseEntity.ok()
                .headers(refreshTokenCookieSupport.cookieHeaders(tokens.refreshToken()))
                .body(body);
    }
}
