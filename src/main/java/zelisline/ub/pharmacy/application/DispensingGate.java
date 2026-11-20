package zelisline.ub.pharmacy.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.PermissionRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.pharmacy.domain.DispensingApproval;
import zelisline.ub.pharmacy.domain.DispensingClass;
import zelisline.ub.pharmacy.domain.DispensingRecord;
import zelisline.ub.pharmacy.domain.DispensingRecordLine;
import zelisline.ub.pharmacy.domain.ItemMedicineDetails;
import zelisline.ub.pharmacy.domain.PharmacyProfile;
import zelisline.ub.pharmacy.repository.DispensingApprovalRepository;
import zelisline.ub.pharmacy.repository.DispensingRecordLineRepository;
import zelisline.ub.pharmacy.repository.DispensingRecordRepository;
import zelisline.ub.pharmacy.repository.PharmacyProfileRepository;
import zelisline.ub.platform.web.SetupRequiredException;
import zelisline.ub.platform.web.SetupRequirement;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.sales.api.dto.PostSaleDispensingRequest;
import zelisline.ub.sales.api.dto.PostSaleLineRequest;
import zelisline.ub.sales.domain.SaleItem;
import zelisline.ub.sales.domain.SaleLineKinds;

/**
 * The one place that decides whether a sale holding medicines may complete (ADR 0033).
 *
 * <p>Two call sites: {@code SaleService.createSale}, which every till path funnels through, and
 * the public web checkout, which takes stock on its own path. The check is in the backend so no
 * client can skip it.</p>
 *
 * <ul>
 *   <li>UNCLASSIFIED: blocked until someone with {@code pharmacy.manage} sets a class.</li>
 *   <li>OTC: sells normally.</li>
 *   <li>PRESCRIPTION_ONLY: needs a dispensing record and a dispenser holding
 *       {@code pharmacy.dispense} (the seller, or a pharmacist who approved by PIN).</li>
 *   <li>CONTROLLED: as prescription-only, and only when the shop has switched controlled sales on.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DispensingGate {

    public static final String PERMISSION_DISPENSE = "pharmacy.dispense";

    /** Hints carried on the setup-required problem so the till opens the right dialog. */
    public static final String HINT_CLASSIFY = "classify";
    public static final String HINT_CONTROLLED = "controlled";
    public static final String HINT_RECORD = "record";
    public static final String HINT_APPROVE = "approve";

    private final ItemRepository itemRepository;
    private final PackageVariantStockResolver packageVariantStockResolver;
    private final MedicineRegistry medicineRegistry;
    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;
    private final PharmacyProfileRepository pharmacyProfileRepository;
    private final DispensingApprovalRepository approvalRepository;
    private final DispensingRecordRepository recordRepository;
    private final DispensingRecordLineRepository lineRepository;
    private final InventoryBatchRepository inventoryBatchRepository;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;
    private final zelisline.ub.pharmacy.repository.PharmacyPatientRepository patientRepository;

    /** A gated medicine in the sale. */
    public record GatedProduct(String itemId, String name, DispensingClass dispensingClass) {
    }

    /** How one sold item stands against the dispensing rules. */
    public record ItemStanding(String itemId, String name, DispensingClass dispensingClass, String status) {
        public static final String OK = "ok";
        public static final String NEEDS_RECORD = "needs_record";
        public static final String UNCLASSIFIED = "unclassified";
        public static final String CONTROLLED_BLOCKED = "controlled_blocked";
    }

    /** What {@link #record} writes once the sale exists. */
    public record Plan(
            String dispenserUserId,
            String sellerUserId,
            String approvalId,
            PostSaleDispensingRequest details,
            Map<String, GatedProduct> gatedByItemId
    ) {
    }

    /**
     * Where each of the given items stands, without failing. Items that are not medicines are
     * left out. Used by the till to warn before Charge, and by {@link #evaluate}.
     */
    @Transactional(readOnly = true)
    public List<ItemStanding> standings(String businessId, Collection<String> itemIds) {
        Set<String> ids = new LinkedHashSet<>();
        if (itemIds != null) {
            for (String id : itemIds) {
                if (id != null && !id.isBlank()) {
                    ids.add(id.strip());
                }
            }
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Item> items = itemRepository.findByIdInAndBusinessIdAndDeletedAtIsNull(ids, businessId);
        Map<String, List<String>> candidatesByItem = new LinkedHashMap<>();
        Set<String> allCandidates = new LinkedHashSet<>();
        for (Item item : items) {
            List<String> c = MedicineRegistry.candidateIds(item, packageVariantStockResolver.stockHolderItemId(item));
            candidatesByItem.put(item.getId(), c);
            allCandidates.addAll(c);
        }
        Map<String, ItemMedicineDetails> rows = medicineRegistry.byItemId(businessId, allCandidates);
        if (rows.isEmpty()) {
            return List.of();
        }
        boolean controlledOn = pharmacyProfileRepository.findById(businessId)
                .map(PharmacyProfile::isControlledSalesEnabled)
                .orElse(false);
        List<ItemStanding> out = new ArrayList<>();
        for (Item item : items) {
            ItemMedicineDetails d = null;
            for (String c : candidatesByItem.get(item.getId())) {
                d = rows.get(c);
                if (d != null) {
                    break;
                }
            }
            if (d == null) {
                continue;
            }
            DispensingClass cls = d.getDispensingClass();
            String status = switch (cls) {
                case OTC -> ItemStanding.OK;
                case UNCLASSIFIED -> ItemStanding.UNCLASSIFIED;
                case PRESCRIPTION_ONLY -> ItemStanding.NEEDS_RECORD;
                case CONTROLLED -> controlledOn ? ItemStanding.NEEDS_RECORD : ItemStanding.CONTROLLED_BLOCKED;
            };
            out.add(new ItemStanding(item.getId(), item.getName(), cls, status));
        }
        return out;
    }

    /** True when the user's role (looked up when {@code roleId} is null) holds {@code pharmacy.dispense}. */
    @Transactional(readOnly = true)
    public boolean mayDispense(String businessId, String userId, String roleId) {
        String role = roleId;
        if (role == null || role.isBlank()) {
            role = userId == null
                    ? null
                    : userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(userId, businessId)
                            .map(User::getRoleId)
                            .orElse(null);
        }
        if (role == null || role.isBlank()) {
            return false;
        }
        return permissionRepository.findPermissionKeysByRoleId(role).contains(PERMISSION_DISPENSE);
    }

    /**
     * Checks a till sale before any stock is touched.
     *
     * @return what to record once the sale exists, or {@code null} when nothing in it is gated
     * @throws SetupRequiredException with requirement {@code dispensing} and a hint when the sale cannot proceed
     */
    @Transactional
    public Plan evaluate(
            String businessId,
            List<PostSaleLineRequest> lines,
            PostSaleDispensingRequest details,
            String userId,
            String roleId
    ) {
        Set<String> itemIds = new LinkedHashSet<>();
        Map<String, java.math.BigDecimal> soldQuantity = new java.util.HashMap<>();
        if (lines != null) {
            for (PostSaleLineRequest line : lines) {
                if (line == null || line.itemId() == null || line.itemId().isBlank()) {
                    continue;
                }
                String kind = line.kind();
                if (kind == null || kind.isBlank() || SaleLineKinds.ITEM.equalsIgnoreCase(kind.strip())) {
                    itemIds.add(line.itemId().strip());
                    if (line.quantity() != null) {
                        soldQuantity.merge(line.itemId().strip(), line.quantity(), java.math.BigDecimal::add);
                    }
                }
            }
        }
        List<ItemStanding> standings = standings(businessId, itemIds);
        if (standings.isEmpty()) {
            return null;
        }
        Map<String, GatedProduct> gated = new LinkedHashMap<>();
        for (ItemStanding s : standings) {
            switch (s.status()) {
                case ItemStanding.UNCLASSIFIED -> throw new SetupRequiredException(
                        HttpStatus.CONFLICT, SetupRequirement.DISPENSING, HINT_CLASSIFY,
                        s.name() + " has no dispensing class yet. Someone who manages the pharmacy "
                                + "must classify it before it can be sold.");
                case ItemStanding.CONTROLLED_BLOCKED -> throw new SetupRequiredException(
                        HttpStatus.CONFLICT, SetupRequirement.DISPENSING, HINT_CONTROLLED,
                        s.name() + " is a controlled medicine and cannot be sold through the till.");
                case ItemStanding.NEEDS_RECORD ->
                        gated.put(s.itemId(), new GatedProduct(s.itemId(), s.name(), s.dispensingClass()));
                default -> {
                    // OTC: nothing to do
                }
            }
        }
        if (gated.isEmpty()) {
            return null;
        }
        if (details == null || details.prescriberName() == null || details.prescriberName().isBlank()) {
            throw new SetupRequiredException(
                    HttpStatus.CONFLICT, SetupRequirement.DISPENSING, HINT_RECORD,
                    "This sale needs a dispensing record for " + names(gated.values()) + ".");
        }
        String dispenser = userId;
        String approvalId = null;
        if (!mayDispense(businessId, userId, roleId)) {
            String raw = details.approvalId();
            if (raw == null || raw.isBlank()) {
                throw new SetupRequiredException(
                        HttpStatus.FORBIDDEN, SetupRequirement.DISPENSING, HINT_APPROVE,
                        "A pharmacist must approve the sale of " + names(gated.values()) + ".");
            }
            DispensingApproval approval = approvalRepository
                    .findByIdAndBusinessIdForUpdate(raw.strip(), businessId)
                    .orElse(null);
            if (approval == null
                    || approval.getUsedAt() != null
                    || approval.getExpiresAt() == null
                    || approval.getExpiresAt().isBefore(Instant.now())
                    || userId == null
                    || !userId.equals(approval.getRequestedBy())
                    || !covers(approval, gated.keySet(), soldQuantity)) {
                throw new SetupRequiredException(
                        HttpStatus.FORBIDDEN, SetupRequirement.DISPENSING, HINT_APPROVE,
                        "The pharmacist's approval is missing, used, expired, or was for different medicines "
                                + "or a smaller quantity. "
                                + "Ask them to approve again.");
            }
            dispenser = approval.getApproverUserId();
            approvalId = approval.getId();
        }
        return new Plan(dispenser, userId, approvalId, details, gated);
    }

    /** Writes the dispensing record for a sale that {@link #evaluate} cleared. Same transaction as the sale. */
    @Transactional
    public void record(String businessId, Plan plan, String saleId, String branchId, List<SaleItem> saleItems) {
        if (plan == null || plan.gatedByItemId().isEmpty()) {
            return;
        }
        PostSaleDispensingRequest d = plan.details();
        DispensingRecord r = new DispensingRecord();
        r.setBusinessId(businessId);
        r.setBranchId(branchId);
        r.setSaleId(saleId);
        r.setDispensedBy(plan.dispenserUserId());
        r.setSoldBy(plan.sellerUserId());
        r.setApprovalId(plan.approvalId());
        r.setPrescriberName(d.prescriberName().strip());
        r.setPrescriberRegNo(blankToNull(d.prescriberRegNo()));
        r.setPrescriptionRef(blankToNull(d.prescriptionRef()));
        r.setPrescriptionDate(d.prescriptionDate());
        r.setPatientName(blankToNull(d.patientName()));
        r.setPatientPhone(blankToNull(d.patientPhone()));
        // A patient profile, when one was chosen. An id that is not this shop's is ignored rather
        // than failing a sale that is otherwise complete.
        if (d.patientId() != null && !d.patientId().isBlank()) {
            patientRepository.findByIdAndBusinessIdAndDeletedAtIsNull(d.patientId().strip(), businessId)
                    .ifPresent(p -> {
                        r.setPatientId(p.getId());
                        if (r.getPatientName() == null) {
                            r.setPatientName(p.getName());
                        }
                        if (r.getPatientPhone() == null) {
                            r.setPatientPhone(p.getPhone());
                        }
                    });
        }
        recordRepository.save(r);

        Map<String, String> instructionsByItem = new LinkedHashMap<>();
        Map<String, PostSaleDispensingRequest.Line> detailByItem = new LinkedHashMap<>();
        if (d.lines() != null) {
            for (PostSaleDispensingRequest.Line l : d.lines()) {
                if (l == null || l.itemId() == null) {
                    continue;
                }
                detailByItem.putIfAbsent(l.itemId().strip(), l);
                if (l.instructions() != null && !l.instructions().isBlank()) {
                    instructionsByItem.put(l.itemId().strip(), l.instructions().strip());
                }
            }
        }
        int lineCount = 0;
        for (GatedProduct g : plan.gatedByItemId().values()) {
            BigDecimal qty = BigDecimal.ZERO;
            List<String> saleItemIds = new ArrayList<>();
            Set<String> lots = new LinkedHashSet<>();
            for (SaleItem si : saleItems) {
                if (!g.itemId().equals(si.getItemId())) {
                    continue;
                }
                qty = qty.add(si.getQuantity() == null ? BigDecimal.ZERO : si.getQuantity());
                saleItemIds.add(si.getId());
                if (si.getBatchId() != null) {
                    inventoryBatchRepository.findByIdAndBusinessId(si.getBatchId(), businessId)
                            .map(InventoryBatch::getLotNumber)
                            .filter(lot -> lot != null && !lot.isBlank())
                            .ifPresent(lots::add);
                }
            }
            if (saleItemIds.isEmpty()) {
                continue;
            }
            DispensingRecordLine line = new DispensingRecordLine();
            line.setRecordId(r.getId());
            line.setBusinessId(businessId);
            line.setItemId(g.itemId());
            line.setItemName(truncate(g.name(), 255));
            line.setDispensingClass(g.dispensingClass().name());
            line.setQuantity(qty.setScale(4, RoundingMode.HALF_UP));
            line.setInstructions(truncate(instructionsByItem.get(g.itemId()), 500));
            line.setSaleItemIds(joinWholeIds(saleItemIds, 1000));
            line.setLotNumbers(lots.isEmpty() ? null : truncate(String.join(", ", lots), 500));
            PostSaleDispensingRequest.Line extra = detailByItem.get(g.itemId());
            if (extra != null) {
                // Partial supply: remember what was prescribed only when more than was supplied.
                if (extra.quantityPrescribed() != null
                        && extra.quantityPrescribed().compareTo(line.getQuantity()) > 0) {
                    line.setQuantityPrescribed(extra.quantityPrescribed().setScale(4, RoundingMode.HALF_UP));
                }
                line.setRefillDueOn(extra.refillDueOn());
                if (extra.settlesLineId() != null && !extra.settlesLineId().isBlank()) {
                    lineRepository.findByIdAndBusinessId(extra.settlesLineId().strip(), businessId)
                            .filter(earlier -> g.itemId().equals(earlier.getItemId()))
                            .ifPresent(earlier -> line.setSettlesLineId(earlier.getId()));
                }
            }
            lineRepository.save(line);
            lineCount++;
        }
        if (plan.approvalId() != null) {
            approvalRepository.findByIdAndBusinessIdForUpdate(plan.approvalId(), businessId).ifPresent(a -> {
                a.setUsedAt(Instant.now());
                a.setUsedSaleId(saleId);
                approvalRepository.save(a);
            });
        }
        audit(businessId, plan, r, lineCount);
    }

    /** Marks the dispensing records of a voided sale as voided. The rows are kept. */
    @Transactional
    public void markVoidedForSale(String businessId, String saleId, String userId) {
        for (DispensingRecord r : recordRepository.findByBusinessIdAndSaleId(businessId, saleId)) {
            if (r.getVoidedAt() == null) {
                r.setVoidedAt(Instant.now());
                r.setVoidedBy(userId);
                recordRepository.save(r);
            }
        }
    }

    /**
     * Refuses an online order that holds a medicine the storefront may not sell: anything but OTC
     * (prescription-only, controlled or not yet classified).
     */
    @Transactional(readOnly = true)
    public void assertOnlineSellable(String businessId, Collection<String> itemIds) {
        for (ItemStanding s : standings(businessId, itemIds)) {
            if (s.dispensingClass().blockedOnline()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        s.name() + " is only available at the counter. Remove it to place this order.");
            }
        }
    }

    /**
     * Item ids the till must not queue for an offline sale: medicines that are not
     * over-the-counter, and their pack variants. Lets the till refuse before the goods leave.
     */
    @Transactional(readOnly = true)
    public List<String> counterOnlyItemIds(String businessId) {
        return medicineRegistry.counterOnlyItemIds(businessId);
    }

    /** Comma-joined ids that fit the column, never cut in the middle of an id. */
    private static String joinWholeIds(List<String> ids, int max) {
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (sb.length() + id.length() + 1 > max) {
                break;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.toString();
    }

    /** How one approved medicine is stored on the approval: {@code itemId=quantity}. */
    static String approvalToken(String itemId, java.math.BigDecimal quantity) {
        return itemId + "=" + quantity.stripTrailingZeros().toPlainString();
    }

    /**
     * True when every gated medicine in the sale was approved, in at least the quantity being
     * sold. An entry with no quantity, or one that cannot be read, approves nothing.
     */
    private static boolean covers(
            DispensingApproval approval,
            Set<String> gatedItemIds,
            Map<String, java.math.BigDecimal> soldQuantity
    ) {
        if (approval.getItemIds() == null || approval.getItemIds().isBlank()) {
            return false;
        }
        Map<String, java.math.BigDecimal> approved = new java.util.HashMap<>();
        for (String token : approval.getItemIds().split(",")) {
            int eq = token.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            try {
                approved.merge(token.substring(0, eq).strip(),
                        new java.math.BigDecimal(token.substring(eq + 1).strip()), java.math.BigDecimal::add);
            } catch (NumberFormatException ignored) {
                // unreadable entry approves nothing
            }
        }
        for (String id : gatedItemIds) {
            java.math.BigDecimal ok = approved.get(id);
            java.math.BigDecimal sold = soldQuantity.get(id);
            if (ok == null || sold == null || sold.compareTo(ok) > 0) {
                return false;
            }
        }
        return true;
    }

    private static String names(Collection<GatedProduct> products) {
        List<String> n = new ArrayList<>();
        for (GatedProduct p : products) {
            n.add(p.name());
            if (n.size() == 3 && products.size() > 3) {
                n.add("and " + (products.size() - 3) + " more");
                break;
            }
        }
        return String.join(", ", n);
    }

    private void audit(String businessId, Plan plan, DispensingRecord r, int lineCount) {
        try {
            // No medicine or patient names here: the audit log is read more widely than the records.
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("saleId", r.getSaleId());
            state.put("lines", lineCount);
            state.put("approvedByPin", plan.approvalId() != null);
            state.put("soldBy", plan.sellerUserId());
            auditEventPublisher.publish(auditEventBuilder
                    .builder(AuditEventCategory.SALES, AuditEventTypes.PHARMACY_DISPENSED, AuditEventSeverity.INFO)
                    .businessId(businessId)
                    .branchId(r.getBranchId())
                    .actor(plan.dispenserUserId(), AuditEventActorType.USER)
                    .target("dispensing_record", r.getId())
                    .source("pharmacy")
                    .newState(state)
                    .build());
        } catch (Exception ex) {
            log.warn("Failed to audit dispensing business={} record={}: {}", businessId, r.getId(), ex.toString());
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
