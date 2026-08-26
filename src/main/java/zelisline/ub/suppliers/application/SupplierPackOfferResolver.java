package zelisline.ub.suppliers.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.ItemPackOption;
import zelisline.ub.catalog.repository.ItemPackOptionRepository;
import zelisline.ub.suppliers.domain.SupplierProductPackOffer;
import zelisline.ub.suppliers.repository.SupplierProductPackOfferRepository;

/**
 * Single source of truth for merging item pack options with per-link offer rows
 * (docs/MULTI_PACK_OPTIONS_SCOPE.md §5.2): an active offer overrides the pack price,
 * an inactive offer opts the shape out, and missing rows fall back to the item default.
 * Consumed by the public stall payload and the supplier link responses.
 */
@Component
@RequiredArgsConstructor
public class SupplierPackOfferResolver {

    private final ItemPackOptionRepository itemPackOptionRepository;
    private final SupplierProductPackOfferRepository supplierProductPackOfferRepository;

    public record ResolvedPack(
            String optionId,
            String label,
            String packUnit,
            BigDecimal unitsPerPack,
            /** Price for ONE pack; null = ask. */
            BigDecimal unitPrice,
            /** Derived unitPrice / unitsPerPack for display; null when unitPrice is null. */
            BigDecimal eachPrice
    ) {
    }

    /** Resolve the offered packs for every link in {@code linkIdToItemId}. Unit-only links map to empty lists. */
    public Map<String, List<ResolvedPack>> resolveByLink(Map<String, String> linkIdToItemId) {
        Map<String, List<ResolvedPack>> out = new LinkedHashMap<>();
        if (linkIdToItemId == null || linkIdToItemId.isEmpty()) {
            return out;
        }
        List<String> distinctItems = linkIdToItemId.values().stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<String> distinctLinks = linkIdToItemId.keySet().stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (distinctItems.isEmpty() || distinctLinks.isEmpty()) {
            return out;
        }
        Map<String, List<ItemPackOption>> optionsByItem = new HashMap<>();
        for (ItemPackOption option :
                itemPackOptionRepository.findByItemIdInAndActiveTrueOrderBySortOrderAscIdAsc(distinctItems)) {
            optionsByItem.computeIfAbsent(option.getItemId(), k -> new ArrayList<>()).add(option);
        }
        Map<String, List<SupplierProductPackOffer>> offersByLink = new HashMap<>();
        for (SupplierProductPackOffer offer : supplierProductPackOfferRepository.findBySupplierProductIdIn(distinctLinks)) {
            offersByLink.computeIfAbsent(offer.getSupplierProductId(), k -> new ArrayList<>()).add(offer);
        }
        for (Map.Entry<String, String> entry : linkIdToItemId.entrySet()) {
            out.put(entry.getKey(), merge(
                    optionsByItem.getOrDefault(entry.getValue(), List.of()),
                    offersByLink.getOrDefault(entry.getKey(), List.of())));
        }
        return out;
    }

    private static List<ResolvedPack> merge(
            List<ItemPackOption> options,
            List<SupplierProductPackOffer> offers
    ) {
        if (options.isEmpty()) {
            return List.of();
        }
        Map<String, SupplierProductPackOffer> offerByOption = new HashMap<>();
        for (SupplierProductPackOffer offer : offers) {
            offerByOption.put(offer.getItemPackOptionId(), offer);
        }
        List<ResolvedPack> out = new ArrayList<>(options.size());
        for (ItemPackOption option : options) {
            SupplierProductPackOffer offer = offerByOption.get(option.getId());
            if (offer != null && !offer.isActive()) {
                continue;
            }
            BigDecimal unitPrice = offer != null && offer.getPackPrice() != null
                    ? offer.getPackPrice()
                    : option.getDefaultPackPrice();
            BigDecimal eachPrice = unitPrice != null
                    ? unitPrice.divide(option.getUnitsPerPack(), 2, RoundingMode.HALF_UP)
                    : null;
            out.add(new ResolvedPack(
                    option.getId(),
                    option.getLabel(),
                    option.getPackUnit(),
                    option.getUnitsPerPack(),
                    unitPrice,
                    eachPrice));
        }
        return out;
    }
}
