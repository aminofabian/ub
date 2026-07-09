package zelisline.ub.marketplace.application;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.SupplierPortalLoginRequest;
import zelisline.ub.marketplace.api.dto.SupplierPortalLoginResponse;
import zelisline.ub.marketplace.domain.SupplierUser;
import zelisline.ub.marketplace.repository.SupplierUserRepository;
import zelisline.ub.platform.security.JwtTokenService;

@Service
@RequiredArgsConstructor
public class SupplierPortalAuthService {

    private final SupplierUserRepository supplierUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;

    @Transactional
    public SupplierPortalLoginResponse login(SupplierPortalLoginRequest request) {
        String email = request.email().trim().toLowerCase();
        SupplierUser user = supplierUserRepository.findByEmail(email)
                .orElseThrow(this::invalidCredentials);
        if (!user.isActive()) {
            throw invalidCredentials();
        }
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now())) {
            throw invalidCredentials();
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            user.setFailedAttempts(user.getFailedAttempts() + 1);
            supplierUserRepository.save(user);
            throw invalidCredentials();
        }
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(Instant.now());
        supplierUserRepository.save(user);
        String jti = UUID.randomUUID().toString();
        String access = jwtTokenService.createSupplierAccessToken(
                user.getId(),
                user.getMarketplaceSupplierId(),
                user.getRoleKey(),
                jti);
        return new SupplierPortalLoginResponse(
                access,
                user.getId(),
                user.getMarketplaceSupplierId(),
                user.getEmail(),
                user.getName());
    }

    private ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Incorrect email or password.");
    }
}
