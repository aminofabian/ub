package zelisline.ub.pharmacy.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.pharmacy.api.dto.PharmacyStandardDtos.ControlledItem;
import zelisline.ub.pharmacy.api.dto.PharmacyStandardDtos.ControlledRegister;
import zelisline.ub.pharmacy.api.dto.PharmacyStandardDtos.RegisterEntry;
import zelisline.ub.pharmacy.domain.DispensingClass;
import zelisline.ub.pharmacy.domain.DispensingRecord;
import zelisline.ub.pharmacy.domain.ItemMedicineDetails;
import zelisline.ub.pharmacy.repository.ControlledRegisterRepository;
import zelisline.ub.pharmacy.repository.DispensingRecordRepository;
import zelisline.ub.pharmacy.repository.ItemMedicineDetailsRepository;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.domain.StockMovement;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;

/**
 * The controlled-medicines register (ADR 0033, Phase 2): every movement of a controlled medicine
 * in date order with a running balance, read from the stock ledger. Nothing is stored twice, so
 * the register cannot drift from stock. Quarantine and release are left out: quarantined stock is
 * still on the premises, so the balance is what the pharmacy physically holds.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ControlledRegisterService {

    private static final ZoneId SHOP_ZONE = ZoneId.of("Africa/Nairobi");
    private static final int MAX_ENTRIES = 2000;
    private static final int MAX_RANGE_DAYS = 366;
    private static final List<String> NOT_PHYSICAL = List.of(
            InventoryConstants.MOVEMENT_QUARANTINE, InventoryConstants.MOVEMENT_QUARANTINE_RELEASE);

    private final ItemMedicineDetailsRepository medicineDetailsRepository;
    private final ItemRepository itemRepository;
    private final ControlledRegisterRepository registerRepository;
    private final DispensingRecordRepository recordRepository;
    private final InventoryBatchRepository inventoryBatchRepository;
    private final UserRepository userRepository;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    /** Every controlled medicine with the balance the register shows for it today. */
    @Transactional(readOnly = true)
    public List<ControlledItem> items(String businessId) {
        List<ItemMedicineDetails> details =
                medicineDetailsRepository.findByBusinessIdAndDispensingClass(businessId, DispensingClass.CONTROLLED);
        if (details.isEmpty()) {
            return List.of();
        }
        Map<String, ItemMedicineDetails> byItem = new LinkedHashMap<>();
        for (ItemMedicineDetails d : details) {
            byItem.put(d.getItemId(), d);
        }
        Instant now = Instant.now();
        List<ControlledItem> out = new ArrayList<>();
        for (Item item : itemRepository.findByIdInAndBusinessIdAndDeletedAtIsNull(byItem.keySet(), businessId)) {
            ItemMedicineDetails d = byItem.get(item.getId());
            out.add(new ControlledItem(
                    item.getId(), item.getName(), d.getStrength(), d.getDosageForm(),
                    registerRepository.balanceBefore(businessId, item.getId(), NOT_PHYSICAL, now)));
        }
        out.sort((a, b) -> String.valueOf(a.name()).compareToIgnoreCase(String.valueOf(b.name())));
        return out;
    }

    @Transactional(readOnly = true)
    public ControlledRegister register(
            String businessId, String itemId, LocalDate from, LocalDate to, String viewerUserId) {
        ItemMedicineDetails d = medicineDetailsRepository.findByItemIdAndBusinessId(itemId, businessId)
                .filter(x -> x.getDispensingClass() == DispensingClass.CONTROLLED)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "This product is not a controlled medicine"));
        Item item = itemRepository.findByIdInAndBusinessIdAndDeletedAtIsNull(List.of(d.getItemId()), businessId)
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
        LocalDate today = LocalDate.now(SHOP_ZONE);
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(30) : from;
        if (start.isAfter(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The start date is after the end date");
        }
        if (start.plusDays(MAX_RANGE_DAYS).isBefore(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a range of one year or less");
        }
        Instant fromTs = start.atStartOfDay(SHOP_ZONE).toInstant();
        Instant toTs = end.plusDays(1).atStartOfDay(SHOP_ZONE).toInstant();
        BigDecimal opening = registerRepository.balanceBefore(businessId, item.getId(), NOT_PHYSICAL, fromTs);
        List<StockMovement> movements = registerRepository.movements(
                businessId, item.getId(), NOT_PHYSICAL, fromTs, toTs, PageRequest.of(0, MAX_ENTRIES + 1));
        boolean truncated = movements.size() > MAX_ENTRIES;
        if (truncated) {
            movements = movements.subList(0, MAX_ENTRIES);
        }

        Set<String> saleIds = new LinkedHashSet<>();
        Set<String> batchIds = new LinkedHashSet<>();
        Set<String> userIds = new LinkedHashSet<>();
        for (StockMovement m : movements) {
            if (InventoryConstants.MOVEMENT_SALE.equals(m.getMovementType()) && m.getReferenceId() != null) {
                saleIds.add(m.getReferenceId());
            }
            if (m.getBatchId() != null) {
                batchIds.add(m.getBatchId());
            }
            if (m.getCreatedBy() != null) {
                userIds.add(m.getCreatedBy());
            }
        }
        Map<String, DispensingRecord> recordBySale = new LinkedHashMap<>();
        if (!saleIds.isEmpty()) {
            for (DispensingRecord r : recordRepository.findByBusinessIdAndSaleIdIn(businessId, saleIds)) {
                recordBySale.putIfAbsent(r.getSaleId(), r);
                if (r.getDispensedBy() != null) {
                    userIds.add(r.getDispensedBy());
                }
            }
        }
        Map<String, String> lotByBatch = new LinkedHashMap<>();
        if (!batchIds.isEmpty()) {
            for (InventoryBatch b : inventoryBatchRepository.findAllById(batchIds)) {
                if (businessId.equals(b.getBusinessId()) && b.getLotNumber() != null) {
                    lotByBatch.put(b.getId(), b.getLotNumber());
                }
            }
        }
        Map<String, String> userNames = new LinkedHashMap<>();
        if (!userIds.isEmpty()) {
            for (User u : userRepository.findAllById(userIds)) {
                if (businessId.equals(u.getBusinessId())) {
                    userNames.put(u.getId(), u.getName());
                }
            }
        }

        BigDecimal balance = opening;
        List<RegisterEntry> entries = new ArrayList<>(movements.size());
        for (StockMovement m : movements) {
            BigDecimal delta = m.getQuantityDelta() == null ? BigDecimal.ZERO : m.getQuantityDelta();
            balance = balance.add(delta);
            DispensingRecord r = InventoryConstants.MOVEMENT_SALE.equals(m.getMovementType())
                    ? recordBySale.get(m.getReferenceId())
                    : null;
            entries.add(new RegisterEntry(
                    m.getCreatedAt(),
                    m.getMovementType(),
                    delta.signum() > 0 ? delta : null,
                    delta.signum() < 0 ? delta.negate() : null,
                    balance,
                    m.getBatchId() == null ? null : lotByBatch.get(m.getBatchId()),
                    m.getCreatedBy() == null ? null : userNames.get(m.getCreatedBy()),
                    r == null ? null : r.getPrescriberName(),
                    r == null ? null : r.getPatientName(),
                    r == null || r.getDispensedBy() == null ? null : userNames.get(r.getDispensedBy()),
                    m.getReason()));
        }
        audit(businessId, viewerUserId, item.getId(), entries.size());
        return new ControlledRegister(item.getId(), item.getName(), start, end, opening, balance, truncated, entries);
    }

    private void audit(String businessId, String viewerUserId, String itemId, int count) {
        try {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("entries", count);
            auditEventPublisher.publish(auditEventBuilder
                    .builder(AuditEventCategory.SECURITY, AuditEventTypes.PHARMACY_REGISTER_VIEWED, AuditEventSeverity.INFO)
                    .businessId(businessId)
                    .actor(viewerUserId, AuditEventActorType.USER)
                    .source("pharmacy")
                    .target("item", itemId)
                    .newState(state)
                    .build());
        } catch (Exception ex) {
            log.warn("Failed to audit controlled-register view business={}: {}", businessId, ex.toString());
        }
    }
}
