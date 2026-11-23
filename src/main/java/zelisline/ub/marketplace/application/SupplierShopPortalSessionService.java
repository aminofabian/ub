package zelisline.ub.marketplace.application;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.marketplace.api.dto.SupplierPortalLoginResponse;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.domain.MarketplaceSupplierStatuses;
import zelisline.ub.marketplace.domain.SupplierUser;
import zelisline.ub.marketplace.domain.SupplierUserRoles;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.marketplace.repository.SupplierUserRepository;
import zelisline.ub.platform.application.PlatformSupplierPortalSettingsService;

/**
 * Opens the supplier portal from inside the supplier's shop, already signed in. The shop user is already
 * authenticated by the shop; this hands them a normal portal session for the portal user that stands for them,
 * making that portal user the first time. So the portal's pages (shops supplied, deliveries, messages, statements,
 * reports) work from the shop without a second sign-in, and without changing either login.
 *
 * A shop owner or admin gets a portal admin; anyone else gets portal staff. A portal user is only ever the one
 * linked to this shop user: nobody is joined to an existing portal account by email.
 */
@Service
@RequiredArgsConstructor
public class SupplierShopPortalSessionService {

    private static final Set<String> SHOP_ADMIN_ROLES = Set.of("owner", "admin");

    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final SupplierUserRepository supplierUserRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final SupplierPortalSessionService portalSessionService;
    private final PlatformSupplierPortalSettingsService portalSettingsService;

    @Transactional
    public SupplierPortalLoginResponse open(HttpServletRequest http, String businessId, String shopUserId) {
        portalSettingsService.requirePortalEnabled();
        MarketplaceSupplier page = marketplaceSupplierRepository.findFirstByBusinessId(businessId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "This shop has no marketplace page"));
        if (MarketplaceSupplierStatuses.SUSPENDED.equalsIgnoreCase(page.getStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This supplier account is suspended");
        }
        User shopUser = userRepository.findById(shopUserId)
                .filter(u -> u.getDeletedAt() == null && businessId.equals(u.getBusinessId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this shop"));

        SupplierUser portalUser = supplierUserRepository
                .findFirstByMarketplaceSupplierIdAndBusinessUserId(page.getId(), shopUser.getId())
                .orElseGet(() -> makePortalUser(page, shopUser));
        if (!portalUser.isActive()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your supplier portal access is switched off");
        }
        return portalSessionService.issueLogin(portalUser, http);
    }

    private SupplierUser makePortalUser(MarketplaceSupplier page, User shopUser) {
        String roleKey = roleRepository.findById(shopUser.getRoleId())
                .map(role -> role.getRoleKey() == null ? "" : role.getRoleKey().trim().toLowerCase(Locale.ROOT))
                .filter(SHOP_ADMIN_ROLES::contains)
                .map(key -> SupplierUserRoles.ADMIN)
                .orElse(SupplierUserRoles.STAFF);

        SupplierUser user = new SupplierUser();
        user.setMarketplaceSupplierId(page.getId());
        user.setName(shopUser.getName() == null || shopUser.getName().isBlank() ? "Shop user" : shopUser.getName().trim());
        // The email is taken only when no portal account holds it: an account is never shared by address.
        String email = shopUser.getEmail() == null ? null : shopUser.getEmail().trim().toLowerCase(Locale.ROOT);
        if (email != null && !email.isBlank() && !email.endsWith(".invalid")
                && supplierUserRepository.findByEmail(email).isEmpty()) {
            user.setEmail(email);
        }
        // A password nobody is told. This portal user is entered from the shop, not from the portal's login.
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID() + "-" + UUID.randomUUID()));
        user.setRoleKey(roleKey);
        user.setActive(true);
        user.setBusinessUserId(shopUser.getId());
        return supplierUserRepository.save(user);
    }
}
