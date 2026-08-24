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
import zelisline.ub.marketplace.domain.MarketplaceSupplierStatuses;
import zelisline.ub.marketplace.domain.SupplierUser;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.marketplace.repository.SupplierUserRepository;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.platform.application.PlatformSupplierPortalSettingsService;

@Service
@RequiredArgsConstructor
public class SupplierPortalAuthService {

    private final SupplierUserRepository supplierUserRepository;
    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final PasswordEncoder passwordEncoder;
    private final PlatformSupplierPortalSettingsService portalSettingsService;
    private final SupplierPortalSessionService sessionService;
    private final SupplierPortalShopLinkService shopLinkService;
    private final SupplierSignInDoorService doorService;

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
        var marketplace = marketplaceSupplierRepository.findById(user.getMarketplaceSupplierId()).orElse(null);
        if (marketplace == null
                || MarketplaceSupplierStatuses.SUSPENDED.equalsIgnoreCase(marketplace.getStatus())) {
            throw invalidCredentials();
        }
        String secret = request.password() == null ? "" : request.password();
        if (secret.isBlank() || !passwordEncoder.matches(secret, user.getPasswordHash())) {
            user.setFailedAttempts(user.getFailedAttempts() + 1);
            supplierUserRepository.save(user);
            throw invalidCredentials();
        }
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(Instant.now());
        supplierUserRepository.save(user);
        try {
            shopLinkService.ensureLinksAndCatalogue(user.getMarketplaceSupplierId());
        } catch (RuntimeException ignored) {
            // Soft heal — login must still succeed.
        }
        return sessionService.issueLogin(user, http);
    }

    private java.util.Optional<SupplierUser> findByIdentifier(String raw) {
        if (raw == null || raw.isBlank()) {
            return java.util.Optional.empty();
        }
        String trimmed = raw.trim();
        java.util.Optional<SupplierUser> direct;
        if (trimmed.contains("@")) {
            direct = supplierUserRepository.findByEmail(trimmed.toLowerCase());
        } else {
            String phone = StkPhoneNormalizer.normalize(trimmed);
            direct = phone != null
                    ? supplierUserRepository.findByPhone(phone)
                    : supplierUserRepository.findByEmail(trimmed.toLowerCase());
        }
        if (direct.isPresent()) {
            return direct;
        }
        // Claim is phone-first, so the account often carries no email while the
        // shops that stock from them know only an email. Either identity signs in.
        return doorService.resolveLoginUser(trimmed);
    }

    private ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "Incorrect phone/email or PIN/password.");
    }
}
