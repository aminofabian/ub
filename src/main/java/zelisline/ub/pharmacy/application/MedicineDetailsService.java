package zelisline.ub.pharmacy.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.catalog.application.PackageVariantStockResolver;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.pharmacy.api.dto.BulkClassifyMedicinesRequest;
import zelisline.ub.pharmacy.api.dto.BulkMarkMedicinesRequest;
import zelisline.ub.pharmacy.api.dto.BulkMedicinesResult;
import zelisline.ub.pharmacy.api.dto.MedicineClassCountsResponse;
import zelisline.ub.pharmacy.api.dto.MedicineDetailsResponse;
import zelisline.ub.pharmacy.api.dto.MedicinePageResponse;
import zelisline.ub.pharmacy.api.dto.UpsertMedicineDetailsRequest;
import zelisline.ub.pharmacy.domain.DispensingClass;
import zelisline.ub.pharmacy.domain.ItemMedicineDetails;
import zelisline.ub.pharmacy.repository.ItemMedicineDetailsRepository;

/**
 * Marks catalogue items as medicines and keeps their attributes and dispensing class.
 *
 * <p>Marking an item forces {@code has_expiry} on it and on its stock holder, because the batch
 * picker only sells earliest-expiry-first for items that carry that flag.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MedicineDetailsService {

    private static final int MAX_PAGE_SIZE = 200;

    private final ItemMedicineDetailsRepository repository;
    private final ItemRepository itemRepository;
    private final PackageVariantStockResolver packageVariantStockResolver;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    @Transactional(readOnly = true)
    public MedicinePageResponse list(String businessId, String dispensingClass, String q, int page, int size) {
        boolean anyClass = dispensingClass == null || dispensingClass.isBlank();
        // The class parameter is never null: a null enum bind is not portable across databases.
        DispensingClass cls = anyClass ? DispensingClass.UNCLASSIFIED : DispensingClass.parse(dispensingClass);
        String like = q == null || q.isBlank()
                ? null
                : "%" + q.strip().toLowerCase(Locale.ROOT) + "%";
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        List<MedicineDetailsResponse> rows = new ArrayList<>();
        for (Object[] row : repository.search(businessId, anyClass, cls, like, PageRequest.of(safePage, safeSize))) {
            rows.add(toResponse((ItemMedicineDetails) row[0], (Item) row[1]));
        }
        long total = repository.countSearch(businessId, anyClass, cls, like);
        return new MedicinePageResponse(rows, total, safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public MedicineClassCountsResponse counts(String businessId) {
        Map<DispensingClass, Long> by = new LinkedHashMap<>();
        for (Object[] row : repository.countByClass(businessId)) {
            by.put((DispensingClass) row[0], ((Number) row[1]).longValue());
        }
        long unclassified = by.getOrDefault(DispensingClass.UNCLASSIFIED, 0L);
        long otc = by.getOrDefault(DispensingClass.OTC, 0L);
        long pom = by.getOrDefault(DispensingClass.PRESCRIPTION_ONLY, 0L);
        long controlled = by.getOrDefault(DispensingClass.CONTROLLED, 0L);
        return new MedicineClassCountsResponse(unclassified + otc + pom + controlled, unclassified, otc, pom, controlled);
    }

    /** The medicine row for an item, or 404 when the item is not marked as a medicine. */
    @Transactional(readOnly = true)
    public MedicineDetailsResponse get(String businessId, String itemId) {
        Item item = requireItem(businessId, itemId);
        ItemMedicineDetails d = repository.findByItemIdAndBusinessId(itemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not marked as a medicine"));
        return toResponse(d, item);
    }

    @Transactional
    public MedicineDetailsResponse upsert(
            String businessId,
            String itemId,
            UpsertMedicineDetailsRequest req,
            String userId
    ) {
        Item item = requireItem(businessId, itemId);
        ItemMedicineDetails d = repository.findByItemIdAndBusinessId(itemId, businessId).orElse(null);
        boolean created = d == null;
        DispensingClass before = created ? null : d.getDispensingClass();
        if (created) {
            d = new ItemMedicineDetails();
            d.setItemId(itemId);
            d.setBusinessId(businessId);
        }
        d.setGenericName(blankToNull(req.genericName()));
        d.setStrength(blankToNull(req.strength()));
        d.setDosageForm(blankToNull(req.dosageForm()));
        d.setPackSize(blankToNull(req.packSize()));
        d.setManufacturer(blankToNull(req.manufacturer()));
        d.setRegistrationNo(blankToNull(req.registrationNo()));
        d.setStorageNote(blankToNull(req.storageNote()));
        if (req.restockOnRefund() != null) {
            d.setRestockOnRefund(req.restockOnRefund());
        }
        if (req.dispensingClass() != null && !req.dispensingClass().isBlank()) {
            applyClass(d, DispensingClass.parse(req.dispensingClass()), userId);
        }
        repository.save(d);
        forceExpiryTracking(businessId, item);
        if (created) {
            audit(businessId, userId, AuditEventTypes.PHARMACY_MEDICINE_MARKED, item, null, d.getDispensingClass());
        } else if (before != d.getDispensingClass()) {
            audit(businessId, userId, AuditEventTypes.PHARMACY_CLASS_CHANGED, item, before, d.getDispensingClass());
        }
        return toResponse(d, item);
    }

    /**
     * Removes the medicine marker. Stock-safety and dispensing rules stop applying to the item, so
     * this is audited. {@code has_expiry} is left as it is.
     */
    @Transactional
    public void unmark(String businessId, String itemId, String userId) {
        Item item = requireItem(businessId, itemId);
        ItemMedicineDetails d = repository.findByItemIdAndBusinessId(itemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not marked as a medicine"));
        DispensingClass before = d.getDispensingClass();
        repository.delete(d);
        audit(businessId, userId, AuditEventTypes.PHARMACY_MEDICINE_UNMARKED, item, before, null);
    }

    @Transactional
    public BulkMedicinesResult bulkClassify(String businessId, BulkClassifyMedicinesRequest req, String userId) {
        DispensingClass cls = DispensingClass.parse(req.dispensingClass());
        Set<String> ids = new LinkedHashSet<>(req.itemIds());
        Map<String, Item> items = liveItems(businessId, ids);
        Map<String, ItemMedicineDetails> existing = new LinkedHashMap<>();
        for (ItemMedicineDetails d : repository.findByBusinessIdAndItemIdIn(businessId, ids)) {
            existing.put(d.getItemId(), d);
        }
        int changed = 0;
        int skipped = 0;
        for (String id : ids) {
            Item item = items.get(id);
            ItemMedicineDetails d = existing.get(id);
            if (item == null || d == null) {
                skipped++;
                continue;
            }
            DispensingClass before = d.getDispensingClass();
            if (before == cls) {
                skipped++;
                continue;
            }
            applyClass(d, cls, userId);
            repository.save(d);
            audit(businessId, userId, AuditEventTypes.PHARMACY_CLASS_CHANGED, item, before, cls);
            changed++;
        }
        return new BulkMedicinesResult(changed, skipped);
    }

    @Transactional
    public BulkMedicinesResult bulkMark(String businessId, BulkMarkMedicinesRequest req, String userId) {
        Set<String> ids = new LinkedHashSet<>();
        if (req.itemIds() != null) {
            ids.addAll(req.itemIds());
        }
        if (req.itemTypeId() != null && !req.itemTypeId().isBlank()) {
            ids.addAll(repository.findUnmarkedItemIdsInItemType(businessId, req.itemTypeId().strip()));
        }
        if (ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose products or a department to mark");
        }
        DispensingClass cls = req.dispensingClass() == null || req.dispensingClass().isBlank()
                ? DispensingClass.UNCLASSIFIED
                : DispensingClass.parse(req.dispensingClass());
        Map<String, Item> items = liveItems(businessId, ids);
        Set<String> already = new LinkedHashSet<>();
        for (ItemMedicineDetails d : repository.findByBusinessIdAndItemIdIn(businessId, ids)) {
            already.add(d.getItemId());
        }
        int changed = 0;
        int skipped = 0;
        for (String id : ids) {
            Item item = items.get(id);
            if (item == null || already.contains(id)) {
                skipped++;
                continue;
            }
            ItemMedicineDetails d = new ItemMedicineDetails();
            d.setItemId(id);
            d.setBusinessId(businessId);
            if (cls != DispensingClass.UNCLASSIFIED) {
                applyClass(d, cls, userId);
            }
            repository.save(d);
            forceExpiryTracking(businessId, item);
            audit(businessId, userId, AuditEventTypes.PHARMACY_MEDICINE_MARKED, item, null, d.getDispensingClass());
            changed++;
        }
        return new BulkMedicinesResult(changed, skipped);
    }

    private static void applyClass(ItemMedicineDetails d, DispensingClass cls, String userId) {
        if (d.getDispensingClass() == cls && d.getClassSetAt() != null) {
            return;
        }
        d.setDispensingClass(cls);
        d.setClassSetBy(userId);
        d.setClassSetAt(Instant.now());
    }

    private void forceExpiryTracking(String businessId, Item item) {
        if (!item.isHasExpiry()) {
            item.setHasExpiry(true);
            itemRepository.save(item);
        }
        String holderId = packageVariantStockResolver.stockHolderItemId(item);
        if (holderId != null && !holderId.equals(item.getId())) {
            itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(holderId, businessId).ifPresent(holder -> {
                if (!holder.isHasExpiry()) {
                    holder.setHasExpiry(true);
                    itemRepository.save(holder);
                }
            });
        }
    }

    private Item requireItem(String businessId, String itemId) {
        return itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found"));
    }

    private Map<String, Item> liveItems(String businessId, Set<String> ids) {
        Map<String, Item> out = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        for (Item i : itemRepository.findByIdInAndBusinessIdAndDeletedAtIsNull(ids, businessId)) {
            out.put(i.getId(), i);
        }
        return out;
    }

    private void audit(
            String businessId,
            String userId,
            String eventType,
            Item item,
            DispensingClass before,
            DispensingClass after
    ) {
        try {
            Map<String, Object> oldState = new LinkedHashMap<>();
            oldState.put("dispensingClass", before == null ? null : before.name());
            Map<String, Object> newState = new LinkedHashMap<>();
            newState.put("dispensingClass", after == null ? null : after.name());
            auditEventPublisher.publish(auditEventBuilder
                    .builder(AuditEventCategory.PRODUCTS, eventType, AuditEventSeverity.WARN)
                    .businessId(businessId)
                    .actor(userId, AuditEventActorType.USER)
                    .target("item", item.getId())
                    .source("pharmacy")
                    .oldState(oldState)
                    .newState(newState)
                    .build());
        } catch (Exception ex) {
            log.warn("Failed to audit {} business={} item={}: {}", eventType, businessId, item.getId(), ex.toString());
        }
    }

    static MedicineDetailsResponse toResponse(ItemMedicineDetails d, Item item) {
        return new MedicineDetailsResponse(
                d.getItemId(),
                item.getName(),
                item.getSku(),
                item.getBarcode(),
                item.getItemTypeId(),
                d.getGenericName(),
                d.getStrength(),
                d.getDosageForm(),
                d.getPackSize(),
                d.getManufacturer(),
                d.getRegistrationNo(),
                d.getStorageNote(),
                d.getDispensingClass().name(),
                d.isRestockOnRefund(),
                d.getClassSetAt()
        );
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
