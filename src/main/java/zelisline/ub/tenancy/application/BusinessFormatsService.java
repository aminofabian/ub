package zelisline.ub.tenancy.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.posdraft.application.OrderTicketService;
import zelisline.ub.tenancy.api.dto.StoreTypeCodes;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.repository.BranchRepository;

/**
 * Change which shop formats a business runs, after onboarding. Adding a format (a restaurant that
 * also opens a bar, or lets rooms) just widens the set; the tools each format unlocks derive from
 * the store types, so they appear on their own. Removing a format is guarded: a format that still
 * has live work on the floor cannot be dropped out from under it.
 *
 * <p>The codes themselves are the fixed enum in {@link StoreTypeCodes}; this service only changes
 * which of them a business has. The write (profile + onboarding answers + audit) is delegated to
 * {@link TenancyService#changeStoreTypesForTenant}, so storage stays in one place.
 */
@Service
@RequiredArgsConstructor
public class BusinessFormatsService {

    private final TenancyService tenancyService;
    private final OrderTicketService orderTicketService;
    private final BranchRepository branchRepository;

    public record FormatState(String code, boolean selected, boolean removable, String lockedReason) {}

    /** Current formats plus, for each known code, whether it is on and whether it could be removed now. */
    @Transactional(readOnly = true)
    public List<FormatState> describe(String businessId) {
        Set<String> current = normalized(tenancyService.currentStoreTypesForTenant(businessId));
        boolean foodOpen = hasOpenFoodOrders(businessId);
        boolean staysOpen = hasOpenStays(businessId);
        List<FormatState> out = new ArrayList<>();
        for (String code : StoreTypeCodes.ALL) {
            boolean selected = current.contains(code);
            // "removable" asks what removing this one would leave, so test against the set without it.
            Set<String> remaining = new LinkedHashSet<>(current);
            remaining.remove(code);
            String reason = selected ? removalBlockReason(code, remaining, foodOpen, staysOpen) : null;
            out.add(new FormatState(code, selected, selected && reason == null, reason));
        }
        return out;
    }

    /**
     * Set the business's formats to {@code desired} (the full new set). Validates the codes, forbids
     * an empty set, and blocks removing a format that still has live work. Returns the new set.
     */
    @Transactional
    public List<String> change(String businessId, List<String> desired) {
        Set<String> wanted = normalized(desired);
        if (wanted.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose at least one business format.");
        }
        for (String code : wanted) {
            if (!StoreTypeCodes.isValidCode(code)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown business format: " + code);
            }
        }
        Set<String> current = normalized(tenancyService.currentStoreTypesForTenant(businessId));
        Set<String> removed = new LinkedHashSet<>(current);
        removed.removeAll(wanted);
        if (!removed.isEmpty()) {
            boolean foodOpen = hasOpenFoodOrders(businessId);
            boolean staysOpen = hasOpenStays(businessId);
            for (String code : removed) {
                String reason = removalBlockReason(code, wanted, foodOpen, staysOpen);
                if (reason != null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, reason);
                }
            }
        }
        return tenancyService.changeStoreTypesForTenant(businessId, List.copyOf(wanted));
    }

    // --- guard ---

    /**
     * Why a format cannot be removed right now, or null when it can. {@code remaining} is the set the
     * business would be left with, so we only block a food removal when <em>no</em> food format stays
     * (dropping "bar" from a bar+restaurant is fine — the floor stays).
     */
    private String removalBlockReason(String code, Set<String> remaining, boolean foodOpen, boolean staysOpen) {
        if (StoreTypeCodes.LODGING_TYPES.contains(code) && staysOpen) {
            return "There are guests still checked in. Check them out before removing lodging.";
        }
        if (StoreTypeCodes.FOOD_TYPES.contains(code)
                && foodOpen
                && remaining.stream().noneMatch(StoreTypeCodes.FOOD_TYPES::contains)) {
            return "There are open orders on the floor. Settle or close them before removing food service.";
        }
        return null;
    }

    private boolean hasOpenFoodOrders(String businessId) {
        return anyOpenOrder(businessId,
                channel -> !OrderTicketService.CHANNEL_LODGING.equals(channel));
    }

    private boolean hasOpenStays(String businessId) {
        return anyOpenOrder(businessId,
                channel -> OrderTicketService.CHANNEL_LODGING.equals(channel));
    }

    private boolean anyOpenOrder(String businessId, java.util.function.Predicate<String> channelMatches) {
        for (Branch branch : branchRepository.findByBusinessIdAndDeletedAtIsNullOrderByNameAsc(businessId)) {
            for (OrderTicketService.Order order : orderTicketService.openOrders(businessId, branch.getId())) {
                if (channelMatches.test(order.channel())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Set<String> normalized(List<String> codes) {
        Set<String> out = new LinkedHashSet<>();
        if (codes != null) {
            for (String raw : codes) {
                if (raw != null && !raw.isBlank()) {
                    out.add(raw.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }
}
