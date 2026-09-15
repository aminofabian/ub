package zelisline.ub.storeroom.application;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
import zelisline.ub.inventory.application.InventoryBatchPickerService;
import zelisline.ub.inventory.application.InventoryLedgerService;
import zelisline.ub.platform.security.CurrentUserPermissions;
import zelisline.ub.purchasing.domain.StockMovement;
import zelisline.ub.purchasing.repository.StockMovementRepository;
import zelisline.ub.storeroom.api.dto.CreateStoreRoomMovementRequest;
import zelisline.ub.storeroom.api.dto.StoreRoomActivityResponse;
import zelisline.ub.storeroom.api.dto.StoreRoomMovementResponse;
import zelisline.ub.storeroom.domain.StoreItem;
import zelisline.ub.storeroom.domain.StoreRoomDirection;
import zelisline.ub.storeroom.domain.StoreRoomMode;
import zelisline.ub.storeroom.domain.StoreRoomMovement;
import zelisline.ub.storeroom.domain.StoreRoomReason;
import zelisline.ub.storeroom.domain.StoreRoomStockEffect;
import zelisline.ub.storeroom.repository.StoreItemRepository;
import zelisline.ub.storeroom.repository.StoreRoomMovementRepository;
import zelisline.ub.tenancy.application.BranchResolutionService;

/**
 * Records take-outs and put-ins from the store room.
 *
 * <p>The rule that matters: <b>only Class B reasons move stock</b>. Class A
 * ("restock to shelf", prep, counter transfer) leave the shop's stock alone — the
 * goods never left the business, so decrementing would drain inventory every time
 * a shelf was filled. See docs/scopes/STORE_ROOM_MANAGEMENT_SCOPE.md §5.
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
        boolean movesStock = reason.stockEffect() == StoreRoomStockEffect.DECREASE;

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
        row.setStockEffect(reason.stockEffect());
        row.setQuantity(quantity);
        row.setNote(note);
        row.setCreatedBy(actorId);

        if (movesStock) {
            if (linked) {
                applyInventoryDecrease(
                        businessId, item, quantity, reason, request, actorId, actorRoleId, sessionBranchId, row);
            } else {
                applyLocalDecrease(item, quantity);
            }
        }

        movementRepository.save(row);
        return decorate(businessId, List.of(row)).get(0);
    }

    /**
     * The activity trail for a window.
     *
     * <p>The caller supplies the window because the dashboard knows its own local
     * day; the server has no opinion about what "today" means for the shop.
     *
     * <p>The summary is computed from the movements actually returned, which is
     * capped at {@value #MAX_PAGE}. A shop recording more than that in one window
     * would need the counts moved to aggregate queries.
     */
    @Transactional(readOnly = true)
    public StoreRoomActivityResponse activity(String businessId, Instant from, Instant to, Integer limit) {
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(Duration.ofHours(24));
        int page = limit == null || limit <= 0 ? DEFAULT_PAGE : Math.min(limit, MAX_PAGE);

        List<StoreRoomMovement> rows = movementRepository
                .findByBusinessIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
                        businessId, start, end, PageRequest.of(0, page));

        int takeOuts = 0;
        int putIns = 0;
        BigDecimal stockLoss = ZERO;
        for (StoreRoomMovement row : rows) {
            if (row.getDirection() == StoreRoomDirection.OUT) {
                takeOuts++;
            } else {
                putIns++;
            }
            if (row.getStockEffect() == StoreRoomStockEffect.DECREASE) {
                stockLoss = stockLoss.add(row.getQuantity());
            }
        }

        return new StoreRoomActivityResponse(
                start,
                end,
                new StoreRoomActivityResponse.Summary(rows.size(), takeOuts, putIns, stockLoss),
                decorate(businessId, rows));
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

    /**
     * Linked rows change real stock. The inventory ledger is the stock of record, so
     * the write goes through it — FEFO allocation, batch depletion and the shrinkage
     * journal all stay in one place.
     */
    private void applyInventoryDecrease(
            String businessId,
            StoreItem item,
            BigDecimal quantity,
            StoreRoomReason reason,
            CreateStoreRoomMovementRequest request,
            String actorId,
            String actorRoleId,
            String sessionBranchId,
            StoreRoomMovement row
    ) {
        String branchId = resolveBranch(businessId, sessionBranchId, actorRoleId, request.branchId());

        // Preview first: it both proves there is enough stock at this shop and gives
        // the per-batch allocation a plain decrease needs.
        List<BatchAllocationLine> lines =
                batchPickerService.previewAllocation(businessId, item.getItemId(), branchId, quantity);
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
                            + " of \"" + item.getName() + "\" at this shop.");
        }

        String ledgerNote = ledgerNote(reason, row.getNote());
        row.setBranchId(branchId);

        if (reason.usesWastagePath()) {
            InventoryMutationResponse result = inventoryLedgerService.recordStandaloneWastage(
                    businessId,
                    new PostStandaloneWastageRequest(
                            branchId,
                            item.getItemId(),
                            quantity,
                            wasteCost(item, lines),
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
     * own cost, so this only has to be positive. Prefer the price the merchant typed.
     */
    private static BigDecimal wasteCost(StoreItem item, List<BatchAllocationLine> lines) {
        BigDecimal typed = item.getBuyingPrice();
        if (typed != null && typed.signum() > 0) {
            return typed;
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
        Map<String, String> userNames = new HashMap<>();
        for (User user : userRepository.findLiveByIds(userIds)) {
            userNames.put(user.getId(), user.getName());
        }

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
                    row.getQuantity(),
                    row.getNote(),
                    row.getMovementId(),
                    row.getMovementCount(),
                    row.getBranchId(),
                    row.getCreatedAt(),
                    row.getCreatedBy(),
                    userNames.get(row.getCreatedBy())));
        }
        return out;
    }

    private static String normalizeNote(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
