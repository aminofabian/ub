package zelisline.ub.marketplace.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.api.dto.CreateUserRequest;
import zelisline.ub.identity.api.dto.LoginResponse;
import zelisline.ub.identity.api.dto.UserResponse;
import zelisline.ub.identity.application.AuthService;
import zelisline.ub.identity.application.IdentityService;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.marketplace.api.dto.SupplierPortalLoginResponse;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.domain.MarketplaceSupplierStatuses;
import zelisline.ub.marketplace.domain.SupplierUser;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.marketplace.repository.SupplierUserRepository;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.tenancy.api.TenantRequestIds;

/**
 * One sign-in for a supplier's portal team across the portal and their own shop (P1-3).
 *
 * <ul>
 *   <li>On the shop: portal details (email or phone, with the portal password or PIN) open a normal shop session.
 *       A portal user the shop has not met yet gets a trade desk shop user made for them on the spot.</li>
 *   <li>On the portal: the shop password of a linked shop user opens the portal.</li>
 * </ul>
 *
 * Both are consulted only where the door's own login has already refused, or on the supplier sign-in route, so a
 * sign-in that worked before is not touched. Wrong guesses here have their own count and their own lock, kept
 * apart from each door's own, so guessing here cannot lock anybody out of the portal or the shop.
 */
@Service
@RequiredArgsConstructor
public class SupplierShopSignInService {

    /** Wrong guesses before the first lock. Each wrong guess after that doubles the wait, up to a day. */
    static final int FREE_ATTEMPTS = 5;
    static final Duration FIRST_LOCK = Duration.ofMinutes(15);
    static final Duration LONGEST_LOCK = Duration.ofHours(24);
    static final String LOGIN_METHOD = "supplier_portal_credentials";
    static final String ROLE_TRADE_DESK = "supplier_trade_desk";
    /** For a portal user with no email. Reserved: mail to it goes nowhere. */
    static final String NO_EMAIL_DOMAIN = "portal.invalid";

    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final SupplierUserRepository supplierUserRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;
    private final IdentityService identityService;
    private final SupplierPortalSessionService portalSessionService;

    /** Portal details on the shop. Empty means "not this way": the caller reports its own refusal. */
    @Transactional
    public Optional<LoginResponse> signInToShop(HttpServletRequest http, String identifier, String secret) {
        String businessId = TenantRequestIds.resolveBusinessIdOrNull(http);
        if (businessId == null || secret == null || secret.isBlank()) {
            return Optional.empty();
        }
        MarketplaceSupplier page = marketplaceSupplierRepository.findFirstByBusinessId(businessId).orElse(null);
        SupplierUser portalUser = usablePortalUser(page, identifier);
        if (portalUser == null || portalUser.getPasswordHash() == null) {
            return Optional.empty();
        }
        if (!passwordEncoder.matches(secret, portalUser.getPasswordHash())) {
            countWrongGuess(portalUser);
            return Optional.empty();
        }
        User shopUser = linkedShopUser(businessId, portalUser);
        if (shopUser == null) {
            shopUser = makeShopUser(businessId, portalUser);
        }
        if (shopUser == null) {
            return Optional.empty();
        }
        // Invited by the shop but never opened the invitation: the portal details prove who this is.
        if (shopUser.statusAsEnum() == UserStatus.INVITED) {
            shopUser.setStatus(UserStatus.ACTIVE);
            userRepository.save(shopUser);
        }
        clearWrongGuesses(portalUser);
        return Optional.of(authService.issueSessionForUser(shopUser, http, LOGIN_METHOD));
    }

    /** A linked shop user's shop password on the portal. Empty means "not this way". */
    @Transactional
    public Optional<SupplierPortalLoginResponse> signInToPortal(HttpServletRequest http, String identifier, String secret) {
        if (secret == null || secret.isBlank()) {
            return Optional.empty();
        }
        SupplierUser portalUser = findPortalUser(identifier);
        if (portalUser == null || portalUser.getBusinessUserId() == null) {
            return Optional.empty();
        }
        MarketplaceSupplier page = marketplaceSupplierRepository.findById(portalUser.getMarketplaceSupplierId()).orElse(null);
        if (!usable(page, portalUser) || page.getBusinessId() == null) {
            return Optional.empty();
        }
        User shopUser = linkedShopUser(page.getBusinessId(), portalUser);
        if (shopUser == null
                || shopUser.statusAsEnum() != UserStatus.ACTIVE
                || shopUser.getPasswordHash() == null
                || (shopUser.getLockedUntil() != null && shopUser.getLockedUntil().isAfter(Instant.now()))) {
            return Optional.empty();
        }
        if (!passwordEncoder.matches(secret, shopUser.getPasswordHash())) {
            countWrongGuess(portalUser);
            return Optional.empty();
        }
        clearWrongGuesses(portalUser);
        return Optional.of(portalSessionService.issueLogin(portalUser, http));
    }

    private SupplierUser usablePortalUser(MarketplaceSupplier page, String identifier) {
        SupplierUser portalUser = findPortalUser(identifier);
        if (portalUser == null || page == null || !page.getId().equals(portalUser.getMarketplaceSupplierId())) {
            return null;
        }
        return usable(page, portalUser) ? portalUser : null;
    }

    private static boolean usable(MarketplaceSupplier page, SupplierUser portalUser) {
        Instant now = Instant.now();
        return page != null
                && !MarketplaceSupplierStatuses.SUSPENDED.equalsIgnoreCase(page.getStatus())
                && portalUser.isActive()
                && (portalUser.getLockedUntil() == null || !portalUser.getLockedUntil().isAfter(now))
                && (portalUser.getShopSigninLockedUntil() == null || !portalUser.getShopSigninLockedUntil().isAfter(now));
    }

    /** By email, or by phone number in any of the usual ways of writing it. */
    private SupplierUser findPortalUser(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return null;
        }
        String trimmed = identifier.trim();
        if (trimmed.contains("@")) {
            return supplierUserRepository.findByEmail(trimmed.toLowerCase(Locale.ROOT)).orElse(null);
        }
        String phone = StkPhoneNormalizer.normalize(trimmed);
        return phone == null ? null : supplierUserRepository.findByPhone(phone).orElse(null);
    }

    private User linkedShopUser(String businessId, SupplierUser portalUser) {
        if (portalUser.getBusinessUserId() == null) {
            return null;
        }
        return userRepository.findById(portalUser.getBusinessUserId())
                .filter(u -> u.getDeletedAt() == null && businessId.equals(u.getBusinessId()))
                .orElse(null);
    }

    /**
     * The shop has not met this portal user: make their shop user now, on the trade desk. Never joined to a shop user that already exists with the same email: that person has shop details of
     * their own, and a portal email is not proof of owning their shop account.
     */
    private User makeShopUser(String businessId, SupplierUser portalUser) {
        String email = portalUser.getEmail() == null || portalUser.getEmail().isBlank()
                ? "supplier-" + portalUser.getId() + "@" + NO_EMAIL_DOMAIN
                : portalUser.getEmail().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByBusinessIdAndEmailAndDeletedAtIsNull(businessId, email)) {
            return null;
        }
        // Always the trade desk, whatever the portal role (owner decision, 2026-11-22). A portal account can be
        // protected by a short PIN, so it must not be able to hand out shop admin. The shop's owner gives admin,
        // through "Invite my portal team" or the staff screen.
        Role role = roleRepository.findSystemRoleByKey(ROLE_TRADE_DESK).orElse(null);
        if (role == null) {
            return null;
        }
        String name = portalUser.getName() == null || portalUser.getName().isBlank()
                ? "Supplier team member"
                : portalUser.getName().trim();
        // A password nobody is told: this person signs in with their portal details, or resets it by email.
        String unknowable = UUID.randomUUID() + "-" + UUID.randomUUID();
        UserResponse created = identityService.createUser(businessId, new CreateUserRequest(
                email, name, null, role.getId(), null, null, unknowable, null, false, null));
        portalUser.setBusinessUserId(created.id());
        supplierUserRepository.save(portalUser);
        return userRepository.findById(created.id()).orElse(null);
    }

    private void countWrongGuess(SupplierUser portalUser) {
        int failures = portalUser.getShopSigninFailures() + 1;
        portalUser.setShopSigninFailures(failures);
        if (failures >= FREE_ATTEMPTS) {
            portalUser.setShopSigninLockedUntil(Instant.now().plus(lockFor(failures)));
        }
        supplierUserRepository.save(portalUser);
    }

    static Duration lockFor(int failures) {
        int doublings = Math.min(Math.max(failures - FREE_ATTEMPTS, 0), 10);
        Duration wait = FIRST_LOCK.multipliedBy(1L << doublings);
        return wait.compareTo(LONGEST_LOCK) > 0 ? LONGEST_LOCK : wait;
    }

    private void clearWrongGuesses(SupplierUser portalUser) {
        if (portalUser.getShopSigninFailures() != 0 || portalUser.getShopSigninLockedUntil() != null) {
            portalUser.setShopSigninFailures(0);
            portalUser.setShopSigninLockedUntil(null);
            supplierUserRepository.save(portalUser);
        }
    }
}
