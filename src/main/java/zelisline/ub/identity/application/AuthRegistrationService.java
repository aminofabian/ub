package zelisline.ub.identity.application;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

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
 * bootstrap path. Later self-signups use {@code app.auth.signup-role-key}.
 */
@Service
@RequiredArgsConstructor
public class AuthRegistrationService {

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

    @Value("${app.auth.signup-role-key:viewer}")
    private String signupRoleKey;

    @Value("${app.auth.email-verification-ttl-hours:48}")
    private long emailVerificationTtlHours;

    @Value("${app.public.email-verification-url-prefix:http://localhost:3000/verify-email?token=}")
    private String emailVerificationUrlPrefix;

    @Transactional
    public RegisterResponse register(HttpServletRequest http, RegisterRequest request) {
        assertSignupEnabled();
        String businessId = TenantRequestIds.resolveBusinessId(http);
        if (!businessRepository.findByIdAndDeletedAtIsNull(businessId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found");
        }
        String email = normaliseEmail(request.email());
        userRepository.findByBusinessIdAndEmailAndDeletedAtIsNull(businessId, email).ifPresent(u -> {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "An account with this email already exists for this business"
            );
        });
        var role = resolveSignupRole(businessId);
        User user = new User();
        user.setBusinessId(businessId);
        user.setEmail(email);
        user.setName(request.name().trim());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRoleId(role.getId());
        if (isEmailVerificationRequired()) {
            user.setStatus(UserStatus.INVITED);
            User saved = userRepository.save(user);
            issueVerificationEmail(saved);
            return new RegisterResponse(saved.getId(), saved.getEmail(), UserStatus.INVITED.wire());
        }
        user.setStatus(UserStatus.ACTIVE);
        User saved = userRepository.save(user);
        return new RegisterResponse(saved.getId(), saved.getEmail(), UserStatus.ACTIVE.wire());
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

    /** Always completes with {@code 204} when email is missing; no user enumeration. */
    @Transactional
    public void resendVerification(HttpServletRequest http, PasswordForgotRequest request) {
        if (!selfSignupEnabled) {
            return;
        }
        if (request == null || request.email() == null || request.email().isBlank()) {
            return;
        }
        String businessId = TenantRequestIds.resolveBusinessId(http);
        String email = normaliseEmail(request.email());
        userRepository.findByBusinessIdAndEmailAndDeletedAtIsNull(businessId, email).ifPresent(user -> {
            if (user.statusAsEnum() != UserStatus.INVITED || user.getPasswordHash() == null) {
                return;
            }
            issueVerificationEmail(user);
        });
    }

    private void assertSignupEnabled() {
        if (!selfSignupEnabled) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Self-service registration is disabled");
        }
    }

    private Role resolveSignupRole(String businessId) {
        if (userRepository.countByBusinessIdAndDeletedAtIsNull(businessId) == 0) {
            return roleRepository
                    .findSystemRoleByKey(IdentityService.OWNER_ROLE_KEY)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "Signup is not available: owner role is not configured"
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

    private void issueVerificationEmail(User user) {
        emailVerificationTokenRepository.deleteUnusedByUserId(user.getId());
        String raw = newRawToken();
        EmailVerificationToken token = new EmailVerificationToken();
        token.setUserId(user.getId());
        token.setTokenHash(TokenHasher.sha256Hex(raw));
        token.setExpiresAt(Instant.now().plus(emailVerificationTtlHours, ChronoUnit.HOURS));
        emailVerificationTokenRepository.save(token);
        String link = emailVerificationUrlPrefix + raw;
        String body = emailVerificationEmailRenderer.renderBody(user.getEmail(), link);
        notificationService.sendEmailVerificationEmail(user.getEmail(), "Verify your UB account", body);
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
