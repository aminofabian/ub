package zelisline.ub.pharmacy.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.application.PackageVariantStockResolver;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.pharmacy.api.dto.TillMedicineDtos.GenericMatch;
import zelisline.ub.pharmacy.api.dto.TillMedicineDtos.TillMedicine;
import zelisline.ub.pharmacy.domain.ItemMedicineDetails;
import zelisline.ub.pharmacy.domain.PharmacyProfile;
import zelisline.ub.pharmacy.repository.ItemMedicineDetailsRepository;
import zelisline.ub.pharmacy.repository.PharmacyProfileRepository;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;

/**
 * What the till shows about medicines before anything is charged: class, generic name, and the
 * lot and expiry the next unit would come from. Read only. The sale itself is still checked by
 * {@link DispensingGate}; nothing here lets a sale through.
 */
@Service
@RequiredArgsConstructor
public class TillMedicineService {

    private static final ZoneId SHOP_ZONE = ZoneId.of("Africa/Nairobi");
    private static final int DEFAULT_SHORT_DATED_DAYS = 90;
    private static final int MAX_GENERIC_MATCHES = 8;

    private final DispensingGate dispensingGate;
    private final ItemRepository itemRepository;
    private final PackageVariantStockResolver packageVariantStockResolver;
    private final MedicineRegistry medicineRegistry;
    private final ItemMedicineDetailsRepository medicineDetailsRepository;
    private final InventoryBatchRepository inventoryBatchRepository;
    private final PharmacyProfileRepository pharmacyProfileRepository;

    /** The medicines among {@code itemIds}; items that are not medicines are left out. */
    @Transactional(readOnly = true)
    public List<TillMedicine> shelf(String businessId, String branchId, Collection<String> itemIds) {
        List<DispensingGate.ItemStanding> standings = dispensingGate.standings(businessId, itemIds);
        if (standings.isEmpty()) {
            return List.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (DispensingGate.ItemStanding s : standings) {
            ids.add(s.itemId());
        }
        Map<String, List<String>> candidates = new LinkedHashMap<>();
        Map<String, String> holderByItem = new LinkedHashMap<>();
        Set<String> lookup = new LinkedHashSet<>();
        for (Item item : itemRepository.findByIdInAndBusinessIdAndDeletedAtIsNull(ids, businessId)) {
            String holder = packageVariantStockResolver.stockHolderItemId(item);
            holderByItem.put(item.getId(), holder == null ? item.getId() : holder);
            List<String> c = MedicineRegistry.candidateIds(item, holder);
            candidates.put(item.getId(), c);
            lookup.addAll(c);
        }
        Map<String, ItemMedicineDetails> rows = medicineRegistry.byItemId(businessId, lookup);
        Map<String, InventoryBatch> next = nextBatchByHolder(
                businessId, branchId, new LinkedHashSet<>(holderByItem.values()));
        int shortDays = pharmacyProfileRepository.findById(businessId)
                .map(PharmacyProfile::getShortDatedDays)
                .orElse(DEFAULT_SHORT_DATED_DAYS);
        LocalDate shortLimit = LocalDate.now(SHOP_ZONE).plusDays(Math.max(0, shortDays));
        List<TillMedicine> out = new ArrayList<>();
        for (DispensingGate.ItemStanding s : standings) {
            ItemMedicineDetails d = null;
            for (String c : candidates.getOrDefault(s.itemId(), List.of())) {
                d = rows.get(c);
                if (d != null) {
                    break;
                }
            }
            InventoryBatch batch = next.get(holderByItem.get(s.itemId()));
            LocalDate expiry = batch == null ? null : batch.getExpiryDate();
            out.add(new TillMedicine(
                    s.itemId(),
                    s.dispensingClass().name(),
                    s.status(),
                    d == null ? null : d.getGenericName(),
                    d == null ? null : d.getStrength(),
                    d == null ? null : d.getDosageForm(),
                    batch == null ? null : batch.getLotNumber(),
                    expiry,
                    expiry != null && !expiry.isAfter(shortLimit)));
        }
        return out;
    }

    /**
     * Sellable medicines whose generic name contains {@code q}, so a cashier who types the generic
     * finds every brand of it. Unclassified medicines are included: the till shows them as blocked.
     */
    @Transactional(readOnly = true)
    public List<GenericMatch> searchGeneric(String businessId, String q) {
        if (q == null || q.strip().length() < 3) {
            return List.of();
        }
        String like = "%" + q.strip().toLowerCase(Locale.ROOT) + "%";
        List<GenericMatch> out = new ArrayList<>();
        for (Object[] row : medicineDetailsRepository.searchByGenericName(
                businessId, like, PageRequest.of(0, MAX_GENERIC_MATCHES))) {
            ItemMedicineDetails d = (ItemMedicineDetails) row[0];
            Item item = (Item) row[1];
            out.add(new GenericMatch(item.getId(), item.getName(), item.getSku(), item.getBarcode(), d.getGenericName()));
        }
        return out;
    }

    /**
     * The batch the picker would draw from first for each stock holder: active, in stock, not
     * expired, and with an expiry date (a medicine batch without one is never sold).
     */
    private Map<String, InventoryBatch> nextBatchByHolder(String businessId, String branchId, Set<String> holderIds) {
        Map<String, InventoryBatch> out = new LinkedHashMap<>();
        if (branchId == null || branchId.isBlank() || holderIds.isEmpty()) {
            return out;
        }
        List<InventoryBatch> batches = inventoryBatchRepository.findActiveBatchesForItems(
                businessId, branchId.strip(), InventoryConstants.BATCH_STATUS_ACTIVE,
                new ArrayList<>(holderIds), BigDecimal.ZERO);
        Comparator<InventoryBatch> firstOut = Comparator
                .comparing(InventoryBatch::getExpiryDate)
                .thenComparing(InventoryBatch::getReceivedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(InventoryBatch::getId);
        for (InventoryBatch b : batches) {
            if (b.getExpiryDate() == null) {
                continue;
            }
            InventoryBatch current = out.get(b.getItemId());
            if (current == null || firstOut.compare(b, current) < 0) {
                out.put(b.getItemId(), b);
            }
        }
        return out;
    }
}
