package zelisline.ub.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.api.dto.PasswordForgotRequest;
import zelisline.ub.identity.api.dto.RegisterRequest;
import zelisline.ub.identity.api.dto.RegisterResponse;
import zelisline.ub.identity.api.dto.VerifyEmailRequest;
import zelisline.ub.identity.domain.EmailVerificationToken;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.EmailVerificationTokenRepository;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Tenant-scoped self-service signup. When {@code app.auth.email-verification-required}
 * is true (default), new users are {@link UserStatus#INVITED} until verify-email;
 * otherwise they are {@link UserStatus#ACTIVE} immediately (local dev without SMTP).
 *
 * <p>The first user in a tenant (no other non-deleted users) receives the system
 * {@link IdentityService#OWNER_ROLE_KEY} role so an empty business has a clear
 * bootstrap path. Later self-signups use {@code app.auth.signup-role-key} — by default {@code buyer}
 * — unless a matching {@link RegisterRequest#staffInviteToken()} is supplied (staff-only link flow).
 */
@Service
@RequiredArgsConstructor
public class AuthRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(AuthRegistrationService.class);

    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final NotificationService notificationService;
    private final EmailVerificationEmailRenderer emailVerificationEmailRenderer;
    private final Environment environment;

    @Value("${app.auth.self-signup-enabled:true}")
    private boolean selfSignupEnabled;

    @Value("${app.auth.signup-role-key:buyer}")
    private String signupRoleKey;

    @Value("${app.auth.staff-signup-token:}")
    private String staffSignupTokenConfigured;

    @Value("${app.auth.staff-signup-role-key:viewer}")
    private String staffSignupRoleKey;

    @Value("${app.auth.email-verification-ttl-hours:48}")
    private long emailVerificationTtlHours;

    @Value("${app.public.email-verification-url-prefix:http://localhost:3000/verify-email?token=}")
    private String emailVerificationUrlPrefix;

    @Value("${app.auth.return-verification-link-in-register-response:false}")
    private boolean returnVerificationLinkInRegisterResponse;

    @Transactional
    public RegisterResponse register(HttpServletRequest http, RegisterRequest request) {
        log.info("[register] START selfSignupEnabled={} email={}", selfSignupEnabled, request.email());
        assertSignupEnabled();
        String businessId = TenantRequestIds.resolveBusinessId(http);
        log.info("[register] resolved businessId={}", businessId);
        if (!businessRepository.findByIdAndDeletedAtIsNull(businessId).isPresent()) {
            log.warn("[register] business not found: {}", businessId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found");
        }
        String email = normaliseEmail(request.email());
        log.info("[register] checking for existing user: businessId={} email={}", businessId, email);
        userRepository.findByBusinessIdAndEmailAndDeletedAtIsNull(businessId, email).ifPresent(u -> {
            log.warn("[register] duplicate email: businessId={} email={}", businessId, email);
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "An account with this email already exists for this business"
            );
        });
        log.info("[register] resolving signup role...");
        var role = resolveSignupRole(businessId, request);
        log.info("[register] resolved role={} key={}", role.getId(), role.getKey());
        User user = new User();
        user.setBusinessId(businessId);
        user.setEmail(email);
        user.setName(request.name().trim());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRoleId(role.getId());
        if (isEmailVerificationRequired()) {
            user.setStatus(UserStatus.INVITED);
            User saved = userRepository.save(user);
            String link = issueVerificationEmail(saved, http);
            return new RegisterResponse(
                    saved.getId(),
                    saved.getEmail(),
                    UserStatus.INVITED.wire(),
                    returnVerificationLinkInRegisterResponse ? link : null);
        }
        user.setStatus(UserStatus.ACTIVE);
        User saved = userRepository.save(user);
        return new RegisterResponse(saved.getId(), saved.getEmail(), UserStatus.ACTIVE.wire(), null);
    }

    @Transactional
    public void verifyEmail(VerifyEmailRequest request) {
        String hash = TokenHasher.sha256Hex(request.token());
        var row = emailVerificationTokenRepository.findByTokenHashAndUsedAtIsNull(hash)
                .orElseThrow(() -> invalidToken());
        if (row.getExpiresAt().isBefore(Instant.now())) {
            throw invalidToken();
        }
        User user = userRepository.findById(row.getUserId())
                .orElseThrow(() -> invalidToken());
        if (user.getDeletedAt() != null) {
            throw invalidToken();
        }
        if (user.statusAsEnum() != UserStatus.INVITED) {
            row.setUsedAt(Instant.now());
            emailVerificationTokenRepository.save(row);
            return;
        }
        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);
        row.setUsedAt(Instant.now());
        emailVerificationTokenRepository.save(row);
    }

    /**
     * Same anti-enumeration contract as before: missing/unknown/ineligible email → {@link Optional#empty()}.
     * When a new link is issued, returns it (caller may expose in JSON only when configured).
     */
    @Transactional
    public Optional<String> resendVerification(HttpServletRequest http, PasswordForgotRequest request) {
        if (!selfSignupEnabled) {
            log.info("[resend-verification] skipped: self-signup disabled");
            return Optional.empty();
        }
        if (request == null || request.email() == null || request.email().isBlank()) {
            log.info("[resend-verification] skipped: missing email in request body");
            return Optional.empty();
        }
        String businessId = TenantRequestIds.resolveBusinessId(http);
        String email = normaliseEmail(request.email());
        var found = userRepository.findByBusinessIdAndEmailAndDeletedAtIsNull(businessId, email);
        if (found.isEmpty()) {
            log.info("[resend-verification] skipped: no user for businessId={} email={}", businessId, email);
            return Optional.empty();
        }
        User user = found.get();
        if (user.statusAsEnum() != UserStatus.INVITED) {
            log.info("[resend-verification] skipped: user status={} (only INVITED users get re-sent) email={}",
                    user.statusAsEnum(), email);
            return Optional.empty();
        }
        if (user.getPasswordHash() == null) {
            log.info("[resend-verification] skipped: user has no passwordHash yet email={}", email);
            return Optional.empty();
        }
        return Optional.of(issueVerificationEmail(user, http));
    }

    private void assertSignupEnabled() {
        if (!selfSignupEnabled) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Self-service registration is disabled");
        }
    }

    private Role resolveSignupRole(String businessId, RegisterRequest request) {
        if (userRepository.countByBusinessIdAndDeletedAtIsNull(businessId) == 0) {
            return roleRepository
                    .findSystemRoleByKey(IdentityService.OWNER_ROLE_KEY)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "Signup is not available: owner role is not configured"
                    ));
        }
        String staffToken = blankToNull(request.staffInviteToken());
        if (staffToken != null) {
            if (staffSignupTokenConfigured.isBlank()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Staff self-signup is not enabled");
            }
            byte[] configured = staffSignupTokenConfigured.strip().getBytes(StandardCharsets.UTF_8);
            byte[] supplied = staffToken.strip().getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(configured, supplied)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid staff invitation");
            }
            String staffKey = staffSignupRoleKey.trim().toLowerCase();
            return roleRepository
                    .findSystemRoleByKey(staffKey)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "Signup is not available: staff role '" + staffKey + "' is not configured"
                    ));
        }
        String key = signupRoleKey.trim().toLowerCase();
        return roleRepository
                .findSystemRoleByKey(key)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Signup is not available: role '" + key + "' is not configured"
                ));
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }

    /** @return full verification URL (for optional UI exposure when mail is unavailable). */
    private String issueVerificationEmail(User user, HttpServletRequest http) {
        emailVerificationTokenRepository.deleteUnusedByUserId(user.getId());
        String raw = newRawToken();
        EmailVerificationToken token = new EmailVerificationToken();
        token.setUserId(user.getId());
        token.setTokenHash(TokenHasher.sha256Hex(raw));
        token.setExpiresAt(Instant.now().plus(emailVerificationTtlHours, ChronoUnit.HOURS));
        emailVerificationTokenRepository.save(token);
        String link = buildVerificationUrlPrefix(http) + raw;
        String body = emailVerificationEmailRenderer.renderBody(user.getEmail(), link);
        notificationService.sendEmailVerificationEmail(user.getEmail(), "Verify your UB account", body);
        return link;
    }

    /**
     * Builds the verification URL prefix ({scheme}://{host}/verify-email?token=) dynamically
     * from the incoming request so that subdomain tenants (e.g. barakia.palmart.co.ke)
     * receive links pointing to their own frontend origin, not the platform apex.
     *
     * <p>Priority:
     * <ol>
     *   <li>{@code X-Tenant-Host} header — sent by the frontend for tenant-aware routing</li>
     *   <li>{@code Host} header (when it differs from the configured platform hosts)</li>
     *   <li>Configured {@code emailVerificationUrlPrefix} (static fallback)</li>
     * </ol>
     *
     * <p>The scheme is read from {@code X-Forwarded-Proto} (for reverse-proxy deployments)
     * then from {@link HttpServletRequest#getScheme()}.
     */
    private String buildVerificationUrlPrefix(HttpServletRequest http) {
        // 1. Determine the frontend hostname
        String frontendHost = http.getHeader("X-Tenant-Host");
        if (frontendHost == null || frontendHost.isBlank()) {
            String serverName = http.getServerName();
            if (serverName != null && !serverName.isBlank()
                    && !"localhost".equalsIgnoreCase(serverName)
                    && !"127.0.0.1".equals(serverName)
                    && !"::1".equals(serverName)) {
                frontendHost = serverName;
            }
        }

        if (frontendHost == null || frontendHost.isBlank()) {
            // Ultimate fallback: use the statically configured prefix
            return emailVerificationUrlPrefix;
        }

        // 2. Determine the scheme (respect reverse-proxy headers)
        String scheme = http.getHeader("X-Forwarded-Proto");
        if (scheme == null || scheme.isBlank()) {
            scheme = http.getScheme();
        }

        // 3. Determine the port — *.localhost uses Next.js dev port 3000
        int port = http.getServerPort();
        if (frontendHost.endsWith(".localhost") || "localhost".equalsIgnoreCase(frontendHost)) {
            port = 3000;
        }
        boolean defaultPort = (port == 80 && "http".equals(scheme))
                || (port == 443 && "https".equals(scheme));

        StringBuilder prefix = new StringBuilder(scheme)
                .append("://")
                .append(frontendHost.trim());
        if (!defaultPort) {
            prefix.append(":").append(port);
        }
        prefix.append("/verify-email?token=");
        return prefix.toString();
    }

    private static String normaliseEmail(String email) {
        return email.trim().toLowerCase();
    }

    private static String newRawToken() {
        byte[] rnd = new byte[32];
        new SecureRandom().nextBytes(rnd);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(rnd);
    }

    private static ResponseStatusException invalidToken() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired verification token");
    }

    private boolean isEmailVerificationRequired() {
        return environment.getProperty("app.auth.email-verification-required", Boolean.class, Boolean.TRUE);
    }
}
