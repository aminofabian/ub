package zelisline.ub.restaurant.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
import zelisline.ub.restaurant.api.dto.RestaurantDtos.IssueRequisitionRequest;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.IssuedLineRequest;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.RequisitionLineRequest;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.RequisitionLineResponse;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.RequisitionProductResponse;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.RequisitionListResponse;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.RequisitionRequest;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.RequisitionResponse;
import zelisline.ub.restaurant.domain.KitchenRequisition;
import zelisline.ub.restaurant.domain.KitchenRequisitionLine;
import zelisline.ub.restaurant.domain.KitchenStation;
import zelisline.ub.restaurant.repository.KitchenRequisitionLineRepository;
import zelisline.ub.restaurant.repository.KitchenRequisitionRepository;
import zelisline.ub.restaurant.repository.KitchenStationRepository;
import zelisline.ub.tenancy.repository.BranchRepository;

/**
 * The kitchen asks the store for ingredients; whoever keeps the store gives them out or refuses
 * (ADR 0020, amendment 4).
 *
 * <p>This is a record, not a stock movement. A branch has one stock count: an ingredient is on it
 * whether it sits in the store or by the stove, and it leaves when the dish it went into is sold
 * (or is written off as waste). Taking it off the count here as well would take it twice. What the
 * record gives the owner is who asked for what, what they were given and when — the thing to hold
 * against what the recipes say was used.
 *
 * <p>Every read and write is scoped to the business and to one branch.
 */
@Service
@RequiredArgsConstructor
public class KitchenRequisitionService {

    /** Requisitions waiting on a decision at one branch. More than this and nobody is reading them. */
    static final int MAX_WAITING = 100;
    private static final Duration RECENT = Duration.ofDays(7);

    private final KitchenRequisitionRepository requisitionRepository;
    private final KitchenRequisitionLineRepository lineRepository;
    private final KitchenStationRepository stationRepository;
    private final ItemRepository itemRepository;
    private final BranchRepository branchRepository;
    private final UserRepository userRepository;
    private final RestaurantGate gate;

    @Transactional(readOnly = true)
    public RequisitionListResponse list(String businessId, String branchId) {
        gate.requireEnabled(businessId);
        List<KitchenRequisition> waiting = requisitionRepository
                .findByBusinessIdAndBranchIdAndStatusOrderByRequestedAtAsc(businessId, branchId, KitchenRequisition.REQUESTED);
        List<KitchenRequisition> recent = requisitionRepository
                .findTop50ByBusinessIdAndBranchIdAndStatusNotAndRequestedAtAfterOrderByRequestedAtDesc(
                        businessId, branchId, KitchenRequisition.REQUESTED, Instant.now().minus(RECENT));
        List<KitchenRequisition> all = new ArrayList<>(waiting);
        all.addAll(recent);
        Map<String, RequisitionResponse> byId = toResponses(businessId, all);
        return new RequisitionListResponse(branchId,
                waiting.stream().map(r -> byId.get(r.getId())).toList(),
                recent.stream().map(r -> byId.get(r.getId())).toList());
    }

    /** The branch a requisition belongs to, for the caller's branch check. */
    @Transactional(readOnly = true)
    public String branchOf(String businessId, String requisitionId) {
        return requisitionRepository.findByIdAndBusinessId(requisitionId, businessId)
                .map(KitchenRequisition::getBranchId)
                .orElseThrow(KitchenRequisitionService::notFound);
    }

    /**
     * Products the kitchen may ask for, by name, brand, barcode or SKU. The catalogue list needs
     * {@code catalog.items.read}, which a cook does not hold (it shows prices); this answer is the
     * smaller one they need — what is stocked, without money.
     */
    @Transactional(readOnly = true)
    public List<RequisitionProductResponse> searchProducts(String businessId, String search) {
        gate.requireEnabled(businessId);
        String term = search == null ? "" : search.trim();
        if (term.length() < 2) {
            return List.of();
        }
        return itemRepository.searchStockedForKitchen(businessId, term, PageRequest.of(0, 20)).stream()
                .map(item -> new RequisitionProductResponse(item.getId(), displayName(item), item.getUnitType()))
                .toList();
    }

    private static String displayName(Item item) {
        String variant = item.getVariantName();
        return variant == null || variant.isBlank() ? item.getName() : item.getName() + " " + variant.trim();
    }

    /** Asks the store for products. Sending the same request again returns the one already made. */
    @Transactional
    public RequisitionResponse request(String businessId, String branchId, String userId, RequisitionRequest request) {
        gate.requireEnabled(businessId);
        KitchenRequisition existing = requisitionRepository
                .findByBusinessIdAndClientKey(businessId, request.clientKey()).orElse(null);
        if (existing != null) {
            if (!existing.getBranchId().equals(branchId)) {
                throw notFound();
            }
            return one(businessId, existing);
        }
        // Locked, so two requests made at once at a branch get different numbers.
        branchRepository.findByIdAndBusinessIdForUpdate(branchId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found"));
        if (requisitionRepository.countByBusinessIdAndBranchIdAndStatus(businessId, branchId, KitchenRequisition.REQUESTED)
                >= MAX_WAITING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Too many requests are waiting on the store; ask someone to deal with them first");
        }
        String stationId = null;
        if (request.stationId() != null && !request.stationId().isBlank()) {
            stationId = stationRepository.findByIdAndBusinessId(request.stationId().trim(), businessId)
                    .map(KitchenStation::getId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kitchen station not found"));
        }
        KitchenRequisition row = new KitchenRequisition();
        row.setBusinessId(businessId);
        row.setBranchId(branchId);
        row.setStationId(stationId);
        row.setRequisitionNo(requisitionRepository.lastNumber(businessId, branchId) + 1);
        row.setNote(blankToNull(request.note()));
        row.setClientKey(request.clientKey());
        row.setRequestedBy(userId);
        requisitionRepository.saveAndFlush(row);

        Set<String> seen = new HashSet<>();
        int order = 0;
        for (RequisitionLineRequest line : request.lines()) {
            Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(line.itemId().trim(), businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Product not found"));
            if (!item.isStocked()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        item.getName() + " is not stocked, so the store has none to give");
            }
            if (!seen.add(item.getId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, item.getName() + " is on the request twice");
            }
            KitchenRequisitionLine out = new KitchenRequisitionLine();
            out.setBusinessId(businessId);
            out.setRequisitionId(row.getId());
            out.setItemId(item.getId());
            out.setItemName(item.getName().length() > 255 ? item.getName().substring(0, 255) : item.getName());
            out.setUnit(item.getUnitType());
            out.setQuantityRequested(line.quantity().setScale(4, RoundingMode.HALF_UP));
            out.setSortOrder(order++);
            lineRepository.save(out);
        }
        lineRepository.flush();
        return one(businessId, row);
    }

    /**
     * Gives out what was asked for, in full unless a line says otherwise. Issuing a requisition
     * that is already issued changes nothing and returns it as it stands.
     */
    @Transactional
    public RequisitionResponse issue(String businessId, String requisitionId, String userId, IssueRequisitionRequest request) {
        gate.requireEnabled(businessId);
        KitchenRequisition row = locked(businessId, requisitionId);
        if (KitchenRequisition.ISSUED.equals(row.getStatus())) {
            return one(businessId, row);
        }
        requireWaiting(row);
        Map<String, BigDecimal> given = new HashMap<>();
        if (request != null && request.lines() != null) {
            for (IssuedLineRequest line : request.lines()) {
                given.put(line.lineId(), line.quantity());
            }
        }
        List<KitchenRequisitionLine> lines =
                lineRepository.findByBusinessIdAndRequisitionIdOrderBySortOrderAsc(businessId, row.getId());
        Set<String> known = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (KitchenRequisitionLine line : lines) {
            known.add(line.getId());
            BigDecimal quantity = given.containsKey(line.getId()) ? given.get(line.getId()) : line.getQuantityRequested();
            line.setQuantityIssued(quantity.setScale(4, RoundingMode.HALF_UP));
            total = total.add(line.getQuantityIssued());
        }
        if (!known.containsAll(given.keySet())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That line is not on this request");
        }
        if (total.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Nothing is being given; refuse the request instead and say why");
        }
        lineRepository.saveAll(lines);
        decide(row, KitchenRequisition.ISSUED, userId, request == null ? null : request.note());
        return one(businessId, row);
    }

    @Transactional
    public RequisitionResponse refuse(String businessId, String requisitionId, String userId, String reason) {
        gate.requireEnabled(businessId);
        KitchenRequisition row = locked(businessId, requisitionId);
        if (KitchenRequisition.REFUSED.equals(row.getStatus())) {
            return one(businessId, row);
        }
        requireWaiting(row);
        decide(row, KitchenRequisition.REFUSED, userId, reason);
        return one(businessId, row);
    }

    /** Withdraws a request nobody has decided yet. Only the person who made it, or someone who can decide it. */
    @Transactional
    public RequisitionResponse cancel(String businessId, String requisitionId, String userId, boolean canDecide) {
        gate.requireEnabled(businessId);
        KitchenRequisition row = locked(businessId, requisitionId);
        if (KitchenRequisition.CANCELLED.equals(row.getStatus())) {
            return one(businessId, row);
        }
        requireWaiting(row);
        if (!canDecide && !row.getRequestedBy().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only whoever asked can withdraw this request");
        }
        decide(row, KitchenRequisition.CANCELLED, userId, null);
        return one(businessId, row);
    }

    private void decide(KitchenRequisition row, String status, String userId, String note) {
        row.setStatus(status);
        row.setDecidedBy(userId);
        row.setDecidedAt(Instant.now());
        row.setDecisionNote(blankToNull(note));
        requisitionRepository.saveAndFlush(row);
    }

    private KitchenRequisition locked(String businessId, String requisitionId) {
        return requisitionRepository.findByIdAndBusinessIdForUpdate(requisitionId, businessId)
                .orElseThrow(KitchenRequisitionService::notFound);
    }

    private static void requireWaiting(KitchenRequisition row) {
        if (!KitchenRequisition.REQUESTED.equals(row.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This request has already been dealt with");
        }
    }

    private RequisitionResponse one(String businessId, KitchenRequisition row) {
        return toResponses(businessId, List.of(row)).get(row.getId());
    }

    private Map<String, RequisitionResponse> toResponses(String businessId, List<KitchenRequisition> rows) {
        Map<String, RequisitionResponse> out = new HashMap<>();
        if (rows.isEmpty()) {
            return out;
        }
        Map<String, List<KitchenRequisitionLine>> linesByParent = new HashMap<>();
        Set<String> itemIds = new HashSet<>();
        for (KitchenRequisitionLine line : lineRepository.findByBusinessIdAndRequisitionIdInOrderBySortOrderAsc(
                businessId, rows.stream().map(KitchenRequisition::getId).toList())) {
            linesByParent.computeIfAbsent(line.getRequisitionId(), k -> new ArrayList<>()).add(line);
            itemIds.add(line.getItemId());
        }
        Map<String, BigDecimal> onHand = new HashMap<>();
        if (!itemIds.isEmpty()) {
            for (Item item : itemRepository.findByIdInAndBusinessIdAndDeletedAtIsNull(new ArrayList<>(itemIds), businessId)) {
                onHand.put(item.getId(), item.getCurrentStock());
            }
        }
        Map<String, String> stationNames = new HashMap<>();
        Map<String, String> userNames = new HashMap<>();
        for (KitchenRequisition row : rows) {
            if (row.getStationId() != null) {
                stationNames.computeIfAbsent(row.getStationId(), id -> stationRepository
                        .findByIdAndBusinessId(id, businessId).map(KitchenStation::getName).orElse(null));
            }
            userNames.computeIfAbsent(row.getRequestedBy(), id -> userName(businessId, id));
            if (row.getDecidedBy() != null) {
                userNames.computeIfAbsent(row.getDecidedBy(), id -> userName(businessId, id));
            }
        }
        for (KitchenRequisition row : rows) {
            List<RequisitionLineResponse> lines = linesByParent.getOrDefault(row.getId(), List.of()).stream()
                    .map(l -> new RequisitionLineResponse(l.getId(), l.getItemId(), l.getItemName(), l.getUnit(),
                            l.getQuantityRequested(), l.getQuantityIssued(), onHand.get(l.getItemId())))
                    .toList();
            out.put(row.getId(), new RequisitionResponse(row.getId(), row.getRequisitionNo(), row.getBranchId(),
                    row.getStationId(), row.getStationId() == null ? null : stationNames.get(row.getStationId()),
                    row.getStatus(), row.getNote(), userNames.get(row.getRequestedBy()), row.getRequestedAt(),
                    row.getDecidedBy() == null ? null : userNames.get(row.getDecidedBy()), row.getDecidedAt(),
                    row.getDecisionNote(), lines));
        }
        return out;
    }

    /** A person's name, or a plain word when the account is gone; never null, so the record always reads. */
    private String userName(String businessId, String userId) {
        return userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(userId, businessId)
                .map(User::getName).filter(n -> n != null && !n.isBlank()).orElse("Someone");
    }

    private static String blankToNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String oneLine = raw.replaceAll("[\\p{Cc}\\p{Cf}]+", " ").trim();
        return oneLine.isEmpty() ? null : oneLine;
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found");
    }
}
