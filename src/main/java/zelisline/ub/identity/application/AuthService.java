package zelisline.ub.identity.application;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
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
import zelisline.ub.till.application.TillDeviceService;

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

    /**
     * Rotation-grace window for refresh-token reuse detection.
     *
     * <p>RFC 6819 §5.2.2.3 reuse detection cascades a revocation of every session
     * for the user when an already-rotated refresh token is presented a second
     * time. That is safe against an attacker but disastrous when the same client
     * legitimately fires two requests in parallel (page load issuing 4-6 API
     * calls, mobile browser retrying after a flaky network, WebSocket reauth
     * racing with a normal API request, two tabs both hitting the scheduled
     * proactive refresh, etc.).
     *
     * <p>The grace window covers exactly that case: if the presented refresh
     * token was rotated within the last {@value} seconds <em>and</em> its
     * successor session is still active, we treat the second request as a
     * benign duplicate and return {@code 401} <em>without</em> cascading the
     * revoke. The first caller already received a fresh access+refresh pair,
     * so the duplicate caller can simply read the new tokens from shared
     * storage (or get a fresh 401 + single-flight refresh) and continue.
     *
     * <p>True reuse (token stolen and replayed minutes/hours later) still
     * triggers the cascade because either (a) the time delta exceeds the
     * window, or (b) the successor has already been rotated again itself.
     */
    private static final long REFRESH_ROTATION_GRACE_SECONDS = 60;

    /** RFC 9457 {@code detail} when user status is {@link UserStatus#INVITED}. */
    public static final String LOGIN_EMAIL_NOT_VERIFIED_DETAIL =
            "Email not verified. Open the link we sent you or use resend verification, then try again.";

    /** Returned when a just-rotated refresh token is replayed inside the grace window. */
    public static final String REFRESH_ALREADY_ROTATED_TITLE = "Refresh token already rotated";

    /** Returned when {@code app.auth.idle-timeout-hours} of inactivity have elapsed. */
    public static final String SESSION_IDLE_EXPIRED_TITLE = "Session idle timeout expired";

    /**
     * Returned by {@link #unlockPin} when there is no usable refresh session
     * (client should fall back to full {@code login-pin}).
     */
    public static final String UNLOCK_NO_SESSION_DETAIL =
            "No active session to unlock. Sign in with PIN again.";

    private final UserRepository userRepository;
    private final UserSessionRepository userSessionRepository;
    private final UserSessionRevocation userSessionRevocation;
    private final UserSessionActivity userSessionActivity;
    private final EntityManager entityManager;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final PasswordResetEmailRenderer passwordResetEmailRenderer;
    private final NotificationService notificationService;
    private final FrontendAuthLinkBuilder frontendAuthLinkBuilder;
    private final RefreshTokenCookieSupport refreshTokenCookieSupport;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;
    private final TillDeviceService tillDeviceService;

    @Value("${app.jwt.access-ttl-minutes:60}")
    private long accessTtlMinutes;

    @Value("${app.jwt.refresh-ttl-days:30}")
    private long refreshTtlDays;

    @Value("${app.auth.password-reset-ttl-hours:1}")
    private long passwordResetTtlHours;

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
            recordLoginFailure(user, http, AuditEventTypes.LOGIN_FAILED, "Incorrect password");
            throw invalidCredentials();
        }
        recordLoginSuccess(user);
        LoginResponse response = issueNewSessionWithSession(user, http).tokens();
        publishLoginEvent(user, http, response, AuditEventTypes.LOGIN_SUCCEEDED, null, null, "password");
        return response;
    }

    @Transactional
    public LoginResponse loginPin(HttpServletRequest http, LoginPinRequest request) {
        String businessId = TenantRequestIds.resolveBusinessId(http);
        String email = request.email().trim().toLowerCase();
        String tillDeviceKey = trimToNull(http.getHeader(TillDeviceService.TILL_DEVICE_HEADER));
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
            recordLoginFailure(user, http, AuditEventTypes.LOGIN_FAILED, "Incorrect PIN");
            throw invalidCredentials();
        }
        try {
            tillDeviceService.assertPinLoginAllowed(businessId, request.branchId(), tillDeviceKey);
        } catch (ResponseStatusException ex) {
            publishPinLoginDenied(user, http, tillDeviceKey, ex.getReason());
            throw ex;
        }
        recordLoginSuccess(user);
        LoginResponse response = issueNewSessionWithSession(user, http).tokens();
        publishLoginEvent(user, http, response, AuditEventTypes.LOGIN_SUCCEEDED, null, tillDeviceKey, "pin");
        return response;
    }

    /**
     * Same-cashier till unlock: verify PIN against the user on the current refresh
     * session and reissue an access JWT <em>without</em> rotating the refresh token.
     *
     * <p>Requires a valid refresh cookie/body. Callers should fall back to
     * {@link #loginPin} when this returns {@link #UNLOCK_NO_SESSION_DETAIL} or idle expiry.
     */
    @Transactional
    public LoginResponse unlockPin(HttpServletRequest http, LoginPinRequest request) {
        String refreshRaw = resolveRefreshToken(http, new RefreshRequest(null));
        if (refreshRaw == null || refreshRaw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, UNLOCK_NO_SESSION_DETAIL);
        }

        String businessId = TenantRequestIds.resolveBusinessId(http);
        String tillDeviceKey = trimToNull(http.getHeader(TillDeviceService.TILL_DEVICE_HEADER));
        String hash = TokenHasher.sha256Hex(refreshRaw);

        UserSession session = userSessionRepository.findByRefreshTokenHash(hash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, UNLOCK_NO_SESSION_DETAIL));
        if (!session.getBusinessId().equals(businessId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, UNLOCK_NO_SESSION_DETAIL);
        }
        assertRefreshSessionValid(session);

        User user = userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(session.getUserId(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, UNLOCK_NO_SESSION_DETAIL));

        String email = request.email().trim().toLowerCase();
        if (!email.equalsIgnoreCase(user.getEmail())) {
            // Switch-cashier must use full login-pin (different user / new session).
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, UNLOCK_NO_SESSION_DETAIL);
        }
        if (user.getPinHash() == null) {
            throw invalidCredentials();
        }
        if (user.getBranchId() == null || !user.getBranchId().equals(request.branchId())) {
            throw invalidCredentials();
        }
        assertCanAuthenticate(user);

        String pinPayload = businessId + ":" + request.pin();
        if (!passwordEncoder.matches(pinPayload, user.getPinHash())) {
            recordLoginFailure(user, http, AuditEventTypes.LOGIN_FAILED, "Incorrect PIN");
            throw invalidCredentials();
        }
        try {
            tillDeviceService.assertPinLoginAllowed(businessId, request.branchId(), tillDeviceKey);
        } catch (ResponseStatusException ex) {
            publishPinLoginDenied(user, http, tillDeviceKey, ex.getReason());
            throw ex;
        }

        recordLoginSuccess(user);
        LoginResponse response = reissueAccessOnSession(session, user, http);
        publishLoginEvent(user, http, response, AuditEventTypes.LOGIN_SUCCEEDED, null, tillDeviceKey, "pin_unlock");
        return response;
    }

    @Transactional
    public LoginResponse refresh(HttpServletRequest http, RefreshRequest request) {
        String refreshRaw = resolveRefreshToken(http, request);
        if (refreshRaw == null || refreshRaw.isBlank()) {
            throw invalidCredentials();
        }
        String businessId = TenantRequestIds.resolveBusinessId(http);
        String hash = TokenHasher.sha256Hex(refreshRaw);

        // Read without row lock first so reuse revocation cannot deadlock with
        // another refresh holding FOR UPDATE on a sibling session row.
        UserSession peek = userSessionRepository.findByRefreshTokenHash(hash)
                .orElseThrow(this::invalidCredentials);

        if (!peek.getBusinessId().equals(businessId)) {
            throw invalidCredentials();
        }

        assertRefreshSessionValid(peek);

        UserSession old = userSessionRepository.findByRefreshTokenHashForUpdate(hash)
                .orElseThrow(this::invalidCredentials);
        entityManager.refresh(old);

        if (!old.getBusinessId().equals(businessId)) {
            throw invalidCredentials();
        }

        assertRefreshSessionValid(old);

        User user = userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(old.getUserId(), old.getBusinessId())
                .orElseThrow(this::invalidCredentials);
        assertCanAuthenticate(user);

        SessionBundle neu = issueNewSessionWithSession(user, http);
        old.setRevokedAt(Instant.now());
        old.setRotatedToId(neu.session().getId());
        userSessionRepository.save(old);

        return neu.tokens();
    }

    /**
     * Validates that a refresh token's session row can still mint a new access token.
     * Throws {@link #REFRESH_ALREADY_ROTATED_TITLE} inside the rotation grace window
     * (benign duplicate) instead of cascading a family-wide revocation.
     */
    private void assertRefreshSessionValid(UserSession session) {
        if (session.getRevokedAt() != null) {
            if (isWithinRotationGrace(session)) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, REFRESH_ALREADY_ROTATED_TITLE);
            }
            userSessionRevocation.revokeAllActiveForUserNow(session.getUserId());
            throw invalidCredentials();
        }
        if (session.getRefreshExpiresAt().isBefore(Instant.now())) {
            session.setRevokedAt(Instant.now());
            userSessionRepository.save(session);
            throw invalidCredentials();
        }
        if (userSessionActivity.revokeIfIdle(session)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, SESSION_IDLE_EXPIRED_TITLE);
        }
    }

    /**
     * Returns true when the just-presented (already-revoked) refresh token was
     * rotated within the grace window <em>and</em> its successor session is
     * still active. See {@link #REFRESH_ROTATION_GRACE_SECONDS}.
     */
    private boolean isWithinRotationGrace(UserSession revoked) {
        Instant revokedAt = revoked.getRevokedAt();
        String successorId = revoked.getRotatedToId();
        if (revokedAt == null || successorId == null || successorId.isBlank()) {
            return false;
        }
        if (revokedAt.plusSeconds(REFRESH_ROTATION_GRACE_SECONDS).isBefore(Instant.now())) {
            return false;
        }
        return userSessionRepository.findById(successorId)
                .map(successor -> successor.getRevokedAt() == null)
                .orElse(false);
    }

    @Transactional
    public void logout(TenantPrincipal principal, HttpServletRequest http) {
        if (principal.accessJti() == null) {
            return;
        }
        userSessionRepository.findByAccessTokenJtiAndRevokedAtIsNull(principal.accessJti())
                .ifPresent(session -> {
                    session.setRevokedAt(Instant.now());
                    userSessionRepository.save(session);
                });
        auditEventPublisher.publish(auditEventBuilder.builder(AuditEventCategory.SECURITY, AuditEventTypes.LOGOUT, AuditEventSeverity.INFO)
                .businessId(principal.businessId())
                .branchId(principal.branchId())
                .actor(principal.userId(), AuditEventActorType.USER)
                .sessionId(principal.accessJti())
                .ipAddress(clientIp(http))
                .userAgent(trimToNull(http.getHeader("User-Agent")))
                .source("web_admin")
                .build());
    }

    @Transactional
    public void logoutAll(TenantPrincipal principal, HttpServletRequest http) {
        userSessionRepository.revokeAllActiveForUser(principal.userId(), Instant.now());
        auditEventPublisher.publish(auditEventBuilder.builder(AuditEventCategory.SECURITY, AuditEventTypes.LOGOUT_ALL, AuditEventSeverity.INFO)
                .businessId(principal.businessId())
                .branchId(principal.branchId())
                .actor(principal.userId(), AuditEventActorType.USER)
                .ipAddress(clientIp(http))
                .userAgent(trimToNull(http.getHeader("User-Agent")))
                .source("web_admin")
                .build());
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
            String link = frontendAuthLinkBuilder.passwordResetLink(http, businessId, raw);
            String body = passwordResetEmailRenderer.renderBody(user.getEmail(), link);
            notificationService.sendPasswordResetEmail(user.getEmail(), "Reset your UB password", body);
            auditEventPublisher.publish(auditEventBuilder.builder(AuditEventCategory.SECURITY, AuditEventTypes.PASSWORD_RESET_REQUESTED, AuditEventSeverity.INFO)
                    .businessId(businessId)
                    .branchId(user.getBranchId())
                    .actor(user.getId(), AuditEventActorType.USER)
                    .actorName(user.getEmail())
                    .target("user", user.getId())
                    .targetLabel(user.getEmail())
                    .ipAddress(clientIp(http))
                    .userAgent(trimToNull(http.getHeader("User-Agent")))
                    .source("web_admin")
                    .build());
        });
    }

    @Transactional
    public void passwordReset(HttpServletRequest http, PasswordResetRequest request) {
        String businessId = TenantRequestIds.resolveBusinessId(http);
        String hash = TokenHasher.sha256Hex(request.token());
        PasswordResetToken row = passwordResetTokenRepository.findByTokenHashAndUsedAtIsNull(hash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired token"));
        if (row.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired token");
        }
        User user = userRepository.findById(row.getUserId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired token"));
        if (user.getDeletedAt() != null || !user.getBusinessId().equals(businessId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired token");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        // Admin-invited users land here via their invitation link with no
        // password set yet; accepting the invite (setting a password) also
        // activates the account so they can sign in.
        if (user.statusAsEnum() == UserStatus.INVITED) {
            user.setStatus(UserStatus.ACTIVE);
        }
        userRepository.save(user);
        row.setUsedAt(Instant.now());
        passwordResetTokenRepository.save(row);
        userSessionRepository.revokeAllActiveForUser(user.getId(), Instant.now());
        auditEventPublisher.publish(auditEventBuilder.builder(AuditEventCategory.SECURITY, AuditEventTypes.PASSWORD_RESET_USED, AuditEventSeverity.INFO)
                .businessId(businessId)
                .branchId(user.getBranchId())
                .actor(user.getId(), AuditEventActorType.USER)
                .actorName(user.getEmail())
                .target("user", user.getId())
                .targetLabel(user.getEmail())
                .ipAddress(clientIp(http))
                .userAgent(trimToNull(http.getHeader("User-Agent")))
                .source("web_admin")
                .build());
    }

    @Transactional
    public void passwordChange(HttpServletRequest http, TenantPrincipal principal, PasswordChangeRequest request) {
        User user = userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(principal.userId(), principal.businessId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated"));
        TenantRequestIds.requireMatchingTenant(http, principal.businessId());
        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Current password is incorrect.");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        userSessionRepository.revokeAllActiveForUser(user.getId(), Instant.now());
        auditEventPublisher.publish(auditEventBuilder.builder(AuditEventCategory.SECURITY, AuditEventTypes.PASSWORD_CHANGED, AuditEventSeverity.INFO)
                .businessId(principal.businessId())
                .branchId(principal.branchId())
                .actor(principal.userId(), AuditEventActorType.USER)
                .target("user", user.getId())
                .targetLabel(user.getEmail())
                .ipAddress(clientIp(http))
                .userAgent(trimToNull(http.getHeader("User-Agent")))
                .source("web_admin")
                .build());
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
        session.setLastSeenAt(now);
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

    /** Mint a new access JWT on an existing session row; leave refresh token unchanged. */
    private LoginResponse reissueAccessOnSession(UserSession session, User user, HttpServletRequest http) {
        String jti = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant accessExp = now.plus(accessTtlMinutes, ChronoUnit.MINUTES);
        session.setAccessTokenJti(jti);
        session.setExpiresAt(accessExp);
        session.setLastSeenAt(now);
        String ua = trimToNull(http.getHeader("User-Agent"));
        if (ua != null) {
            session.setUserAgent(ua);
        }
        String ip = clientIp(http);
        if (ip != null) {
            session.setIp(ip);
        }
        userSessionRepository.save(session);

        String access = jwtTokenService.createAccessToken(
                user.getId(),
                user.getBusinessId(),
                user.getRoleId(),
                user.getBranchId(),
                jti
        );
        return new LoginResponse(access, null, toAuthUser(user));
    }

    private void recordLoginFailure(User user, HttpServletRequest http, String eventType, String reason) {
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

        boolean softLocked = user.getFailedAttempts() >= FAILED_ATTEMPTS_SOFT_LOCK;
        boolean hardLocked = user.getAuthHourFailures() >= HARD_LOCK_FAILURES;
        if (softLocked) {
            user.setLockedUntil(now.plus(SOFT_LOCK_MINUTES, ChronoUnit.MINUTES));
        }
        if (user.getFailedAttempts() == FAILED_ATTEMPTS_SOFT_LOCK) {
            notificationService.sendTemporaryLockNotice(user.getEmail());
            publishSecurityEventSync(user, http, AuditEventTypes.ACCOUNT_LOCKED_SOFT, "Too many failed login attempts");
        }
        if (hardLocked) {
            user.setStatus(UserStatus.LOCKED);
            publishSecurityEventSync(user, http, AuditEventTypes.ACCOUNT_LOCKED_HARD, "Too many failed login attempts in one hour");
        }
        userRepository.save(user);
        publishSecurityEventSync(user, http, eventType, reason);
    }

    private void publishLoginEvent(
            User user,
            HttpServletRequest http,
            LoginResponse response,
            String eventType,
            String reason,
            String tillDeviceKey,
            String authMethod
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("authMethod", authMethod);
        if (tillDeviceKey != null && !tillDeviceKey.isBlank()) {
            metadata.put("tillDeviceKey", tillDeviceKey);
        }
        auditEventPublisher.publish(auditEventBuilder.builder(AuditEventCategory.SECURITY, eventType,
                        AuditEventTypes.LOGIN_SUCCEEDED.equals(eventType) ? AuditEventSeverity.INFO : AuditEventSeverity.WARN)
                .businessId(user.getBusinessId())
                .branchId(user.getBranchId())
                .actor(user.getId(), AuditEventActorType.USER)
                .actorName(user.getEmail())
                .target("user", user.getId())
                .targetLabel(user.getEmail())
                .ipAddress(clientIp(http))
                .userAgent(trimToNull(http.getHeader("User-Agent")))
                .source("web_admin")
                .terminalId(tillDeviceKey)
                .reason(reason)
                .metadata(metadata)
                .build());
    }

    private void publishPinLoginDenied(User user, HttpServletRequest http, String tillDeviceKey, String reason) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("authMethod", "pin");
        metadata.put("denied", "till_not_registered");
        if (tillDeviceKey != null && !tillDeviceKey.isBlank()) {
            metadata.put("tillDeviceKey", tillDeviceKey);
        }
        auditEventPublisher.publishSynchronous(auditEventBuilder.builder(
                        AuditEventCategory.SECURITY, AuditEventTypes.LOGIN_FAILED, AuditEventSeverity.WARN)
                .businessId(user.getBusinessId())
                .branchId(user.getBranchId())
                .actor(user.getId(), AuditEventActorType.USER)
                .actorName(user.getEmail())
                .target("user", user.getId())
                .targetLabel(user.getEmail())
                .ipAddress(clientIp(http))
                .userAgent(trimToNull(http.getHeader("User-Agent")))
                .source("web_admin")
                .terminalId(tillDeviceKey)
                .reason(reason != null ? reason : TillDeviceService.TILL_DEVICE_NOT_REGISTERED_DETAIL)
                .metadata(metadata)
                .build());
    }

    private void publishSecurityEventSync(User user, HttpServletRequest http, String eventType, String reason) {
        String tillDeviceKey = trimToNull(http.getHeader(TillDeviceService.TILL_DEVICE_HEADER));
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (tillDeviceKey != null) {
            metadata.put("tillDeviceKey", tillDeviceKey);
        }
        auditEventPublisher.publishSynchronous(auditEventBuilder.builder(AuditEventCategory.SECURITY, eventType, AuditEventSeverity.WARN)
                .businessId(user.getBusinessId())
                .branchId(user.getBranchId())
                .actor(user.getId(), AuditEventActorType.USER)
                .actorName(user.getEmail())
                .target("user", user.getId())
                .targetLabel(user.getEmail())
                .ipAddress(clientIp(http))
                .userAgent(trimToNull(http.getHeader("User-Agent")))
                .source("web_admin")
                .terminalId(tillDeviceKey)
                .reason(reason)
                .metadata(metadata.isEmpty() ? null : metadata)
                .build());
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
        if (user.statusAsEnum() == UserStatus.INVITED) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, LOGIN_EMAIL_NOT_VERIFIED_DETAIL);
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
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Incorrect email or password.");
    }

    private String resolveRefreshToken(HttpServletRequest http, RefreshRequest request) {
        Optional<String> fromCookie = refreshTokenCookieSupport.read(http);
        if (fromCookie.isPresent()) {
            return fromCookie.get();
        }
        if (request != null && request.refreshToken() != null && !request.refreshToken().isBlank()) {
            return request.refreshToken().trim();
        }
        return null;
    }

    private record SessionBundle(LoginResponse tokens, UserSession session) {
    }
}
