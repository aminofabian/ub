package zelisline.ub.integrations.privacy.application;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.application.IdentityService;
import zelisline.ub.identity.application.UserSessionRevocation;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;

/**
 * Staff / tenant user erasure: scrub login identifiers, revoke sessions, keep {@code users.id} for audit joins.
 */
@Service
@RequiredArgsConstructor
public class UserAnonymisationService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserSessionRevocation userSessionRevocation;

    @Transactional
    public void anonymiseUser(String businessId, String userId) {
        User u = userRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(userId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (u.getAnonymisedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User already anonymised");
        }
        if (hasOwnerRole(u.getRoleId())) {
            long owners = userRepository.countActiveByRoleKey(businessId, IdentityService.OWNER_ROLE_KEY);
            if (owners <= 1) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Cannot anonymise the last active owner of this tenant");
            }
        }

        userSessionRevocation.revokeAllActiveForUserNow(userId);

        u.setName(CustomerAnonymisationService.REDACTED_NAME);
        u.setPhone(null);
        u.setEmail(redactedEmail(userId));
        u.setPasswordHash(null);
        u.setPinHash(null);
        u.setStatus(UserStatus.SUSPENDED);
        u.setAnonymisedAt(Instant.now());
        userRepository.save(u);
    }

    private boolean hasOwnerRole(String roleId) {
        return roleRepository
                .findByIdAndDeletedAtIsNull(roleId)
                .map(r -> IdentityService.OWNER_ROLE_KEY.equals(r.getRoleKey()))
                .orElse(false);
    }

    /** Public for tests; deterministic unique placeholder per {@code users.id}. */
    public static String redactedEmail(String userId) {
        return "redacted." + userId + "@invalid.ub";
    }
}
