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
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.platform.application.PlatformSupplierPortalSettingsService;
import zelisline.ub.platform.security.JwtTokenService;

@Service
@RequiredArgsConstructor
public class SupplierPortalAuthService {

    private final SupplierUserRepository supplierUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final PlatformSupplierPortalSettingsService portalSettingsService;

    @Transactional
    public SupplierPortalLoginResponse login(SupplierPortalLoginRequest request) {
        portalSettingsService.requirePortalEnabled();
        SupplierUser user = findByIdentifier(request.identifier())
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
                user.getPhone(),
                user.getName());
    }

    private java.util.Optional<SupplierUser> findByIdentifier(String raw) {
        if (raw == null || raw.isBlank()) {
            return java.util.Optional.empty();
        }
        String trimmed = raw.trim();
        if (trimmed.contains("@")) {
            return supplierUserRepository.findByEmail(trimmed.toLowerCase());
        }
        String phone = StkPhoneNormalizer.normalize(trimmed);
        if (phone != null) {
            return supplierUserRepository.findByPhone(phone);
        }
        return supplierUserRepository.findByEmail(trimmed.toLowerCase());
    }

    private ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Incorrect phone/email or password.");
    }
}
