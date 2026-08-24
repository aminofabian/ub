package zelisline.ub.tenancy.application;

import java.util.ArrayList;
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
import zelisline.ub.marketplace.domain.SupplierUser;
import zelisline.ub.marketplace.repository.SupplierUserRepository;
import zelisline.ub.tenancy.api.dto.PublicSignInDestinationResponse;
import zelisline.ub.tenancy.api.dto.PublicShopsSearchResponse;

/**
 * Apex identity → destinations. Given an email (open lookup, same privacy as
 * resolve-by-email) or a platform-verified phone, returns every shop/portal
 * the person can open, tagged with the door (staff till, shopper account, or
 * supplier portal) so the landing sheet never asks "shopper or merchant?".
 */
@Service
@RequiredArgsConstructor
public class PublicSignInDestinationService {

    static final int MAX_RESULTS = 12;

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final SupplierUserRepository supplierUserRepository;
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

        supplierUserRepository.findByEmail(email)
                .filter(SupplierUser::isActive)
                .ifPresent(supplier -> putSupplier(out, supplier));

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

        for (String candidate : candidates) {
            supplierUserRepository.findByPhone(candidate)
                    .filter(SupplierUser::isActive)
                    .ifPresent(supplier -> putSupplier(out, supplier));
        }

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
                door));
    }

    private static void putSupplier(
            LinkedHashMap<String, PublicSignInDestinationResponse> out,
            SupplierUser supplier
    ) {
        if (out.size() >= MAX_RESULTS) {
            return;
        }
        String name = supplier.getName() == null || supplier.getName().isBlank()
                ? "Supplier portal"
                : supplier.getName().trim();
        out.putIfAbsent(
                PublicSignInDestinationResponse.DOOR_SUPPLIER + ":portal",
                new PublicSignInDestinationResponse(
                        null,
                        name,
                        null,
                        null,
                        PublicSignInDestinationResponse.DOOR_SUPPLIER));
    }
}
