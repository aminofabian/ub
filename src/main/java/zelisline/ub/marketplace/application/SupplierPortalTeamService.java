package zelisline.ub.marketplace.application;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.CreateSupplierPortalTeamUserRequest;
import zelisline.ub.marketplace.api.dto.PatchSupplierPortalTeamUserRequest;
import zelisline.ub.marketplace.api.dto.SupplierPortalTeamUserRow;
import zelisline.ub.marketplace.domain.SupplierUser;
import zelisline.ub.marketplace.domain.SupplierUserRoles;
import zelisline.ub.marketplace.repository.SupplierUserRepository;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.platform.application.PlatformSupplierPortalSettingsService;
import zelisline.ub.platform.security.SupplierPrincipal;

@Service
@RequiredArgsConstructor
public class SupplierPortalTeamService {

    private final SupplierUserRepository supplierUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final PlatformSupplierPortalSettingsService portalSettingsService;
    private final SupplierPortalSessionService sessionService;

    @Transactional(readOnly = true)
    public List<SupplierPortalTeamUserRow> list(SupplierPrincipal principal) {
        return supplierUserRepository
                .findByMarketplaceSupplierIdOrderByCreatedAtAsc(principal.marketplaceSupplierId())
                .stream()
                .map(u -> toRow(u, principal.userId()))
                .toList();
    }

    @Transactional
    public SupplierPortalTeamUserRow create(SupplierPrincipal principal, CreateSupplierPortalTeamUserRequest request) {
        String roleKey;
        try {
            roleKey = SupplierUserRoles.normalize(request.roleKey());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
        portalSettingsService.validatePassword(request.password());

        String email = normalizeEmail(request.email());
        if (email != null && !email.contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid email");
        }
        String phone = normalizePhone(request.phone());
        if (email == null && phone == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide an email or phone for the teammate");
        }
        if (email != null && supplierUserRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That email already has a portal account");
        }
        if (phone != null && supplierUserRepository.existsByPhone(phone)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That phone already has a portal account");
        }

        SupplierUser user = new SupplierUser();
        user.setMarketplaceSupplierId(principal.marketplaceSupplierId());
        user.setName(request.name().trim());
        user.setEmail(email);
        user.setPhone(phone);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRoleKey(roleKey);
        user.setActive(true);
        supplierUserRepository.save(user);
        return toRow(user, principal.userId());
    }

    @Transactional
    public SupplierPortalTeamUserRow patch(
            SupplierPrincipal principal,
            String userId,
            PatchSupplierPortalTeamUserRequest request
    ) {
        SupplierUser user = requireTeammate(principal.marketplaceSupplierId(), userId);
        boolean self = principal.userId().equals(user.getId());

        if (request.roleKey() != null && !request.roleKey().isBlank()) {
            String roleKey;
            try {
                roleKey = SupplierUserRoles.normalize(request.roleKey());
            } catch (IllegalArgumentException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
            }
            if (self && !SupplierUserRoles.ADMIN.equals(roleKey)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot demote your own account");
            }
            if (SupplierUserRoles.ADMIN.equals(user.getRoleKey())
                    && !SupplierUserRoles.ADMIN.equals(roleKey)
                    && !hasOtherAdmin(principal.marketplaceSupplierId(), user.getId())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Keep at least one owner/admin on the account");
            }
            user.setRoleKey(roleKey);
        }

        if (request.active() != null) {
            if (self && Boolean.FALSE.equals(request.active())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot suspend your own account");
            }
            if (Boolean.FALSE.equals(request.active())
                    && SupplierUserRoles.isAdmin(user.getRoleKey())
                    && !hasOtherAdmin(principal.marketplaceSupplierId(), user.getId())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Keep at least one active owner/admin on the account");
            }
            user.setActive(request.active());
            if (!user.isActive()) {
                sessionService.revokeAll(user.getId());
            }
        }

        supplierUserRepository.save(user);
        return toRow(user, principal.userId());
    }

    @Transactional
    public SupplierPortalTeamUserRow resetPassword(
            SupplierPrincipal principal,
            String userId,
            String password
    ) {
        portalSettingsService.validatePassword(password);
        SupplierUser user = requireTeammate(principal.marketplaceSupplierId(), userId);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        supplierUserRepository.save(user);
        sessionService.revokeAll(user.getId());
        return toRow(user, principal.userId());
    }

    private boolean hasOtherAdmin(String marketplaceSupplierId, String excludeUserId) {
        return supplierUserRepository.findByMarketplaceSupplierIdOrderByCreatedAtAsc(marketplaceSupplierId).stream()
                .anyMatch(u -> !u.getId().equals(excludeUserId)
                        && u.isActive()
                        && SupplierUserRoles.isAdmin(u.getRoleKey()));
    }

    private SupplierUser requireTeammate(String marketplaceSupplierId, String userId) {
        return supplierUserRepository.findByIdAndMarketplaceSupplierId(userId, marketplaceSupplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team member not found"));
    }

    private static SupplierPortalTeamUserRow toRow(SupplierUser user, String currentUserId) {
        return new SupplierPortalTeamUserRow(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getPhone(),
                user.getRoleKey(),
                user.isActive(),
                user.getLastLoginAt(),
                user.getCreatedAt(),
                user.getId().equals(currentUserId));
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase();
    }

    private static String normalizePhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String normalized = StkPhoneNormalizer.normalize(phone);
        if (normalized == null || normalized.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid phone number");
        }
        return normalized;
    }
}
