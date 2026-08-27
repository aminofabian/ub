package zelisline.ub.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.api.dto.EmailLookupRequest;
import zelisline.ub.identity.api.dto.EmailLookupResponse;
import zelisline.ub.identity.api.dto.PasswordForgotRequest;
import zelisline.ub.identity.api.dto.LoginResponse;
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
import zelisline.ub.notifications.NotificationCategories;
import zelisline.ub.notifications.NotificationTypes;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.tenancy.application.PublicHostResolverService;
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
    private final zelisline.ub.notifications.application.NotificationService inAppNotificationService;
    private final EmailVerificationEmailRenderer emailVerificationEmailRenderer;
    private final WelcomeEmailRenderer welcomeEmailRenderer;
    private final PublicHostResolverService publicHostResolverService;
    private final FrontendAuthLinkBuilder frontendAuthLinkBuilder;
    private final AuthService authService;
    private final Environment environment;
    private final ObjectMapper objectMapper;
    private final zelisline.ub.onboarding.sequence.application.MerchantOnboardingSequenceService
            onboardingSequenceService;
    private final zelisline.ub.support.application.SupportService supportService;

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
        log.info("[register] resolved role={} roleKey={}", role.getId(), role.getRoleKey());
        User user = new User();
        user.setBusinessId(businessId);
        user.setEmail(email);
        user.setName(request.name().trim());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRoleId(role.getId());
        if (isEmailVerificationRequired()) {
            user.setStatus(UserStatus.INVITED);
            User saved = userRepository.save(user);
            sendWelcome(saved, businessId, role);
            String link = issueVerificationEmail(saved, http);
            return new RegisterResponse(
                    saved.getId(),
                    saved.getEmail(),
                    UserStatus.INVITED.wire(),
                    returnVerificationLinkInRegisterResponse ? link : null);
        }
        user.setStatus(UserStatus.ACTIVE);
        User saved = userRepository.save(user);
        sendWelcome(saved, businessId, role);
        return new RegisterResponse(saved.getId(), saved.getEmail(), UserStatus.ACTIVE.wire(), null);
    }

    /**
     * Email for every signup. In-app welcome is tenant operators only
     * (owners / staff) — not storefront buyers.
     */
    private void sendWelcome(User user, String businessId, Role role) {
        String businessName = businessRepository.findByIdAndDeletedAtIsNull(businessId)
                .map(b -> b.getName())
                .orElse(null);
        String subject = welcomeEmailRenderer.renderSubject();
        String htmlBody = welcomeEmailRenderer.renderHtml(user.getName(), businessName);
        notificationService.sendWelcomeEmail(user.getEmail(), subject, htmlBody);
        if (isSequenceOwnerRole(role)) {
            pushWelcomeInApp(user, businessId, businessName);
            pushWelcomeSupportChat(user, businessId, businessName);
            onboardingSequenceService.enrollAfterWelcome(businessId, user.getId());
        } else if (isTenantOperatorRole(role)) {
            // Staff operators get welcome in-app; week-1 sequence belongs to owner/admin only.
            pushWelcomeInApp(user, businessId, businessName);
            pushWelcomeSupportChat(user, businessId, businessName);
        }
    }

    /** Owner / admin signup only — invited cashiers don't get the week-1 sequence. */
    private boolean isSequenceOwnerRole(Role role) {
        if (role == null || role.getRoleKey() == null) {
            return false;
        }
        String key = role.getRoleKey().trim().toLowerCase();
        return IdentityService.OWNER_ROLE_KEY.equalsIgnoreCase(key) || "admin".equals(key);
    }

    /** Store buyers use the shopper inbox; tenant staff use the business bell. */
    private boolean isTenantOperatorRole(Role role) {
        if (role == null || role.getRoleKey() == null) {
            return false;
        }
        String key = role.getRoleKey().trim().toLowerCase();
        return !signupRoleKey.trim().equalsIgnoreCase(key);
    }

    private void pushWelcomeInApp(User user, String businessId, String businessName) {
        String name = WelcomeEmailRenderer.displayName(user.getName());
        String business = WelcomeEmailRenderer.displayBusiness(businessName);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", "Welcome to Kiosk!");
        payload.put("body", welcomeEmailRenderer.renderPlainText(user.getName(), businessName));
        // Open Support — /business is usually already the post-signup screen, so
        // router.push("/business") looked like a dead click.
        payload.put("actionUrl", "/support");
        payload.put("name", name);
        payload.put("businessName", business);
        payload.put("supportPhone", WelcomeEmailRenderer.SUPPORT_PHONE);
        payload.put("supportEmail", WelcomeEmailRenderer.SUPPORT_EMAIL);
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        // Business-wide (userId null) so it lands in the tenant staff inbox,
        // not the storefront shopper notification panel.
        inAppNotificationService.tryInsertDedupe(
                businessId,
                NotificationTypes.ACCOUNT_WELCOME,
                "welcome:" + user.getId(),
                NotificationCategories.ENGAGEMENT,
                "MEDIUM",
                json);
    }

    private void pushWelcomeSupportChat(User user, String businessId, String businessName) {
        supportService.postPlatformWelcome(
                businessId,
                user.getId(),
                welcomeEmailRenderer.renderSupportChat(user.getName(), businessName));
    }

    /**
     * Activates an invited user and issues a web session so they can continue
     * onboarding without signing in again.
     */
    @Transactional
    public LoginResponse verifyEmail(HttpServletRequest http, VerifyEmailRequest request) {
        String hash = TokenHasher.sha256Hex(request.token());
        var row = emailVerificationTokenRepository.findByTokenHashAndUsedAtIsNull(hash)
                .orElseThrow(() -> invalidToken());
        if (row.getExpiresAt().isBefore(Instant.now())) {
            throw invalidToken();
        }
        User user = userRepository.findById(row.getUserId())
                .orElseThrow(() -> invalidToken());
        // The token identifies the tenant; a verification link opened on the
        // platform apex (no domain mapping) must still activate the account.
        String requestBusinessId = TenantRequestIds.resolveBusinessIdOrNull(http);
        if (user.getDeletedAt() != null
                || (requestBusinessId != null && !user.getBusinessId().equals(requestBusinessId))) {
            throw invalidToken();
        }
        TenantRequestIds.bindBusinessId(http, user.getBusinessId());
        if (user.statusAsEnum() == UserStatus.INVITED) {
            user.setStatus(UserStatus.ACTIVE);
            userRepository.save(user);
        }
        row.setUsedAt(Instant.now());
        emailVerificationTokenRepository.save(row);
        return authService.issueSessionForUser(user, http, "email_verification");
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

    /**
     * Checkout helper: tells the storefront whether to show sign-in or sign-up for a contact email.
     * Intentionally reveals registration status for the current tenant only.
     */
    @Transactional(readOnly = true)
    public EmailLookupResponse lookupEmail(HttpServletRequest http, EmailLookupRequest request) {
        assertSignupEnabled();
        String businessId = TenantRequestIds.resolveBusinessId(http);
        if (!businessRepository.findByIdAndDeletedAtIsNull(businessId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found");
        }
        String email = normaliseEmail(request.email());
        boolean registered = userRepository.existsByBusinessIdAndEmailAndDeletedAtIsNull(businessId, email);
        return new EmailLookupResponse(registered);
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
        String link = frontendAuthLinkBuilder.verificationLink(http, user.getBusinessId(), raw);
        String frontendHost = frontendAuthLinkBuilder.resolveFrontendHost(http);
        var branding = EmailVerificationBrandingContext.fromHost(
                frontendHost != null
                        ? publicHostResolverService.resolveByHost(frontendHost)
                        : Optional.empty(),
                frontendHost);
        String subject = emailVerificationEmailRenderer.renderSubject(branding);
        String htmlBody = emailVerificationEmailRenderer.renderHtml(
                branding, user.getName(), user.getEmail(), link);
        notificationService.sendEmailVerificationEmail(user.getEmail(), subject, htmlBody);
        return link;
    }

    /**
     * Mint a verification URL without sending the stock verify mail — used when a
     * platform campaign already carries the continue button.
     */
    @Transactional
    public String issueVerificationLinkOnly(User user) {
        emailVerificationTokenRepository.deleteUnusedByUserId(user.getId());
        String raw = newRawToken();
        EmailVerificationToken token = new EmailVerificationToken();
        token.setUserId(user.getId());
        token.setTokenHash(TokenHasher.sha256Hex(raw));
        token.setExpiresAt(Instant.now().plus(emailVerificationTtlHours, ChronoUnit.HOURS));
        emailVerificationTokenRepository.save(token);
        return frontendAuthLinkBuilder.verificationLinkForBusiness(user.getBusinessId(), raw);
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
