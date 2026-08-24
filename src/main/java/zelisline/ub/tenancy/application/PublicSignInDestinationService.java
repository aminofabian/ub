package zelisline.ub.tenancy.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.domain.KenyanPhoneForms;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.marketplace.application.SupplierSignInDoorService;
import zelisline.ub.tenancy.api.dto.PublicSignInDestinationResponse;
import zelisline.ub.tenancy.api.dto.PublicShopsSearchResponse;

/**
 * Apex identity → destinations. Given an email (open lookup, same privacy as
 * resolve-by-email) or a platform-verified phone, returns every shop/portal
 * the person can open, tagged with the door (staff till, shopper account,
 * supplier portal, or an unclaimed supplier portal) so the landing sheet never
 * asks "shopper or merchant?".
 */
@Service
@RequiredArgsConstructor
public class PublicSignInDestinationService {

    static final int MAX_RESULTS = 12;

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final SupplierSignInDoorService supplierSignInDoorService;
    private final CustomerPhoneRepository customerPhoneRepository;
    private final PublicShopsSearchService publicShopsSearchService;

    @Transactional(readOnly = true)
    public List<PublicSignInDestinationResponse> byEmail(String rawEmail) {
        if (rawEmail == null || rawEmail.isBlank()) {
            return List.of();
        }
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        LinkedHashMap<String, PublicSignInDestinationResponse> out = new LinkedHashMap<>();

        for (User user : userRepository.findAllActiveByEmail(email)) {
            addUserDestination(out, user);
        }

        supplierSignInDoorService.byEmail(email).ifPresent(door -> putSupplier(out, door));

        return List.copyOf(out.values());
    }

    /**
     * Builds destinations for a phone that has already been platform-verified
     * (token consumed by the caller). Merges customer-shop history, staff/buyer
     * memberships, and an active supplier account on that number.
     */
    @Transactional(readOnly = true)
    public List<PublicSignInDestinationResponse> byVerifiedPhone(String normalizedPhone) {
        if (normalizedPhone == null || normalizedPhone.isBlank()) {
            return List.of();
        }
        LinkedHashMap<String, PublicSignInDestinationResponse> out = new LinkedHashMap<>();

        List<String> candidates = KenyanPhoneForms.lookupCandidates(normalizedPhone);

        // Shopper history first — most common apex path.
        List<String> shopperBusinessIds =
                customerPhoneRepository.findDistinctBusinessIdByPhones(candidates);
        for (PublicShopsSearchResponse row : publicShopsSearchService.byBusinessIds(shopperBusinessIds)) {
            putShop(out, row, PublicSignInDestinationResponse.DOOR_SHOPPER);
        }

        for (User user : userRepository.findAllActiveByPhoneIn(candidates)) {
            addUserDestination(out, user);
        }

        supplierSignInDoorService.byVerifiedPhone(candidates)
                .ifPresent(door -> putSupplier(out, door));

        return List.copyOf(out.values());
    }

    private void addUserDestination(
            LinkedHashMap<String, PublicSignInDestinationResponse> out,
            User user
    ) {
        if (out.size() >= MAX_RESULTS) {
            return;
        }
        String door = doorForRole(user.getRoleId());
        publicShopsSearchService.byBusinessIds(List.of(user.getBusinessId())).stream()
                .findFirst()
                .ifPresent(row -> putShop(out, row, door));
    }

    private String doorForRole(String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return PublicSignInDestinationResponse.DOOR_STAFF;
        }
        return roleRepository.findByIdAndDeletedAtIsNull(roleId)
                .map(Role::getRoleKey)
                .map(key -> "buyer".equalsIgnoreCase(key)
                        ? PublicSignInDestinationResponse.DOOR_SHOPPER
                        : PublicSignInDestinationResponse.DOOR_STAFF)
                .orElse(PublicSignInDestinationResponse.DOOR_STAFF);
    }

    private static void putShop(
            LinkedHashMap<String, PublicSignInDestinationResponse> out,
            PublicShopsSearchResponse row,
            String door
    ) {
        if (out.size() >= MAX_RESULTS || row == null || row.slug() == null || row.slug().isBlank()) {
            return;
        }
        String key = door + ":" + row.slug().trim().toLowerCase(Locale.ROOT);
        out.putIfAbsent(key, new PublicSignInDestinationResponse(
                row.slug(),
                row.name(),
                row.logoUrl(),
                row.primaryHost(),
                door,
                null));
    }

    private static void putSupplier(
            LinkedHashMap<String, PublicSignInDestinationResponse> out,
            SupplierSignInDoorService.SupplierDoor supplier
    ) {
        String door = supplier.claimed()
                ? PublicSignInDestinationResponse.DOOR_SUPPLIER
                : PublicSignInDestinationResponse.DOOR_SUPPLIER_CLAIM;
        out.putIfAbsent(
                door + ":portal",
                new PublicSignInDestinationResponse(
                        null,
                        supplier.name(),
                        null,
                        null,
                        door,
                        supplier.hint()));
    }
}
