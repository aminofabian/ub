package zelisline.ub.desktop.application;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
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
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    /**
     * Last cloud PIN hash this till refused, keyed by user id. A persistent
     * till-vs-cloud PIN divergence is logged when it first appears (or when the
     * cloud value changes) rather than on every 30-minute master pull. In-memory
     * only: a restart may re-emit once, which is acceptable.
     */
    private final ConcurrentHashMap<String, String> refusedCloudPins = new ConcurrentHashMap<>();

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
     * Apply cloud password/PIN hashes under the documented authority policy
     * (docs/scopes/DESKTOP_APP_AUDIT_SCOPE.md §4.5, WP-12):
     *
     * <ul>
     *   <li><b>Password — cloud wins.</b> The online shop owns passwords, so a
     *       cloud hash always replaces the local one.</li>
     *   <li><b>PIN — till wins.</b> A PIN set on this install (identifiable by a
     *       present {@code pinEnc}, which is cleared whenever a PIN is mirrored
     *       from the cloud) is never clobbered by the cloud copy — so "my PIN
     *       change sticks" is true within the 30-minute sync window. A till that
     *       has never had a local PIN adopts the cloud one.</li>
     * </ul>
     *
     * Every genuine conflict is written to the unified audit log (STAFF
     * category, source {@code desktop_sync}, severity WARN so it also surfaces
     * in the failures view): the cloud password override once per divergence,
     * and the till-kept PIN once per distinct cloud PIN rather than on every
     * master pull. No credential hashes are logged.
     *
     * Hashes are bcrypt/argon strings from the cloud row — never re-encoded.
     * Falls back to a generated password only when creating a row that has
     * neither hash (satisfies chk_users_credentials).
     */
    private void applyCredentials(User user, MasterDataSnapshot.StaffData d, boolean created) {
        String passwordHash = blankToNull(d.passwordHash());
        String pinHash = blankToNull(d.pinHash());
        if (passwordHash != null) {
            String localPassword = blankToNull(user.getPasswordHash());
            if (localPassword != null && !localPassword.equals(passwordHash)) {
                publishCredentialConflict(
                    user,
                    AuditEventTypes.STAFF_PASSWORD_OVERRIDDEN,
                    AuditEventSeverity.WARN,
                    "password",
                    "cloud");
            }
            user.setPasswordHash(passwordHash);
        }
        if (pinHash != null) {
            if (hasTillSetPin(user)) {
                // Local PIN is authoritative; leave it (and its reveal ciphertext)
                // alone rather than overwriting with the cloud copy.
                if (!pinHash.equals(user.getPinHash())) {
                    if (!pinHash.equals(refusedCloudPins.put(user.getId(), pinHash))) {
                        publishCredentialConflict(
                            user,
                            AuditEventTypes.STAFF_PIN_KEPT_LOCAL,
                            AuditEventSeverity.WARN,
                            "pin",
                            "till");
                    }
                    if (log.isDebugEnabled()) {
                        log.debug(
                            "[DesktopSync] keeping till-set PIN for user {} ({}) — cloud PIN differs",
                            user.getId(), user.getEmail());
                    }
                } else {
                    // Cloud now agrees — drop the marker so a later divergence re-logs.
                    refusedCloudPins.remove(user.getId());
                }
            } else {
                user.setPinHash(pinHash);
                // Cloud pinEnc uses a different encryption key — clear any stale
                // local reveal ciphertext so admins re-set the PIN to view it.
                user.setPinEnc(null);
                refusedCloudPins.remove(user.getId());
            }
        }
        if (created
                && user.getPasswordHash() == null
                && user.getPinHash() == null) {
            user.setPasswordHash(passwordEncoder.encode(generatePassword()));
        }
    }

    /**
     * Record the outcome of a till-vs-cloud staff credential conflict (WP-12).
     * Only which side won and for which credential is logged — never a hash.
     */
    private void publishCredentialConflict(
            User user,
            String eventType,
            AuditEventSeverity severity,
            String credential,
            String authority) {
        try {
            auditEventPublisher.publish(auditEventBuilder.builder(
                    AuditEventCategory.STAFF,
                    eventType,
                    severity)
                .businessId(user.getBusinessId())
                .actor(null, AuditEventActorType.SYSTEM)
                .target("user", user.getId())
                .targetLabel(user.getEmail())
                .source("desktop_sync")
                .reason("cloud".equals(authority)
                    ? "Cloud credential applied over a differing local value"
                    : "Till-set credential kept; the cloud copy was not applied")
                .metadata(Map.of("credential", credential, "authority", authority))
                .build());
        } catch (RuntimeException e) {
            // An audit failure must never break the staff sync.
            log.warn(
                "[DesktopSync] failed to publish staff credential audit event {}",
                eventType, e);
        }
    }

    /**
     * A PIN was set on this till when both a hash and its reveal ciphertext are
     * present — {@code applyPin} writes both, while mirroring a cloud PIN clears
     * the ciphertext. Used as the "till wins for PIN" marker.
     */
    private static boolean hasTillSetPin(User user) {
        return user.getPinHash() != null
            && !user.getPinHash().isBlank()
            && user.getPinEnc() != null
            && !user.getPinEnc().isBlank();
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
