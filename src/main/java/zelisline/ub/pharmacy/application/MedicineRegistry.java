package zelisline.ub.pharmacy.application;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.pharmacy.domain.ItemMedicineDetails;
import zelisline.ub.pharmacy.repository.ItemMedicineDetailsRepository;

/**
 * Read side of the medicine marker, for the modules that enforce medicine rules (inventory,
 * purchasing, sales, storefront). A sold line can be a pack variant whose stock lives on its
 * parent, so lookups take every item id that stands for the product: the sold item, its parent
 * and its stock holder. The sold item's own row wins, then the parent's, then the holder's.
 */
@Service
@RequiredArgsConstructor
public class MedicineRegistry {

    private final ItemMedicineDetailsRepository repository;

    @Transactional(readOnly = true)
    public Optional<ItemMedicineDetails> find(String businessId, Collection<String> candidateItemIds) {
        Set<String> ids = clean(candidateItemIds);
        if (businessId == null || ids.isEmpty()) {
            return Optional.empty();
        }
        Map<String, ItemMedicineDetails> byId = new LinkedHashMap<>();
        for (ItemMedicineDetails d : repository.findByBusinessIdAndItemIdIn(businessId, ids)) {
            byId.put(d.getItemId(), d);
        }
        for (String id : ids) {
            ItemMedicineDetails d = byId.get(id);
            if (d != null) {
                return Optional.of(d);
            }
        }
        return Optional.empty();
    }

    @Transactional(readOnly = true)
    public Optional<ItemMedicineDetails> find(String businessId, Item soldItem, String stockHolderItemId) {
        return find(businessId, candidateIds(soldItem, stockHolderItemId));
    }

    @Transactional(readOnly = true)
    public boolean isMedicine(String businessId, Collection<String> candidateItemIds) {
        Set<String> ids = clean(candidateItemIds);
        if (businessId == null || ids.isEmpty()) {
            return false;
        }
        return repository.existsByBusinessIdAndItemIdIn(businessId, ids);
    }

    @Transactional(readOnly = true)
    public boolean isMedicine(String businessId, Item soldItem, String stockHolderItemId) {
        return isMedicine(businessId, candidateIds(soldItem, stockHolderItemId));
    }

    /**
     * Which of these products are medicines, by their own id or the product they are a pack of.
     * Used to keep what a named customer bought out of analytics, audiences and messages
     * (ADR 0034, Phase 0): a medicine tied to a name is health data whatever its class.
     */
    @Transactional(readOnly = true)
    public Set<String> medicineItemIds(String businessId, Collection<Item> items) {
        Set<String> out = new LinkedHashSet<>();
        if (businessId == null || items == null || items.isEmpty()) {
            return out;
        }
        Set<String> candidates = new LinkedHashSet<>();
        for (Item item : items) {
            if (item != null) {
                candidates.addAll(candidateIds(item, null));
            }
        }
        if (candidates.isEmpty()) {
            return out;
        }
        Set<String> marked = new LinkedHashSet<>();
        for (ItemMedicineDetails d : repository.findByBusinessIdAndItemIdIn(businessId, candidates)) {
            marked.add(d.getItemId());
        }
        for (Item item : items) {
            if (item == null || item.getId() == null) {
                continue;
            }
            for (String id : candidateIds(item, null)) {
                if (marked.contains(id)) {
                    out.add(item.getId());
                    break;
                }
            }
        }
        return out;
    }

    /** Medicine rows keyed by item id, for the ids given (no parent or holder resolution). */
    @Transactional(readOnly = true)
    public Map<String, ItemMedicineDetails> byItemId(String businessId, Collection<String> itemIds) {
        Set<String> ids = clean(itemIds);
        Map<String, ItemMedicineDetails> out = new LinkedHashMap<>();
        if (businessId == null || ids.isEmpty()) {
            return out;
        }
        for (ItemMedicineDetails d : repository.findByBusinessIdAndItemIdIn(businessId, ids)) {
            out.put(d.getItemId(), d);
        }
        return out;
    }

    /**
     * Ids of the given items that the public storefront may show but not sell: medicines whose
     * class is anything but over-the-counter. A pack variant follows its parent's class.
     */
    @Transactional(readOnly = true)
    public Set<String> counterOnlyItemIds(String businessId, Collection<Item> items) {
        Set<String> out = new LinkedHashSet<>();
        if (businessId == null || items == null || items.isEmpty()) {
            return out;
        }
        Set<String> lookup = new LinkedHashSet<>();
        for (Item i : items) {
            lookup.addAll(candidateIds(i, null));
        }
        Map<String, ItemMedicineDetails> rows = byItemId(businessId, lookup);
        if (rows.isEmpty()) {
            return out;
        }
        for (Item i : items) {
            for (String c : candidateIds(i, null)) {
                ItemMedicineDetails d = rows.get(c);
                if (d != null) {
                    if (d.getDispensingClass().blockedOnline()) {
                        out.add(i.getId());
                    }
                    break;
                }
            }
        }
        return out;
    }

    /** Every item id of the business that is a non-OTC medicine or a pack variant of one (capped). */
    @Transactional(readOnly = true)
    public List<String> counterOnlyItemIds(String businessId) {
        if (businessId == null) {
            return List.of();
        }
        return repository.findCounterOnlyItemIds(
                businessId,
                zelisline.ub.pharmacy.domain.DispensingClass.OTC,
                org.springframework.data.domain.PageRequest.of(0, 5000));
    }

    public static List<String> candidateIds(Item soldItem, String stockHolderItemId) {
        Set<String> ids = new LinkedHashSet<>();
        if (soldItem != null) {
            if (soldItem.getId() != null) {
                ids.add(soldItem.getId());
            }
            if (soldItem.getVariantOfItemId() != null && !soldItem.getVariantOfItemId().isBlank()) {
                ids.add(soldItem.getVariantOfItemId());
            }
        }
        if (stockHolderItemId != null && !stockHolderItemId.isBlank()) {
            ids.add(stockHolderItemId);
        }
        return List.copyOf(ids);
    }

    private static Set<String> clean(Collection<String> raw) {
        Set<String> ids = new LinkedHashSet<>();
        if (raw != null) {
            for (String id : raw) {
                if (id != null && !id.isBlank()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }
}
