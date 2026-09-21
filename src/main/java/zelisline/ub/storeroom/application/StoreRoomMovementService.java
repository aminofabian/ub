package zelisline.ub.storeroom.application;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.inventory.api.dto.BatchAllocationLine;
import zelisline.ub.inventory.api.dto.InventoryMutationResponse;
import zelisline.ub.inventory.api.dto.PostBatchDecreaseRequest;
import zelisline.ub.inventory.api.dto.PostStandaloneWastageRequest;
import zelisline.ub.inventory.api.dto.PostStockIncreaseRequest;
import zelisline.ub.inventory.application.InventoryBatchPickerService;
import zelisline.ub.inventory.application.InventoryLedgerService;
import zelisline.ub.platform.security.CurrentUserPermissions;
import zelisline.ub.purchasing.repository.StockMovementRepository;
import zelisline.ub.storeroom.api.dto.CreateStoreRoomMovementRequest;
import zelisline.ub.storeroom.api.dto.StoreRoomActivityResponse;
import zelisline.ub.storeroom.api.dto.StoreRoomMovementResponse;
import zelisline.ub.storeroom.domain.StoreItem;
import zelisline.ub.storeroom.domain.StoreRoomDirection;
import zelisline.ub.storeroom.domain.StoreRoomMode;
import zelisline.ub.storeroom.domain.StoreRoomMovement;
import zelisline.ub.storeroom.domain.StoreRoomMovementStatus;
import zelisline.ub.storeroom.domain.StoreRoomReason;
import zelisline.ub.storeroom.domain.StoreRoomSettings;
import zelisline.ub.storeroom.domain.StoreRoomStockEffect;
import zelisline.ub.storeroom.repository.StoreItemRepository;
import zelisline.ub.storeroom.repository.StoreRoomMovementRepository;
import zelisline.ub.tenancy.application.BranchResolutionService;

/**
 * Records take-outs and put-ins from the store room, and decides on the ones that
 * need approval.
 *
 * <p>The rule that matters: <b>Class B reasons decrease stock</b>, and a manual
 * put-in ({@code received_into_room}) increases it. Class A ("restock to shelf",
 * prep, counter transfer) leave the shop's stock alone — the goods never left
 * the business, so decrementing would drain inventory every time a shelf was
 * filled. Purchase-order inherit uses {@code from_purchase_order} as a memo so
 * GRN is not double-counted. See docs/scopes/STORE_ROOM_MANAGEMENT_SCOPE.md §5.
 *
 * <p>For a linked product the inventory ledger stays the stock of record; this
 * service writes the ledger entry <em>and</em> the narrative row, in one
 * transaction, and links them.
 */
@Service
@RequiredArgsConstructor
public class StoreRoomMovementService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int DEFAULT_PAGE = 200;
    private static final int MAX_PAGE = 500;
    /** Validated but otherwise unused by the ledger; needed when nothing else has a cost. */
    private static final BigDecimal NOMINAL_UNIT_COST = new BigDecimal("0.01");

    private final StoreRoomMovementRepository movementRepository;
    private final StoreItemRepository storeItemRepository;
    private final StoreRoomSettingsService storeRoomSettingsService;
    private final InventoryBatchPickerService batchPickerService;
    private final InventoryLedgerService inventoryLedgerService;
    private final StockMovementRepository stockMovementRepository;
    private final BranchResolutionService branchResolutionService;
    private final ItemRepository itemRepository;
    private final UserRepository userRepository;
    private final CurrentUserPermissions permissions;

    /** Filters for the activity read. All optional. */
    public record ActivityQuery(
            Instant from,
            Instant to,
            Integer limit,
            String reason,
            String createdBy,
            String direction,
            String status
    ) {
    }

    @Transactional
    public StoreRoomMovementResponse record(
            String businessId,
            String actorId,
            String actorRoleId,
            String sessionBranchId,
            CreateStoreRoomMovementRequest request
    ) {
        StoreItem item = storeItemRepository.findByIdAndBusinessId(request.storeItemId(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Store item not found"));

        StoreRoomDirection direction = StoreRoomDirection.fromWire(request.direction());
        StoreRoomReason reason = StoreRoomReason.fromWire(request.reason());
        if (reason.direction() != direction) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "The reason \"" + reason.wireValue() + "\" cannot be used for a "
                            + direction.wireValue() + " movement.");
        }

        BigDecimal quantity = request.quantity();
        String note = normalizeNote(request.note());
        if (reason == StoreRoomReason.OTHER && note == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Add a note so \"Something else\" still makes sense later.");
        }
        boolean linked = storeRoomSettingsService.currentMode(businessId) == StoreRoomMode.CONNECTED
                && item.getItemId() != null;
        StoreRoomStockEffect effect = reason.stockEffect();
        boolean decreasesStock = effect == StoreRoomStockEffect.DECREASE;
        boolean increasesStock = effect == StoreRoomStockEffect.INCREASE;
        boolean movesStock = decreasesStock || increasesStock;

        // The permission depends on the payload, not the route: moving real stock is
        // an inventory action, while adjusting the local register is a catalogue one.
        // Either is accepted for the local case so a delegated stock manager (who has
        // inventory.write but never catalog.items.write) is not locked out.
        if (movesStock && linked) {
            permissions.require("inventory.write");
        } else {
            permissions.requireAny("catalog.items.write", "inventory.write");
        }

        StoreRoomMovement row = new StoreRoomMovement();
        row.setBusinessId(businessId);
        row.setStoreItemId(item.getId());
        row.setItemId(item.getItemId());
        row.setDirection(direction);
        row.setReason(reason);
        row.setStockEffect(effect);
        row.setQuantity(quantity);
        row.setNote(note);
        row.setCreatedBy(actorId);
        row.setStatus(StoreRoomMovementStatus.APPLIED);

        if (decreasesStock && linked && exceedsApprovalThreshold(businessId, quantity)) {
            // Nothing moves until somebody says yes. The reason class still records the
            // intent, so an approver can see what they are approving.
            row.setStatus(StoreRoomMovementStatus.PENDING);
            row.setBranchId(resolveBranch(
                    businessId, sessionBranchId, actorRoleId, request.branchId()));
        } else if (decreasesStock) {
            if (linked) {
                applyInventoryDecrease(
                        businessId,
                        item.getItemId(),
                        item.getName(),
                        item.getBuyingPrice(),
                        quantity,
                        reason,
                        request.branchId(),
                        note,
                        actorId,
                        actorRoleId,
                        sessionBranchId,
                        row);
            } else {
                applyLocalDecrease(item, quantity);
            }
        } else if (increasesStock) {
            if (linked) {
                applyInventoryIncrease(
                        businessId,
                        item.getItemId(),
                        item.getBuyingPrice(),
                        quantity,
                        reason,
                        request.branchId(),
                        note,
                        actorId,
                        actorRoleId,
                        sessionBranchId,
                        row);
            } else {
                applyLocalIncrease(item, quantity);
            }
        }

        movementRepository.save(row);
        return decorate(businessId, List.of(row)).get(0);
    }

    /**
     * Approve or turn down a pending movement.
     *
     * <p>Approving is where stock actually moves, so it carries the same
     * {@code inventory.write} requirement as the take-out would have. Rejecting
     * changes nothing, but a decision is still an inventory decision, so it takes the
     * same key rather than a weaker one.
     *
     * <p>When the store room asks for a second pair of eyes (§10 D7), nobody may
     * approve a take-out they raised themselves. Rejecting your own is still allowed —
     * withdrawing a request moves no stock, and needing a colleague to witness you
     * cancelling your own mistake would be perverse.
     */
    @Transactional
    public StoreRoomMovementResponse decide(
            String businessId,
            String movementId,
            String actorId,
            String actorRoleId,
            String sessionBranchId,
            boolean approve,
            String decisionNote
    ) {
        permissions.require("inventory.write");

        StoreRoomMovement row = movementRepository.findByIdAndBusinessId(movementId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Movement not found"));
        if (row.getStatus() != StoreRoomMovementStatus.PENDING) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "That movement has already been decided (" + row.getStatus().wireValue() + ").");
        }

        if (approve) {
            StoreRoomSettings settings = storeRoomSettingsService.settingsRow(businessId);
            if (settings != null && settings.isRequireSeparateApprover()
                    && actorId != null && actorId.equals(row.getCreatedBy())) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "This store room asks for a second person. You raised this take-out, "
                                + "so somebody else has to approve it — or you can turn it down.");
            }
            if (row.getItemId() == null) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "This movement has no product to apply against any more.");
            }
            StoreItem item = row.getStoreItemId() == null
                    ? null
                    : storeItemRepository.findByIdAndBusinessId(row.getStoreItemId(), businessId)
                            .orElse(null);
            // Post to the branch the take-out was raised against, so approval cannot
            // silently move the stock to a different shop.
            applyInventoryDecrease(
                    businessId,
                    row.getItemId(),
                    item == null ? null : item.getName(),
                    item == null ? null : item.getBuyingPrice(),
                    row.getQuantity(),
                    row.getReason(),
                    row.getBranchId(),
                    row.getNote(),
                    actorId,
                    actorRoleId,
                    sessionBranchId,
                    row);
            row.setStatus(StoreRoomMovementStatus.APPLIED);
        } else {
            row.setStatus(StoreRoomMovementStatus.REJECTED);
        }
        row.setDecidedBy(actorId);
        row.setDecidedAt(Instant.now());
        row.setDecisionNote(normalizeNote(decisionNote));
        movementRepository.save(row);

        return decorate(businessId, List.of(row)).get(0);
    }

    /**
     * The activity trail for a window.
     *
     * <p>The caller supplies the window because the dashboard knows its own local day;
     * the server has no opinion about what "today" means for the shop.
     *
     * <p>The summary and filter options describe the whole window, while
     * {@code movements} is the filtered view — so the headline does not move while
     * somebody narrows the list, and the offered filters stay truthful. Filtering
     * happens in memory because the window is capped and facets have to be computed
     * over it anyway.
     */
    @Transactional(readOnly = true)
    public StoreRoomActivityResponse activity(String businessId, ActivityQuery query) {
        Instant end = query.to() != null ? query.to() : Instant.now();
        Instant start = query.from() != null ? query.from() : end.minus(Duration.ofHours(24));
        int page = query.limit() == null || query.limit() <= 0
                ? DEFAULT_PAGE
                : Math.min(query.limit(), MAX_PAGE);

        List<StoreRoomMovement> window = movementRepository
                .findByBusinessIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
                        businessId, start, end, PageRequest.of(0, MAX_PAGE));

        int takeOuts = 0;
        int putIns = 0;
        int pending = 0;
        BigDecimal stockLoss = ZERO;
        Map<String, Integer> actorsSeen = new LinkedHashMap<>();
        Map<String, Integer> reasonsSeen = new LinkedHashMap<>();
        for (StoreRoomMovement row : window) {
            if (row.getDirection() == StoreRoomDirection.OUT) {
                takeOuts++;
            } else {
                putIns++;
            }
            if (row.getStatus() == StoreRoomMovementStatus.PENDING) {
                pending++;
            }
            // Pending stock has not left the shop, so it is not a loss yet.
            if (row.getStockEffect() == StoreRoomStockEffect.DECREASE
                    && row.getStatus() == StoreRoomMovementStatus.APPLIED) {
                stockLoss = stockLoss.add(row.getQuantity());
            }
            if (row.getCreatedBy() != null) {
                actorsSeen.merge(row.getCreatedBy(), 1, Integer::sum);
            }
            reasonsSeen.merge(row.getReason().wireValue(), 1, Integer::sum);
        }

        List<StoreRoomMovement> filtered = window.stream()
                .filter(row -> matches(query.reason(), row.getReason().wireValue()))
                .filter(row -> matches(query.direction(), row.getDirection().wireValue()))
                .filter(row -> matches(query.status(), row.getStatus().wireValue()))
                .filter(row -> matches(query.createdBy(), row.getCreatedBy()))
                .limit(page)
                .toList();

        Map<String, String> userNames = userNames(actorsSeen.keySet());
        List<StoreRoomActivityResponse.ActorFacet> actors = actorsSeen.entrySet().stream()
                .map(entry -> new StoreRoomActivityResponse.ActorFacet(
                        entry.getKey(),
                        userNames.getOrDefault(entry.getKey(), "Unknown"),
                        entry.getValue()))
                .toList();
        List<StoreRoomActivityResponse.ReasonFacet> reasons = reasonsSeen.entrySet().stream()
                .map(entry -> new StoreRoomActivityResponse.ReasonFacet(
                        entry.getKey(), entry.getValue()))
                .toList();

        return new StoreRoomActivityResponse(
                start,
                end,
                new StoreRoomActivityResponse.Summary(
                        window.size(), takeOuts, putIns, stockLoss, pending),
                new StoreRoomActivityResponse.Facets(actors, reasons),
                decorate(businessId, filtered));
    }

    private static boolean matches(String filter, String value) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        return filter.equals(value);
    }

    /** True when this take-out is big enough to need a second pair of eyes. */
    private boolean exceedsApprovalThreshold(String businessId, BigDecimal quantity) {
        StoreRoomSettings settings = storeRoomSettingsService.settingsRow(businessId);
        BigDecimal threshold = settings == null ? null : settings.getApprovalThreshold();
        return threshold != null && quantity.compareTo(threshold) > 0;
    }

    // ------------------------------------------------------------------
    // Stock effects
    // ------------------------------------------------------------------

    /** Standalone rows keep their own integer count; no catalogue, no branch, no ledger. */
    private void applyLocalDecrease(StoreItem item, BigDecimal quantity) {
        BigDecimal whole = quantity.stripTrailingZeros();
        if (whole.scale() > 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Back-room-only items are counted in whole numbers.");
        }
        int qty = whole.intValueExact();
        if (item.getQuantity() < qty) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Only " + item.getQuantity() + " in the store room.");
        }
        item.setQuantity(item.getQuantity() - qty);
        storeItemRepository.save(item);
    }

    private void applyLocalIncrease(StoreItem item, BigDecimal quantity) {
        BigDecimal whole = quantity.stripTrailingZeros();
        if (whole.scale() > 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Back-room-only items are counted in whole numbers.");
        }
        int qty = whole.intValueExact();
        item.setQuantity(item.getQuantity() + qty);
        storeItemRepository.save(item);
    }

    /**
     * Linked put-in raises real on-hand through the inventory ledger (same path as
     * a stock gain), so the store room count follows the product again.
     */
    private void applyInventoryIncrease(
            String businessId,
            String catalogItemId,
            BigDecimal preferredUnitCost,
            BigDecimal quantity,
            StoreRoomReason reason,
            String requestedBranchId,
            String note,
            String actorId,
            String actorRoleId,
            String sessionBranchId,
            StoreRoomMovement row
    ) {
        String branchId = resolveBranch(businessId, sessionBranchId, actorRoleId, requestedBranchId);
        BigDecimal unitCost = preferredUnitCost != null && preferredUnitCost.signum() > 0
                ? preferredUnitCost
                : NOMINAL_UNIT_COST;
        InventoryMutationResponse result = inventoryLedgerService.recordStockIncrease(
                businessId,
                new PostStockIncreaseRequest(
                        branchId,
                        catalogItemId,
                        quantity,
                        unitCost,
                        ledgerNote(reason, note)),
                actorId);
        row.setBranchId(branchId);
        row.setMovementId(result.stockMovementId());
        row.setMovementCount(countLedgerRows(businessId, result.stockMovementId()));
    }

    /**
     * Linked rows change real stock. The inventory ledger is the stock of record, so
     * the write goes through it — FEFO allocation, batch depletion and the shrinkage
     * journal all stay in one place.
     *
     * <p>Takes the catalogue item's details rather than the register row, because
     * approving a movement must still work after the register row has been deleted.
     */
    private void applyInventoryDecrease(
            String businessId,
            String catalogItemId,
            String itemName,
            BigDecimal preferredUnitCost,
            BigDecimal quantity,
            StoreRoomReason reason,
            String requestedBranchId,
            String note,
            String actorId,
            String actorRoleId,
            String sessionBranchId,
            StoreRoomMovement row
    ) {
        String branchId = resolveBranch(businessId, sessionBranchId, actorRoleId, requestedBranchId);

        // Preview first: it both proves there is enough stock at this shop and gives
        // the per-batch allocation a plain decrease needs.
        List<BatchAllocationLine> lines =
                batchPickerService.previewAllocation(businessId, catalogItemId, branchId, quantity);
        BigDecimal available = ZERO;
        for (BatchAllocationLine line : lines) {
            if (line.quantity() != null) {
                available = available.add(line.quantity());
            }
        }
        if (available.compareTo(quantity) < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Only " + available.stripTrailingZeros().toPlainString()
                            + " of \"" + (itemName == null ? catalogItemId : itemName)
                            + "\" at this shop.");
        }

        String ledgerNote = ledgerNote(reason, note);
        row.setBranchId(branchId);

        if (reason.usesWastagePath()) {
            InventoryMutationResponse result = inventoryLedgerService.recordStandaloneWastage(
                    businessId,
                    new PostStandaloneWastageRequest(
                            branchId,
                            catalogItemId,
                            quantity,
                            wasteCost(preferredUnitCost, lines),
                            ledgerNote,
                            null,
                            reason.wastageReason().name()),
                    actorId);
            row.setMovementId(result.stockMovementId());
            row.setMovementCount(countLedgerRows(businessId, result.stockMovementId()));
            return;
        }

        // Shrinkage that is not physical loss (theft, staff use, correction) is a plain
        // adjustment, so it depletes the allocated batches directly.
        String firstMovementId = null;
        int written = 0;
        for (BatchAllocationLine line : lines) {
            if (line.quantity() == null || line.quantity().signum() <= 0) {
                continue;
            }
            InventoryMutationResponse result = inventoryLedgerService.recordBatchDecrease(
                    businessId,
                    new PostBatchDecreaseRequest(line.batchId(), line.quantity(), ledgerNote),
                    actorId);
            if (firstMovementId == null) {
                firstMovementId = result.stockMovementId();
            }
            written++;
        }
        row.setMovementId(firstMovementId);
        row.setMovementCount(written);
    }

    /**
     * The ledger validates {@code unitCost} but values a write-off from each batch's
     * own cost, so this only has to be positive.
     */
    private static BigDecimal wasteCost(BigDecimal preferred, List<BatchAllocationLine> lines) {
        if (preferred != null && preferred.signum() > 0) {
            return preferred;
        }
        for (BatchAllocationLine line : lines) {
            if (line.unitCost() != null && line.unitCost().signum() > 0) {
                return line.unitCost();
            }
        }
        return NOMINAL_UNIT_COST;
    }

    /** A wastage can split across batches; the ledger only hands back the first id. */
    private int countLedgerRows(String businessId, String movementId) {
        if (movementId == null) {
            return 0;
        }
        return stockMovementRepository.findById(movementId)
                .map(movement -> stockMovementRepository
                        .findByBusinessIdAndReferenceTypeAndReferenceId(
                                businessId, movement.getReferenceType(), movement.getReferenceId())
                        .size())
                .orElse(1);
    }

    private String ledgerNote(StoreRoomReason reason, String note) {
        return note == null ? reason.ledgerLabel() : reason.ledgerLabel() + " — " + note;
    }

    /**
     * The store room is business-wide but inventory is branch-scoped, and the ledger
     * rejects a missing branch. Resolve server-side so the write never depends on
     * client state: requested shop → the operator's own branch → the shop's default.
     */
    private String resolveBranch(String businessId, String sessionBranchId, String actorRoleId, String requested) {
        String requestedBranch = normalizeNote(requested);
        String effective = branchResolutionService.resolveEffectiveBranch(
                businessId, requestedBranch, actorRoleId);
        String branch = effective != null && !effective.isBlank() ? effective : sessionBranchId;
        if (branch == null || branch.isBlank()) {
            branch = branchResolutionService.resolveDefaultBranch(businessId);
        }
        if (branch == null || branch.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "This shop has no location to post stock against. Add a branch first.");
        }
        return branch;
    }

    // ------------------------------------------------------------------
    // Read decoration
    // ------------------------------------------------------------------

    private List<StoreRoomMovementResponse> decorate(String businessId, List<StoreRoomMovement> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<String> storeItemIds = new LinkedHashSet<>();
        Set<String> itemIds = new LinkedHashSet<>();
        Set<String> userIds = new LinkedHashSet<>();
        for (StoreRoomMovement row : rows) {
            if (row.getStoreItemId() != null) {
                storeItemIds.add(row.getStoreItemId());
            }
            if (row.getItemId() != null) {
                itemIds.add(row.getItemId());
            }
            if (row.getCreatedBy() != null) {
                userIds.add(row.getCreatedBy());
            }
            if (row.getDecidedBy() != null) {
                userIds.add(row.getDecidedBy());
            }
        }

        Map<String, String> storeItemNames = new HashMap<>();
        for (StoreItem item : storeItemRepository.findAllById(storeItemIds)) {
            if (businessId.equals(item.getBusinessId())) {
                storeItemNames.put(item.getId(), item.getName());
            }
        }
        Map<String, String> itemNames = new HashMap<>();
        for (Item item : itemRepository.findAllById(itemIds)) {
            if (businessId.equals(item.getBusinessId())) {
                itemNames.put(item.getId(), item.getName());
            }
        }
        Map<String, String> userNames = userNames(userIds);

        List<StoreRoomMovementResponse> out = new ArrayList<>(rows.size());
        for (StoreRoomMovement row : rows) {
            out.add(new StoreRoomMovementResponse(
                    row.getId(),
                    row.getStoreItemId(),
                    storeItemNames.get(row.getStoreItemId()),
                    row.getItemId(),
                    itemNames.get(row.getItemId()),
                    row.getDirection().wireValue(),
                    row.getReason().wireValue(),
                    row.getStockEffect().wireValue(),
                    row.getStatus().wireValue(),
                    row.getQuantity(),
                    row.getNote(),
                    row.getMovementId(),
                    row.getMovementCount(),
                    row.getBranchId(),
                    row.getCreatedAt(),
                    row.getCreatedBy(),
                    userNames.get(row.getCreatedBy()),
                    row.getDecidedAt(),
                    row.getDecidedBy(),
                    userNames.get(row.getDecidedBy()),
                    row.getDecisionNote()));
        }
        return out;
    }

    private Map<String, String> userNames(Set<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = new HashMap<>();
        for (User user : userRepository.findLiveByIds(ids)) {
            names.put(user.getId(), user.getName());
        }
        return names;
    }

    private static String normalizeNote(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
