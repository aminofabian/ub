package zelisline.ub.desktop.application;

import java.security.SecureRandom;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import zelisline.ub.desktop.api.dto.MasterDataSnapshot;
import zelisline.ub.identity.application.IdentityService;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;

/**
 * Mirrors the cloud's staff list onto a desktop install (connect + Settings →
 * Sync now). Each cloud user keeps its cloud id locally, so a pushed sale's
 * {@code soldBy} already refers to the real cloud cashier.
 *
 * <p>Credentials are deliberately NOT synced: the cloud's password hash cannot
 * be reversed, and replicating PINs would be a security risk. Every mirrored
 * user gets a generated password so the row satisfies the
 * {@code chk_users_credentials} check and can be unlocked by the till owner,
 * who assigns local PINs from Settings → Users.
 *
 * <p>Roles are remapped by {@code roleKey} to the local system roles (their ids
 * are stable across installs — see {@code V3__identity_seed.sql}). Unknown keys
 * fall back to the cashier role so a till operator can still sign in.
 */
@Service
@Profile("desktop")
@RequiredArgsConstructor
public class DesktopStaffSyncService {

    private static final Logger log = LoggerFactory.getLogger(DesktopStaffSyncService.class);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * Upsert every cloud staff row. Existing users keep their local credentials
     * (password/PIN) — only identity fields and the role are refreshed.
     *
     * @param validBranchIds branch ids present in the snapshot; staff assigned
     *     to a branch the cloud retired get a null branch instead of tripping
     *     the {@code users.branch_id} FK
     * @return the number of cloud staff ids mirrored locally
     */
    public int upsertStaff(
            String localId,
            java.util.List<MasterDataSnapshot.StaffData> staff,
            java.util.Set<String> validBranchIds) {
        if (staff == null) {
            return 0;
        }
        int count = 0;
        for (MasterDataSnapshot.StaffData d : staff) {
            if (d.id() == null || d.id().isBlank()) {
                continue;
            }
            User user = userRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(d.id(), localId)
                .orElseGet(() -> {
                    // Fall back to the email match for rows the connect flow
                    // created under a different id (defensive; connect now
                    // reuses the cloud owner id, so this is rare).
                    String email = d.email() == null ? null : d.email().trim().toLowerCase(Locale.ROOT);
                    return email == null || email.isBlank()
                        ? null
                        : userRepository
                            .findByBusinessIdAndEmailAndDeletedAtIsNull(localId, email)
                            .orElse(null);
                });
            if (user == null) {
                user = new User();
                user.setId(d.id());
                user.setBusinessId(localId);
                user.setEmail(d.email());
                user.setName(d.name() == null || d.name().isBlank() ? "Staff" : d.name().trim());
                // Generated local credential; the owner assigns a real PIN later.
                user.setPasswordHash(passwordEncoder.encode(generatePassword()));
            }
            applyIdentity(user, d, validBranchIds);
            userRepository.save(user);
            count++;
        }
        log.info("[DesktopSync] mirrored {} staff member(s) onto local install", count);
        return count;
    }

    private void applyIdentity(
            User user,
            MasterDataSnapshot.StaffData d,
            java.util.Set<String> validBranchIds) {
        user.setName(d.name() == null || d.name().isBlank() ? user.getName() : d.name().trim());
        if (d.email() != null && !d.email().isBlank()) {
            user.setEmail(d.email().trim());
        }
        if (d.phone() != null) {
            user.setPhone(d.phone().isBlank() ? null : d.phone().trim());
        }
        user.setBranchId(d.branchId() != null && validBranchIds.contains(d.branchId())
            ? d.branchId()
            : null);
        user.setRoleId(resolveRoleId(d.roleKey()));
        user.setStatus(safeStatus(d.status()));
    }

    private static UserStatus safeStatus(String status) {
        try {
            return UserStatus.fromWire(status);
        } catch (IllegalArgumentException e) {
            // Unknown wire value from a newer cloud — keep the mirror active.
            return UserStatus.ACTIVE;
        }
    }

    /**
     * Map a cloud role key to a local system role. System role ids are stable
     * across installs; unknown / tenant-scoped keys fall back to cashier.
     */
    public String resolveRoleId(String roleKey) {
        if (roleKey != null && !roleKey.isBlank()) {
            java.util.Optional<zelisline.ub.identity.domain.Role> role =
                roleRepository.findSystemRoleByKey(roleKey.trim());
            if (role.isPresent()) {
                return role.get().getId();
            }
        }
        return roleRepository
            .findSystemRoleByKey("cashier")
            .orElseGet(() -> roleRepository
                .findSystemRoleByKey(IdentityService.OWNER_ROLE_KEY)
                .orElseThrow(() -> new IllegalStateException(
                    "No system cashier/owner role seeded — Flyway migrations may not have run"
                )))
            .getId();
    }

    private static String generatePassword() {
        SecureRandom random = new SecureRandom();
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder(20);
        for (int i = 0; i < 20; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
