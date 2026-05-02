package zelisline.ub.identity.application;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.api.dto.AuthUserResponse;
import zelisline.ub.identity.api.dto.LoginPinRequest;
import zelisline.ub.identity.api.dto.LoginRequest;
import zelisline.ub.identity.api.dto.LoginResponse;
import zelisline.ub.identity.api.dto.PasswordChangeRequest;
import zelisline.ub.identity.api.dto.PasswordForgotRequest;
import zelisline.ub.identity.api.dto.PasswordResetRequest;
import zelisline.ub.identity.api.dto.RefreshRequest;
import zelisline.ub.identity.domain.PasswordResetToken;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserSession;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.PasswordResetTokenRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.identity.repository.UserSessionRepository;
import zelisline.ub.platform.security.JwtTokenService;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.tenancy.api.TenantRequestIds;

/**
 * Slice 3 auth use-cases (PHASE_1_PLAN.md §3.3–3.4): login, PIN, refresh rotation,
 * password flows, logout, time-window lockout.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int FAILED_ATTEMPTS_SOFT_LOCK = 5;
    private static final int SOFT_LOCK_MINUTES = 15;
    private static final int SOFT_WINDOW_MINUTES = 10;
    private static final int HARD_WINDOW_MINUTES = 60;
    private static final int HARD_LOCK_FAILURES = 10;

    private final UserRepository userRepository;
    private final UserSessionRepository userSessionRepository;
    private final UserSessionRevocation userSessionRevocation;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final PasswordResetEmailRenderer passwordResetEmailRenderer;
    private final NotificationService notificationService;

    @Value("${app.jwt.access-ttl-minutes:15}")
    private long accessTtlMinutes;

    @Value("${app.jwt.refresh-ttl-days:30}")
    private long refreshTtlDays;

    @Value("${app.auth.password-reset-ttl-hours:1}")
    private long passwordResetTtlHours;

    @Value("${app.public.password-reset-url-prefix:http://localhost:5173/reset-password?token=}")
    private String passwordResetUrlPrefix;

    @Transactional
    public LoginResponse login(HttpServletRequest http, LoginRequest request) {
        String businessId = TenantRequestIds.resolveBusinessId(http);
        String email = request.email().trim().toLowerCase();
        User user = userRepository.findByBusinessIdAndEmailAndDeletedAtIsNull(businessId, email)
                .orElseThrow(this::invalidCredentials);
        if (user.getPasswordHash() == null) {
            throw invalidCredentials();
        }
        assertCanAuthenticate(user);
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            recordLoginFailure(user);
            throw invalidCredentials();
        }
        recordLoginSuccess(user);
        return issueNewSession(user, http);
    }

    @Transactional
    public LoginResponse loginPin(HttpServletRequest http, LoginPinRequest request) {
        String businessId = TenantRequestIds.resolveBusinessId(http);
        String email = request.email().trim().toLowerCase();
        User user = userRepository.findByBusinessIdAndEmailAndDeletedAtIsNull(businessId, email)
                .orElseThrow(this::invalidCredentials);
        if (user.getPinHash() == null) {
            throw invalidCredentials();
        }
        if (user.getBranchId() == null || !user.getBranchId().equals(request.branchId())) {
            throw invalidCredentials();
        }
        assertCanAuthenticate(user);
        String pinPayload = businessId + ":" + request.pin();
        if (!passwordEncoder.matches(pinPayload, user.getPinHash())) {
            recordLoginFailure(user);
            throw invalidCredentials();
        }
        recordLoginSuccess(user);
        return issueNewSession(user, http);
    }

    @Transactional
    public LoginResponse refresh(HttpServletRequest http, RefreshRequest request) {
        String businessId = TenantRequestIds.resolveBusinessId(http);
        String hash = TokenHasher.sha256Hex(request.refreshToken());
        UserSession old = userSessionRepository.findByRefreshTokenHashForUpdate(hash)
                .orElseThrow(this::invalidCredentials);

        if (!old.getBusinessId().equals(businessId)) {
            throw invalidCredentials();
        }

        if (old.getRevokedAt() != null) {
            userSessionRevocation.revokeAllActiveForUserNow(old.getUserId());
            throw invalidCredentials();
        }
        if (old.getRefreshExpiresAt().isBefore(Instant.now())) {
            old.setRevokedAt(Instant.now());
            userSessionRepository.save(old);
            throw invalidCredentials();
        }

        User user = userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(old.getUserId(), old.getBusinessId())
                .orElseThrow(this::invalidCredentials);
        assertCanAuthenticate(user);

        SessionBundle neu = issueNewSessionWithSession(user, http);
        old.setRevokedAt(Instant.now());
        old.setRotatedToId(neu.session().getId());
        userSessionRepository.save(old);

        return neu.tokens();
    }

    @Transactional
    public void logout(TenantPrincipal principal) {
        if (principal.accessJti() == null) {
            return;
        }
        userSessionRepository.findByAccessTokenJtiAndRevokedAtIsNull(principal.accessJti())
                .ifPresent(session -> {
                    session.setRevokedAt(Instant.now());
                    userSessionRepository.save(session);
                });
    }

    @Transactional
    public void logoutAll(TenantPrincipal principal) {
        userSessionRepository.revokeAllActiveForUser(principal.userId(), Instant.now());
    }

    /** Always {@code 204} — no tenant user enumeration (§3.3). */
    @Transactional
    public void passwordForgot(HttpServletRequest http, PasswordForgotRequest request) {
        String businessId = TenantRequestIds.resolveBusinessId(http);
        if (request == null || request.email() == null || request.email().isBlank()) {
            return;
        }
        String email = request.email().trim().toLowerCase();
        userRepository.findByBusinessIdAndEmailAndDeletedAtIsNull(businessId, email).ifPresent(user -> {
            if (user.getPasswordHash() == null) {
                return;
            }
            String raw = newSecureRefresh();
            String tokenHash = TokenHasher.sha256Hex(raw);
            Instant now = Instant.now();
            PasswordResetToken entity = new PasswordResetToken();
            entity.setUserId(user.getId());
            entity.setTokenHash(tokenHash);
            entity.setExpiresAt(now.plus(passwordResetTtlHours, ChronoUnit.HOURS));
            passwordResetTokenRepository.save(entity);
            String link = passwordResetUrlPrefix + raw;
            String body = passwordResetEmailRenderer.renderBody(user.getEmail(), link);
            notificationService.sendPasswordResetEmail(user.getEmail(), "Reset your UB password", body);
        });
    }

    @Transactional
    public void passwordReset(PasswordResetRequest request) {
        String hash = TokenHasher.sha256Hex(request.token());
        PasswordResetToken row = passwordResetTokenRepository.findByTokenHashAndUsedAtIsNull(hash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired token"));
        if (row.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired token");
        }
        User user = userRepository.findById(row.getUserId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired token"));
        if (user.getDeletedAt() != null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired token");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        row.setUsedAt(Instant.now());
        passwordResetTokenRepository.save(row);
        userSessionRepository.revokeAllActiveForUser(user.getId(), Instant.now());
    }

    @Transactional
    public void passwordChange(HttpServletRequest http, TenantPrincipal principal, PasswordChangeRequest request) {
        User user = userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(principal.userId(), principal.businessId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated"));
        TenantRequestIds.requireMatchingTenant(http, principal.businessId());
        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        userSessionRepository.revokeAllActiveForUser(user.getId(), Instant.now());
    }

    private LoginResponse issueNewSession(User user, HttpServletRequest http) {
        return issueNewSessionWithSession(user, http).tokens();
    }

    private SessionBundle issueNewSessionWithSession(User user, HttpServletRequest http) {
        String jti = UUID.randomUUID().toString();
        String refreshRaw = newSecureRefresh();
        String refreshHash = TokenHasher.sha256Hex(refreshRaw);
        Instant now = Instant.now();
        Instant accessExp = now.plus(accessTtlMinutes, ChronoUnit.MINUTES);
        Instant refreshExp = now.plus(refreshTtlDays, ChronoUnit.DAYS);

        UserSession session = new UserSession();
        session.setUserId(user.getId());
        session.setBusinessId(user.getBusinessId());
        session.setAccessTokenJti(jti);
        session.setRefreshTokenHash(refreshHash);
        session.setUserAgent(trimToNull(http.getHeader("User-Agent")));
        session.setIp(clientIp(http));
        session.setExpiresAt(accessExp);
        session.setRefreshExpiresAt(refreshExp);
        userSessionRepository.save(session);

        String access = jwtTokenService.createAccessToken(
                user.getId(),
                user.getBusinessId(),
                user.getRoleId(),
                user.getBranchId(),
                jti
        );
        LoginResponse response = new LoginResponse(access, refreshRaw, toAuthUser(user));
        return new SessionBundle(response, session);
    }

    private void recordLoginFailure(User user) {
        Instant now = Instant.now();

        if (user.getAuthSoftWindowStart() == null
                || user.getAuthSoftWindowStart().plus(SOFT_WINDOW_MINUTES, ChronoUnit.MINUTES).isBefore(now)) {
            user.setAuthSoftWindowStart(now);
            user.setFailedAttempts(1);
        } else {
            user.setFailedAttempts(user.getFailedAttempts() + 1);
        }

        if (user.getAuthHourWindowStart() == null
                || user.getAuthHourWindowStart().plus(HARD_WINDOW_MINUTES, ChronoUnit.MINUTES).isBefore(now)) {
            user.setAuthHourWindowStart(now);
            user.setAuthHourFailures(1);
        } else {
            user.setAuthHourFailures(user.getAuthHourFailures() + 1);
        }

        if (user.getFailedAttempts() >= FAILED_ATTEMPTS_SOFT_LOCK) {
            user.setLockedUntil(now.plus(SOFT_LOCK_MINUTES, ChronoUnit.MINUTES));
        }
        if (user.getFailedAttempts() == FAILED_ATTEMPTS_SOFT_LOCK) {
            notificationService.sendTemporaryLockNotice(user.getEmail());
        }
        if (user.getAuthHourFailures() >= HARD_LOCK_FAILURES) {
            user.setStatus(UserStatus.LOCKED);
        }
        userRepository.save(user);
    }

    private void recordLoginSuccess(User user) {
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        user.setAuthSoftWindowStart(null);
        user.setAuthHourWindowStart(null);
        user.setAuthHourFailures(0);
        user.setLastLoginAt(Instant.now());
        userRepository.save(user);
    }

    private void assertCanAuthenticate(User user) {
        if (user.getDeletedAt() != null) {
            throw invalidCredentials();
        }
        if (user.statusAsEnum() != UserStatus.ACTIVE) {
            throw invalidCredentials();
        }
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now())) {
            throw invalidCredentials();
        }
    }

    private AuthUserResponse toAuthUser(User user) {
        return new AuthUserResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getBusinessId(),
                user.getBranchId(),
                user.getRoleId(),
                user.getStatus()
        );
    }

    private static String newSecureRefresh() {
        byte[] rnd = new byte[32];
        new SecureRandom().nextBytes(rnd);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(rnd);
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
    }

    private record SessionBundle(LoginResponse tokens, UserSession session) {
    }
}
