package zelisline.ub.desktop.application;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import zelisline.ub.desktop.api.dto.MasterDataSnapshot;
import zelisline.ub.identity.application.IdentityService;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;

/**
 * Mirrors the cloud's staff list onto a desktop install (connect + Settings →
 * Sync now). Each cloud user keeps its cloud id locally, so a pushed sale's
 * {@code soldBy} already refers to the real cloud cashier.
 *
 * <p>Credential <em>hashes</em> (password + PIN) are copied from the cloud so
 * the same email and password/PIN unlock the till. Plaintext is never
 * transmitted. When the cloud row has neither hash (rare), a generated
 * password keeps the {@code chk_users_credentials} check happy until the
 * owner sets a local PIN from Settings → Users.
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
    private static final String BUYER_ROLE_KEY = "buyer";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * Upsert every cloud staff row. Existing users get identity + role refreshed
     * and their password/PIN hashes replaced with the cloud copies so Sync now
     * repairs tills where staff could not sign in after connect.
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
            // Storefront customers (buyers) are not till staff — never mirror
            // them onto the register.
            if (d.roleKey() != null && "buyer".equalsIgnoreCase(d.roleKey())) {
                continue;
            }
            User user = userRepository
                .findById(d.id())
                .filter(u -> localId.equals(u.getBusinessId()))
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
            boolean created = false;
            if (user == null) {
                created = true;
                user = new User();
                user.setId(d.id());
                user.setBusinessId(localId);
                user.setEmail(d.email());
                user.setName(d.name() == null || d.name().isBlank() ? "Staff" : d.name().trim());
            } else {
                // Revive staff soft-deleted by "Set up this till again".
                user.setDeletedAt(null);
            }
            applyCredentials(user, d, created);
            applyIdentity(user, d, validBranchIds);
            userRepository.save(user);
            count++;
        }
        log.info("[DesktopSync] mirrored {} staff member(s) onto local install", count);
        return count;
    }

    /**
     * Soft-delete any storefront customer (buyer) rows that a pre-fix sync
     * already mirrored onto the register. Buyers are not till staff; soft-delete
     * keeps shift/sale references intact while hiding the account from every
     * list query.
     *
     * @return number of buyer accounts removed
     */
    public int removeBuyerStaff(String localId) {
        Role buyer = roleRepository.findSystemRoleByKey(BUYER_ROLE_KEY).orElse(null);
        if (buyer == null) {
            return 0;
        }
        java.util.List<User> buyers =
            userRepository.findByBusinessIdAndRoleIdAndDeletedAtIsNull(localId, buyer.getId());
        Instant now = Instant.now();
        for (User u : buyers) {
            u.setDeletedAt(now);
            userRepository.save(u);
        }
        if (!buyers.isEmpty()) {
            log.info("[DesktopSync] soft-deleted {} buyer account(s) from the local install", buyers.size());
        }
        return buyers.size();
    }

    /**
     * Apply cloud password/PIN hashes. Hashes are bcrypt/argon strings from the
     * cloud row — never re-encoded. Falls back to a generated password only when
     * creating a row that has neither hash (satisfies chk_users_credentials).
     */
    private void applyCredentials(User user, MasterDataSnapshot.StaffData d, boolean created) {
        String passwordHash = blankToNull(d.passwordHash());
        String pinHash = blankToNull(d.pinHash());
        if (passwordHash != null) {
            user.setPasswordHash(passwordHash);
        }
        if (pinHash != null) {
            user.setPinHash(pinHash);
            // Cloud pinEnc uses a different encryption key — clear any stale
            // local reveal ciphertext so admins re-set the PIN to view it.
            user.setPinEnc(null);
        }
        if (created
                && user.getPasswordHash() == null
                && user.getPinHash() == null) {
            user.setPasswordHash(passwordEncoder.encode(generatePassword()));
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
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
