package zelisline.ub.marketplace.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.api.dto.CreateUserRequest;
import zelisline.ub.identity.api.dto.UserResponse;
import zelisline.ub.identity.application.IdentityService;
import zelisline.ub.identity.application.UserInvitationService;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.marketplace.api.dto.ShopTeamInviteResponse;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.domain.SupplierUser;
import zelisline.ub.marketplace.domain.SupplierUserRoles;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.marketplace.repository.SupplierUserRepository;

/**
 * Brings a supplier's portal team into its shop (P1-3, the safe part). Each active portal user with an email is
 * invited to the shop exactly as the shop invites any staff member: the same create-user call and the same
 * invitation email, from which they set their own shop password. Neither sign-in is changed, so nobody can be
 * locked out by this. A portal admin becomes a shop admin; other portal users get the trade desk role.
 *
 * Not transactional on purpose: one person's failure (a plan's staff limit, say) must not undo the others.
 */
@Service
@RequiredArgsConstructor
public class ShopSupplierTeamService {

    static final String ROLE_SHOP_ADMIN = "admin";
    static final String ROLE_TRADE_DESK = "supplier_trade_desk";

    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final SupplierUserRepository supplierUserRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final IdentityService identityService;
    private final UserInvitationService userInvitationService;

    public ShopTeamInviteResponse invitePortalTeam(HttpServletRequest http, String businessId) {
        MarketplaceSupplier page = marketplaceSupplierRepository.findFirstByBusinessId(businessId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "This shop has no marketplace page"));
        List<ShopTeamInviteResponse.Row> rows = new ArrayList<>();
        for (SupplierUser portalUser : supplierUserRepository.findByMarketplaceSupplierIdAndActiveTrue(page.getId())) {
            rows.add(inviteOne(http, businessId, portalUser));
        }
        return new ShopTeamInviteResponse(rows);
    }

    private ShopTeamInviteResponse.Row inviteOne(HttpServletRequest http, String businessId, SupplierUser portalUser) {
        String name = portalUser.getName() == null || portalUser.getName().isBlank()
                ? "Supplier team member"
                : portalUser.getName().trim();
        String email = portalUser.getEmail() == null ? "" : portalUser.getEmail().trim().toLowerCase(Locale.ROOT);
        if (email.isEmpty()) {
            return new ShopTeamInviteResponse.Row(name, null, "no_email", "Add an email in the supplier portal first");
        }
        try {
            var existing = userRepository.findByBusinessIdAndEmailAndDeletedAtIsNull(businessId, email);
            if (existing.isPresent()) {
                // Left as it is: this person already has shop details of their own. Joining a portal user to an
                // existing shop account by email alone would let a portal password open that account.
                return new ShopTeamInviteResponse.Row(name, email, "already_in_shop", null);
            }
            String roleKey = SupplierUserRoles.isAdmin(portalUser.getRoleKey()) ? ROLE_SHOP_ADMIN : ROLE_TRADE_DESK;
            Role role = roleRepository.findSystemRoleByKey(roleKey)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.CONFLICT, "The " + roleKey + " role is missing"));
            UserResponse created = identityService.createUser(businessId, new CreateUserRequest(
                    email, name, portalUser.getPhone(), role.getId(), null, null, null, null, true, null));
            userInvitationService.sendInvite(http, businessId, created.id());
            link(portalUser, created.id());
            return new ShopTeamInviteResponse.Row(name, email, "invited", null);
        } catch (ResponseStatusException e) {
            return new ShopTeamInviteResponse.Row(name, email, "failed", e.getReason());
        } catch (RuntimeException e) {
            return new ShopTeamInviteResponse.Row(name, email, "failed", "Could not invite this person");
        }
    }

    private void link(SupplierUser portalUser, String shopUserId) {
        if (!shopUserId.equals(portalUser.getBusinessUserId())) {
            portalUser.setBusinessUserId(shopUserId);
            supplierUserRepository.save(portalUser);
        }
    }
}
