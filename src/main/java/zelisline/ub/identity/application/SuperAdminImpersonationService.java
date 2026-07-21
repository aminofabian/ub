package zelisline.ub.identity.application;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.identity.api.dto.AuthUserResponse;
import zelisline.ub.identity.api.dto.SaImpersonateRequest;
import zelisline.ub.identity.api.dto.SaImpersonateResponse;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserSession;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.identity.repository.UserSessionRepository;
import zelisline.ub.platform.security.JwtTokenService;
import zelisline.ub.tenancy.api.dto.BusinessResponse;
import zelisline.ub.tenancy.application.TenancyService;

/**
 * Mints a short-lived tenant session so a platform operator can open a
 * tenant dashboard (implement.md §14.4). Requires a real {@link UserSession}
 * row — bare JWTs are rejected by {@code JwtAuthenticationFilter}.
 */
@Service
@RequiredArgsConstructor
public class SuperAdminImpersonationService {

    private final UserRepository userRepository;
    private final UserSessionRepository userSessionRepository;
    private final JwtTokenService jwtTokenService;
    private final TenancyService tenancyService;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    @Value("${app.jwt.refresh-ttl-days:30}")
    private long refreshTtlDays;

    @Transactional
    public SaImpersonateResponse impersonate(
            String businessId,
            String superAdminId,
            SaImpersonateRequest request,
            HttpServletRequest http
    ) {
        BusinessResponse business = tenancyService.getBusiness(businessId);
        if (!business.active()) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "Tenant is inactive");
        }

        User user = resolveTargetUser(businessId, request == null ? null : request.userId());
        if (user.statusAsEnum() != UserStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Target user is not active");
        }

        String jti = UUID.randomUUID().toString();
        String refreshRaw = newSecureRefresh();
        Instant now = Instant.now();
        Instant accessExp = now.plus(JwtTokenService.IMPERSONATION_ACCESS_TTL_MINUTES, ChronoUnit.MINUTES);
        Instant refreshExp = now.plus(refreshTtlDays, ChronoUnit.DAYS);

        UserSession session = new UserSession();
        session.setUserId(user.getId());
        session.setBusinessId(user.getBusinessId());
        session.setAccessTokenJti(jti);
        session.setRefreshTokenHash(TokenHasher.sha256Hex(refreshRaw));
        session.setUserAgent(trimToNull(http.getHeader("User-Agent")));
        session.setIp(clientIp(http));
        session.setExpiresAt(accessExp);
        session.setRefreshExpiresAt(refreshExp);
        session.setLastSeenAt(now);
        userSessionRepository.save(session);

        String access = jwtTokenService.createImpersonationAccessToken(
                user.getId(),
                user.getBusinessId(),
                user.getRoleId(),
                user.getBranchId(),
                jti,
                superAdminId
        );

        auditEventPublisher.publishSynchronous(auditEventBuilder
                .builder(AuditEventCategory.SECURITY, AuditEventTypes.IMPERSONATION_STARTED, AuditEventSeverity.WARN)
                .businessId(businessId)
                .actor(superAdminId, AuditEventActorType.USER)
                .actorName("super_admin")
                .target("user", user.getId())
                .targetLabel(user.getEmail())
                .source("super_admin_portal")
                .reason("Impersonation session started")
                .ipAddress(clientIp(http))
                .userAgent(trimToNull(http.getHeader("User-Agent")))
                .build());

        return new SaImpersonateResponse(
                access,
                refreshRaw,
                toAuthUser(user),
                business.id(),
                business.slug(),
                business.primaryDomain(),
                superAdminId,
                JwtTokenService.IMPERSONATION_ACCESS_TTL_MINUTES * 60
        );
    }

    private User resolveTargetUser(String businessId, String requestedUserId) {
        if (requestedUserId != null && !requestedUserId.isBlank()) {
            return userRepository
                    .findByIdAndBusinessIdAndDeletedAtIsNull(requestedUserId.trim(), businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found in this tenant"));
        }
        List<User> owners = userRepository.findActiveByRoleKeyOrderByCreatedAtAsc(
                businessId, IdentityService.OWNER_ROLE_KEY);
        if (!owners.isEmpty()) {
            return owners.getFirst();
        }
        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "No active owner user on this tenant. Pick a user explicitly."
        );
    }

    private static AuthUserResponse toAuthUser(User user) {
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
}
