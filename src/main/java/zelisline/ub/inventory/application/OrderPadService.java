package zelisline.ub.inventory.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.inventory.api.dto.OrderPadDtos.CreateOrderPadBatchRequest;
import zelisline.ub.inventory.api.dto.OrderPadDtos.CreateOrderPadItemRequest;
import zelisline.ub.inventory.api.dto.OrderPadDtos.OrderPadItemResponse;
import zelisline.ub.inventory.api.dto.OrderPadDtos.OrderPadLineInput;
import zelisline.ub.inventory.domain.OrderPadItem;
import zelisline.ub.inventory.repository.OrderPadItemRepository;
import zelisline.ub.tenancy.application.BranchResolutionService;

@Service
@RequiredArgsConstructor
public class OrderPadService {

    private final OrderPadItemRepository orderPadItemRepository;
    private final ItemRepository itemRepository;
    private final UserRepository userRepository;
    private final BranchResolutionService branchResolutionService;

    @Transactional(readOnly = true)
    public List<OrderPadItemResponse> list(
            String businessId,
            String roleId,
            String sessionBranchId,
            String requestedBranchId,
            Boolean ordered
    ) {
        if (requestedBranchId == null || requestedBranchId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "branchId is required");
        }
        String branchId = branchResolutionService.requireBranchForLockedRole(
                roleId, sessionBranchId, requestedBranchId.trim());
        List<OrderPadItem> rows = orderPadItemRepository.findForBranch(businessId, branchId, ordered);
        return toResponses(rows);
    }

    @Transactional
    public OrderPadItemResponse create(
            String businessId,
            String roleId,
            String sessionBranchId,
            String userId,
            CreateOrderPadItemRequest body
    ) {
        String branchId = branchResolutionService.requireBranchForLockedRole(
                roleId, sessionBranchId, body.branchId().trim());
        OrderPadItem row = buildRow(businessId, branchId, userId, toLine(body));
        orderPadItemRepository.save(row);
        return toResponses(List.of(row)).getFirst();
    }

    @Transactional
    public List<OrderPadItemResponse> createBatch(
            String businessId,
            String roleId,
            String sessionBranchId,
            String userId,
            CreateOrderPadBatchRequest body
    ) {
        String branchId = branchResolutionService.requireBranchForLockedRole(
                roleId, sessionBranchId, body.branchId().trim());
        List<OrderPadItem> saved = new ArrayList<>();
        for (OrderPadLineInput line : body.lines()) {
            OrderPadItem row = buildRow(businessId, branchId, userId, line);
            saved.add(orderPadItemRepository.save(row));
        }
        return toResponses(saved);
    }

    @Transactional
    public OrderPadItemResponse setOrdered(
            String businessId,
            String userId,
            String itemId,
            boolean ordered
    ) {
        OrderPadItem row = orderPadItemRepository.findByIdAndBusinessId(itemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order pad item not found"));
        if (ordered) {
            row.setOrdered(true);
            row.setOrderedBy(userId);
            row.setOrderedAt(Instant.now());
        } else {
            row.setOrdered(false);
            row.setOrderedBy(null);
            row.setOrderedAt(null);
        }
        orderPadItemRepository.save(row);
        return toResponses(List.of(row)).getFirst();
    }

    @Transactional
    public void delete(String businessId, String userId, boolean canManage, String itemId) {
        OrderPadItem row = orderPadItemRepository.findByIdAndBusinessId(itemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order pad item not found"));
        if (!canManage && !Objects.equals(row.getCreatedBy(), userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only remove lines you added");
        }
        if (!canManage && row.isOrdered()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ordered lines can only be removed by a manager");
        }
        orderPadItemRepository.delete(row);
    }

    private OrderPadItem buildRow(
            String businessId,
            String branchId,
            String userId,
            OrderPadLineInput line
    ) {
        String catalogId = blankToNull(line.itemId());
        String name = blankToNull(line.itemName());

        if (catalogId != null) {
            Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(catalogId, businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Product not found"));
            if (name == null || name.isBlank()) {
                name = item.getName();
            }
        }

        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Enter an item name or pick a product from the catalog");
        }

        BigDecimal qty = line.quantity();
        if (qty != null && qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quantity must be greater than zero when set");
        }

        OrderPadItem row = new OrderPadItem();
        row.setBusinessId(businessId);
        row.setBranchId(branchId);
        row.setItemId(catalogId);
        row.setItemName(name.trim());
        row.setQuantity(qty);
        row.setNote(blankToNull(line.note()));
        row.setOrdered(false);
        row.setCreatedBy(userId);
        return row;
    }

    private static OrderPadLineInput toLine(CreateOrderPadItemRequest body) {
        return new OrderPadLineInput(body.itemId(), body.itemName(), body.quantity(), body.note());
    }

    private List<OrderPadItemResponse> toResponses(List<OrderPadItem> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<String> userIds = rows.stream()
                .flatMap(r -> java.util.stream.Stream.of(r.getCreatedBy(), r.getOrderedBy()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, User> users = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));

        return rows.stream().map(r -> {
            User created = users.get(r.getCreatedBy());
            User orderedBy = r.getOrderedBy() != null ? users.get(r.getOrderedBy()) : null;
            return new OrderPadItemResponse(
                    r.getId(),
                    r.getBusinessId(),
                    r.getBranchId(),
                    r.getItemId(),
                    r.getItemName(),
                    r.getQuantity(),
                    r.getNote(),
                    r.isOrdered(),
                    r.getOrderedBy(),
                    orderedBy != null ? displayName(orderedBy) : null,
                    r.getOrderedAt(),
                    r.getCreatedBy(),
                    created != null ? displayName(created) : "",
                    r.getCreatedAt(),
                    r.getUpdatedAt()
            );
        }).toList();
    }

    private static String displayName(User user) {
        if (user.getName() != null && !user.getName().isBlank()) {
            return user.getName().trim();
        }
        if (user.getEmail() != null && !user.getEmail().isBlank()) {
            return user.getEmail().trim();
        }
        return "User";
    }

    private static String blankToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
