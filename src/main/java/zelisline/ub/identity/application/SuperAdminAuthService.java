package zelisline.ub.identity.application;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
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

    @Transactional
    public SuperAdminLoginResponse login(SuperAdminLoginRequest request) {
        String email = request.email().trim().toLowerCase();
        SuperAdmin admin = superAdminRepository.findByEmail(email)
                .orElseThrow(this::invalidCredentials);
        if (!admin.isActive()) {
            throw invalidCredentials();
        }
        if (admin.getMfaSecret() != null && !admin.getMfaSecret().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "MFA is required for this account");
        }
        if (admin.getLockedUntil() != null && admin.getLockedUntil().isAfter(Instant.now())) {
            throw invalidCredentials();
        }
        if (!passwordEncoder.matches(request.password(), admin.getPasswordHash())) {
            admin.setFailedAttempts(admin.getFailedAttempts() + 1);
            superAdminRepository.save(admin);
            throw invalidCredentials();
        }
        admin.setFailedAttempts(0);
        admin.setLockedUntil(null);
        admin.setLastLoginAt(Instant.now());
        superAdminRepository.save(admin);
        String jti = UUID.randomUUID().toString();
        String access = jwtTokenService.createSuperAdminAccessToken(admin.getId(), jti);
        return new SuperAdminLoginResponse(access, admin.getId(), admin.getEmail(), admin.getName());
    }

    private ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
    }
}
