package zelisline.ub.identity.application;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.identity.api.dto.SuperAdminLoginRequest;
import zelisline.ub.identity.api.dto.SuperAdminLoginResponse;
import zelisline.ub.identity.domain.SuperAdmin;
import zelisline.ub.identity.repository.SuperAdminRepository;
import zelisline.ub.platform.security.JwtTokenService;

@Service
@RequiredArgsConstructor
public class SuperAdminAuthService {

    private final SuperAdminRepository superAdminRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    @Transactional
    public SuperAdminLoginResponse login(SuperAdminLoginRequest request) {
        String email = request.email().trim().toLowerCase();
        SuperAdmin admin = superAdminRepository.findByEmail(email)
                .orElseThrow(() -> {
                    publishSuperAdminEvent(null, AuditEventTypes.LOGIN_FAILED, "Unknown email");
                    return invalidCredentials();
                });
        if (!admin.isActive()) {
            publishSuperAdminEvent(admin, AuditEventTypes.LOGIN_FAILED, "Account inactive");
            throw invalidCredentials();
        }
        if (admin.getMfaSecret() != null && !admin.getMfaSecret().isBlank()) {
            publishSuperAdminEvent(admin, AuditEventTypes.LOGIN_FAILED, "MFA required");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "MFA is required for this account");
        }
        if (admin.getLockedUntil() != null && admin.getLockedUntil().isAfter(Instant.now())) {
            publishSuperAdminEvent(admin, AuditEventTypes.LOGIN_FAILED, "Account locked");
            throw invalidCredentials();
        }
        if (!passwordEncoder.matches(request.password(), admin.getPasswordHash())) {
            admin.setFailedAttempts(admin.getFailedAttempts() + 1);
            superAdminRepository.save(admin);
            publishSuperAdminEvent(admin, AuditEventTypes.LOGIN_FAILED, "Incorrect password");
            throw invalidCredentials();
        }
        admin.setFailedAttempts(0);
        admin.setLockedUntil(null);
        admin.setLastLoginAt(Instant.now());
        superAdminRepository.save(admin);
        String jti = UUID.randomUUID().toString();
        String access = jwtTokenService.createSuperAdminAccessToken(admin.getId(), jti);
        publishSuperAdminEvent(admin, AuditEventTypes.LOGIN_SUCCEEDED, null);
        return new SuperAdminLoginResponse(access, admin.getId(), admin.getEmail(), admin.getName());
    }

    private void publishSuperAdminEvent(SuperAdmin admin, String eventType, String reason) {
        auditEventPublisher.publishSynchronous(auditEventBuilder.builder(AuditEventCategory.SECURITY, eventType,
                        AuditEventTypes.LOGIN_SUCCEEDED.equals(eventType) ? AuditEventSeverity.INFO : AuditEventSeverity.WARN)
                .businessId("platform")
                .actor(admin == null ? null : admin.getId(), AuditEventActorType.USER)
                .actorName(admin == null ? "unknown" : admin.getEmail())
                .target("super_admin", admin == null ? null : admin.getId())
                .targetLabel(admin == null ? "unknown" : admin.getEmail())
                .source("super_admin_portal")
                .reason(reason)
                .build());
    }

    private ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Incorrect email or password.");
    }
}
