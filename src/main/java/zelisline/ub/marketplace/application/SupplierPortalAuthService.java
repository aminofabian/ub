package zelisline.ub.marketplace.application;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.SupplierPortalLoginRequest;
import zelisline.ub.marketplace.api.dto.SupplierPortalLoginResponse;
import zelisline.ub.marketplace.domain.SupplierUser;
import zelisline.ub.marketplace.repository.SupplierUserRepository;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.platform.application.PlatformSupplierPortalSettingsService;

@Service
@RequiredArgsConstructor
public class SupplierPortalAuthService {

    private final SupplierUserRepository supplierUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final PlatformSupplierPortalSettingsService portalSettingsService;
    private final SupplierPortalSessionService sessionService;

    @Transactional
    public SupplierPortalLoginResponse login(SupplierPortalLoginRequest request, HttpServletRequest http) {
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
        return sessionService.issueLogin(user, http);
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
