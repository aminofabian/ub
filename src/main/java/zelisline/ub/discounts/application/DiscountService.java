package zelisline.ub.discounts.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.catalog.domain.Category;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.CategoryRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.discounts.api.dto.CreateDiscountRequest;
import zelisline.ub.discounts.api.dto.DiscountPreviewLine;
import zelisline.ub.discounts.api.dto.DiscountPreviewResponse;
import zelisline.ub.discounts.api.dto.DiscountResponse;
import zelisline.ub.discounts.api.dto.PreviewDiscountRequest;
import zelisline.ub.discounts.api.dto.ResolvedPriceResponse;
import zelisline.ub.discounts.api.dto.UpdateDiscountRequest;
import zelisline.ub.discounts.domain.Discount;
import zelisline.ub.discounts.domain.DiscountCategory;
import zelisline.ub.discounts.domain.DiscountExclusion;
import zelisline.ub.discounts.domain.DiscountItem;
import zelisline.ub.discounts.domain.DiscountKinds;
import zelisline.ub.discounts.domain.DiscountMethods;
import zelisline.ub.discounts.domain.DiscountScopes;
import zelisline.ub.discounts.domain.DiscountStatuses;
import zelisline.ub.discounts.domain.DiscountSupplier;
import zelisline.ub.discounts.repository.DiscountCategoryRepository;
import zelisline.ub.discounts.repository.DiscountExclusionRepository;
import zelisline.ub.discounts.repository.DiscountItemRepository;
import zelisline.ub.discounts.repository.DiscountRepository;
import zelisline.ub.discounts.repository.DiscountSupplierRepository;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.suppliers.domain.SupplierProduct;
import zelisline.ub.suppliers.repository.SupplierProductRepository;

@Service
@RequiredArgsConstructor
public class DiscountService {

    private static final int PREVIEW_SAMPLE = 8;
    private static final int PREVIEW_LARGE_THRESHOLD = 2000;
    private static final int MONEY_SCALE = 2;

    private final DiscountRepository discountRepository;
    private final DiscountItemRepository discountItemRepository;
    private final DiscountCategoryRepository discountCategoryRepository;
    private final DiscountSupplierRepository discountSupplierRepository;
    private final DiscountExclusionRepository discountExclusionRepository;
    private final DiscountStatusDeriver statusDeriver;
    private final DiscountResolutionService discountResolutionService;
    private final ItemRepository itemRepository;
    private final CategoryRepository categoryRepository;
    private final BranchRepository branchRepository;
    private final SupplierProductRepository supplierProductRepository;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    @Transactional(readOnly = true)
    public List<DiscountResponse> list(String businessId) {
        return discountRepository.findByBusinessIdOrderByCreatedAtDesc(businessId).stream()
                .map(d -> toResponse(businessId, d))
                .toList();
    }

    @Transactional(readOnly = true)
    public DiscountResponse get(String businessId, String discountId) {
        Discount discount = requireDiscount(businessId, discountId);
        return toResponse(businessId, discount);
    }

    @Transactional
    public DiscountResponse create(String businessId, String userId, CreateDiscountRequest req) {
        validateMethod(req.method());
        validateScope(req.scope());
        validateWindow(req.startAt(), req.endAt(), false);
        validateTargets(req.scope(), req.itemIds(), req.categoryIds(), req.supplierIds());

        Discount discount = new Discount();
        discount.setBusinessId(businessId);
        discount.setCreatedBy(userId);
        discount.setUpdatedBy(userId);
        applyFields(discount, req.name(), req.description(), req.method(), req.value(), req.scope(),
                req.branchId(), req.startAt(), req.endAt());
        discount.setPriority(defaultPriority(req.scope()));
        discount.setKind(DiscountKinds.STANDARD);
        discount.setPaused(false);

        if (Boolean.TRUE.equals(req.publish())) {
            validatePublishWindow(req.startAt(), req.endAt());
            discount.setPublishedAt(Instant.now());
        }

        discountRepository.save(discount);
        replaceTargets(discount, req.itemIds(), req.categoryIds(), req.supplierIds(),
                req.includeAnyLinkedSupplier(), req.excludedItemIds());

        publishAudit(businessId, userId,
                Boolean.TRUE.equals(req.publish()) ? AuditEventTypes.DISCOUNT_PUBLISHED : AuditEventTypes.DISCOUNT_CREATED,
                discount);

        return toResponse(businessId, discount);
    }

    @Transactional
    public DiscountResponse update(String businessId, String discountId, String userId, UpdateDiscountRequest req) {
        Discount discount = requireDiscount(businessId, discountId);
        if (discount.getPublishedAt() != null && DiscountStatuses.EXPIRED.equals(statusDeriver.deriveStatus(discount, Instant.now()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Expired discounts cannot be edited");
        }
        if (discount.getVersion() != req.version()) {
            throw conflictVersion(discount);
        }
        validateMethod(req.method());
        validateScope(req.scope());
        validateWindow(req.startAt(), req.endAt(), discount.getPublishedAt() != null);
        validateTargets(req.scope(), req.itemIds(), req.categoryIds(), req.supplierIds());

        discount.setUpdatedBy(userId);
        applyFields(discount, req.name(), req.description(), req.method(), req.value(), req.scope(),
                req.branchId(), req.startAt(), req.endAt());
        replaceTargets(discount, req.itemIds(), req.categoryIds(), req.supplierIds(),
                req.includeAnyLinkedSupplier(), req.excludedItemIds());

        try {
            discountRepository.save(discount);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw conflictVersion(requireDiscount(businessId, discountId));
        }

        publishAudit(businessId, userId, AuditEventTypes.DISCOUNT_UPDATED, discount);
        return toResponse(businessId, discount);
    }

    @Transactional
    public DiscountResponse publish(String businessId, String discountId, String userId) {
        Discount discount = requireDiscount(businessId, discountId);
        if (discount.getPublishedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Discount is already published");
        }
        validatePublishWindow(discount.getStartAt(), discount.getEndAt());
        discount.setPublishedAt(Instant.now());
        discount.setUpdatedBy(userId);
        discountRepository.save(discount);
        publishAudit(businessId, userId, AuditEventTypes.DISCOUNT_PUBLISHED, discount);
        return toResponse(businessId, discount);
    }

    @Transactional
    public DiscountResponse pause(String businessId, String discountId, String userId) {
        Discount discount = requireDiscount(businessId, discountId);
        requirePublished(discount);
        discount.setPaused(true);
        discount.setUpdatedBy(userId);
        discountRepository.save(discount);
        publishAudit(businessId, userId, AuditEventTypes.DISCOUNT_PAUSED, discount);
        return toResponse(businessId, discount);
    }

    @Transactional
    public DiscountResponse resume(String businessId, String discountId, String userId) {
        Discount discount = requireDiscount(businessId, discountId);
        requirePublished(discount);
        discount.setPaused(false);
        discount.setUpdatedBy(userId);
        discountRepository.save(discount);
        publishAudit(businessId, userId, AuditEventTypes.DISCOUNT_RESUMED, discount);
        return toResponse(businessId, discount);
    }

    @Transactional
    public DiscountResponse duplicate(String businessId, String discountId, String userId) {
        Discount source = requireDiscount(businessId, discountId);
        Discount copy = new Discount();
        copy.setBusinessId(businessId);
        copy.setName(source.getName() + " (copy)");
        copy.setDescription(source.getDescription());
        copy.setKind(source.getKind());
        copy.setMethod(source.getMethod());
        copy.setValue(source.getValue());
        copy.setScope(source.getScope());
        copy.setBranchId(source.getBranchId());
        copy.setStartAt(source.getStartAt());
        copy.setEndAt(source.getEndAt());
        copy.setPriority(source.getPriority());
        copy.setPaused(false);
        copy.setPublishedAt(null);
        copy.setCreatedBy(userId);
        copy.setUpdatedBy(userId);
        discountRepository.save(copy);

        replaceTargets(copy,
                listItemIds(source.getId()),
                listCategoryIds(source.getId()),
                listSupplierIds(source.getId()),
                includeAnyLinked(source.getId()),
                listExcludedItemIds(source.getId()));

        publishAudit(businessId, userId, AuditEventTypes.DISCOUNT_DUPLICATED, copy);
        return toResponse(businessId, copy);
    }

    @Transactional
    public void deleteDraft(String businessId, String discountId, String userId) {
        Discount discount = requireDiscount(businessId, discountId);
        if (discount.getPublishedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only drafts can be deleted");
        }
        discountRepository.delete(discount);
        publishAudit(businessId, userId, AuditEventTypes.DISCOUNT_DELETED, discount);
    }

    @Transactional(readOnly = true)
    public DiscountPreviewResponse preview(String businessId, PreviewDiscountRequest req) {
        validateMethod(req.method());
        validateScope(req.scope());
        if (req.endAt() != null && req.startAt() != null && !req.endAt().isAfter(req.startAt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "End must be after start");
        }
        validateTargets(req.scope(), req.itemIds(), req.categoryIds(), req.supplierIds());

        List<String> candidateIds = resolveCandidateItemIds(businessId, req);
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (candidateIds.size() > PREVIEW_LARGE_THRESHOLD) {
            warnings.add("Large scope — preview shows a sample");
        }

        List<DiscountPreviewLine> sample = new ArrayList<>();
        BigDecimal minSaved = null;
        BigDecimal maxSaved = null;
        int sampled = 0;

        for (String itemId : candidateIds) {
            ResolvedPriceResponse resolved = discountResolutionService.resolveForItem(businessId, itemId, req.branchId());
            if (resolved.regularPrice() == null) {
                continue;
            }
            ResolvedPriceResponse hypothetical = applyHypothetical(resolved.regularPrice(), req.method(), req.value());
            if (DiscountMethods.FIXED_AMOUNT.equals(req.method())
                    && req.value().compareTo(resolved.regularPrice()) > 0) {
                errors.add("Fixed amount exceeds regular price for " + itemId);
                continue;
            }
            BigDecimal saved = hypothetical.savedAmount();
            minSaved = minSaved == null ? saved : minSaved.min(saved);
            maxSaved = maxSaved == null ? saved : maxSaved.max(saved);
            if (sampled < PREVIEW_SAMPLE) {
                Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId).orElse(null);
                sample.add(new DiscountPreviewLine(
                        itemId,
                        item != null ? item.getName() : itemId,
                        hypothetical.regularPrice(),
                        hypothetical.finalPrice(),
                        saved));
                sampled++;
            }
        }

        return new DiscountPreviewResponse(
                candidateIds.size(),
                sample,
                minSaved,
                maxSaved,
                warnings,
                errors);
    }

    private ResolvedPriceResponse applyHypothetical(BigDecimal regular, String method, BigDecimal value) {
        Discount temp = new Discount();
        temp.setMethod(method);
        temp.setValue(value);
        BigDecimal finalPrice = discountResolutionServiceApply(temp, regular);
        BigDecimal saved = regular.subtract(finalPrice).max(BigDecimal.ZERO).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        return new ResolvedPriceResponse(regular, finalPrice, saved, null);
    }

    private BigDecimal discountResolutionServiceApply(Discount discount, BigDecimal regular) {
        return switch (discount.getMethod()) {
            case DiscountMethods.PERCENTAGE -> {
                BigDecimal factor = BigDecimal.ONE.subtract(discount.getValue().movePointLeft(2));
                yield zelisline.ub.pricing.application.SuggestedSellPriceRounding.round(regular.multiply(factor));
            }
            case DiscountMethods.FIXED_AMOUNT ->
                    zelisline.ub.pricing.application.SuggestedSellPriceRounding.round(regular.subtract(discount.getValue()));
            default -> regular;
        };
    }

    private List<String> resolveCandidateItemIds(String businessId, PreviewDiscountRequest req) {
        Set<String> excluded = req.excludedItemIds() == null ? Set.of() : new HashSet<>(req.excludedItemIds());
        return switch (req.scope()) {
            case DiscountScopes.ITEM -> distinct(req.itemIds()).stream()
                    .filter(id -> !excluded.contains(id))
                    .toList();

            case DiscountScopes.STORE -> itemRepository.findByBusinessIdAndDeletedAtIsNull(businessId).stream()
                    .filter(i -> i.isActive() && i.isSellable())
                    .map(Item::getId)
                    .filter(id -> !excluded.contains(id))
                    .toList();

            case DiscountScopes.CATEGORY -> {
                Set<String> categoryTreeIds = new HashSet<>();
                List<Category> categories = categoryRepository.findByBusinessIdOrderByPositionAsc(businessId);
                Map<String, Category> byId = new HashMap<>();
                for (Category c : categories) {
                    byId.put(c.getId(), c);
                }
                for (String categoryId : distinct(req.categoryIds())) {
                    categoryTreeIds.addAll(descendantsIncludingSelf(categoryId, byId));
                }

                List<Item> all = itemRepository.findByBusinessIdAndDeletedAtIsNull(businessId);
                yield all.stream()
                        .filter(i -> i.isActive() && i.isSellable())
                        .filter(i -> i.getCategoryId() != null && categoryTreeIds.contains(i.getCategoryId()))
                        .map(Item::getId)
                        .filter(id -> !excluded.contains(id))
                        .toList();
            }

            case DiscountScopes.SUPPLIER -> {
                boolean includeAnyLinked = Boolean.TRUE.equals(req.includeAnyLinkedSupplier());
                Set<String> candidateItemIds = new HashSet<>();
                for (String supplierId : distinct(req.supplierIds())) {
                    List<SupplierProduct> links = supplierProductRepository.listForSupplier(businessId, supplierId);
                    for (SupplierProduct link : links) {
                        if (link.getDeletedAt() != null || !link.isActive()) {
                            continue;
                        }
                        if (includeAnyLinked || link.isPrimaryLink()) {
                            candidateItemIds.add(link.getItemId());
                        }
                    }
                }
                if (candidateItemIds.isEmpty()) {
                    yield List.of();
                }
                List<Item> items = itemRepository.findByIdInAndBusinessIdAndDeletedAtIsNull(candidateItemIds, businessId);
                yield items.stream()
                        .filter(i -> i.isActive() && i.isSellable())
                        .map(Item::getId)
                        .filter(id -> !excluded.contains(id))
                        .toList();
            }

            default -> List.of();
        };
    }

    private DiscountResponse toResponse(String businessId, Discount discount) {
        Instant now = Instant.now();
        return new DiscountResponse(
                discount.getId(),
                discount.getName(),
                discount.getDescription(),
                discount.getKind(),
                discount.getMethod(),
                discount.getValue(),
                discount.getScope(),
                discount.getBranchId(),
                discount.getStartAt(),
                discount.getEndAt(),
                discount.isPaused(),
                discount.getPublishedAt(),
                discount.getPriority(),
                discount.getVersion(),
                statusDeriver.deriveStatus(discount, now),
                countAffected(businessId, discount),
                listItemIds(discount.getId()),
                listCategoryIds(discount.getId()),
                listSupplierIds(discount.getId()),
                includeAnyLinked(discount.getId()),
                listExcludedItemIds(discount.getId()),
                discount.getCreatedAt(),
                discount.getUpdatedAt());
    }

    private long countAffected(String businessId, Discount discount) {
        return switch (discount.getScope()) {
            case DiscountScopes.ITEM -> discountItemRepository.countByDiscountId(discount.getId());
            default -> 0L;
        };
    }

    private void replaceTargets(
            Discount discount,
            List<String> itemIds,
            List<String> categoryIds,
            List<String> supplierIds,
            Boolean includeAnyLinked,
            List<String> excludedItemIds
    ) {
        String discountId = discount.getId();
        discountItemRepository.deleteByIdDiscountId(discountId);
        discountCategoryRepository.deleteByIdDiscountId(discountId);
        discountSupplierRepository.deleteByIdDiscountId(discountId);
        discountExclusionRepository.deleteByIdDiscountId(discountId);

        if (DiscountScopes.ITEM.equals(discount.getScope())) {
            for (String itemId : distinct(itemIds)) {
                DiscountItem row = new DiscountItem();
                DiscountItem.DiscountItemId id = new DiscountItem.DiscountItemId();
                id.setDiscountId(discountId);
                id.setItemId(itemId);
                row.setId(id);
                discountItemRepository.save(row);
            }
        }
        if (DiscountScopes.CATEGORY.equals(discount.getScope())) {
            for (String categoryId : distinct(categoryIds)) {
                DiscountCategory row = new DiscountCategory();
                DiscountCategory.DiscountCategoryId id = new DiscountCategory.DiscountCategoryId();
                id.setDiscountId(discountId);
                id.setCategoryId(categoryId);
                row.setId(id);
                discountCategoryRepository.save(row);
            }
        }
        if (DiscountScopes.SUPPLIER.equals(discount.getScope())) {
            for (String supplierId : distinct(supplierIds)) {
                DiscountSupplier row = new DiscountSupplier();
                DiscountSupplier.DiscountSupplierId id = new DiscountSupplier.DiscountSupplierId();
                id.setDiscountId(discountId);
                id.setSupplierId(supplierId);
                row.setId(id);
                row.setIncludeAnyLinked(Boolean.TRUE.equals(includeAnyLinked));
                discountSupplierRepository.save(row);
            }
        }
        for (String excludedId : distinct(excludedItemIds)) {
            DiscountExclusion row = new DiscountExclusion();
            DiscountExclusion.DiscountExclusionId id = new DiscountExclusion.DiscountExclusionId();
            id.setDiscountId(discountId);
            id.setItemId(excludedId);
            row.setId(id);
            discountExclusionRepository.save(row);
        }
    }

    private void applyFields(
            Discount discount,
            String name,
            String description,
            String method,
            BigDecimal value,
            String scope,
            String branchId,
            Instant startAt,
            Instant endAt
    ) {
        discount.setName(name.trim());
        discount.setDescription(blankToNull(description));
        discount.setMethod(method);
        discount.setValue(value);
        discount.setScope(scope);
        String br = blankToNull(branchId);
        if (br != null) {
            branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(br, discount.getBusinessId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch not found"));
        }
        discount.setBranchId(br);
        discount.setStartAt(startAt);
        discount.setEndAt(endAt);
    }

    private static int defaultPriority(String scope) {
        return switch (scope) {
            case DiscountScopes.ITEM -> 100;
            case DiscountScopes.CATEGORY -> 50;
            case DiscountScopes.SUPPLIER -> 25;
            case DiscountScopes.STORE -> 10;
            default -> 0;
        };
    }

    private static void validateMethod(String method) {
        if (!DiscountMethods.PERCENTAGE.equals(method) && !DiscountMethods.FIXED_AMOUNT.equals(method)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid discount method");
        }
    }

    private static void validateScope(String scope) {
        if (!DiscountScopes.ITEM.equals(scope)
                && !DiscountScopes.CATEGORY.equals(scope)
                && !DiscountScopes.SUPPLIER.equals(scope)
                && !DiscountScopes.STORE.equals(scope)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid discount scope");
        }
    }

    private static void validateTargets(
            String scope,
            List<String> itemIds,
            List<String> categoryIds,
            List<String> supplierIds
    ) {
        if (DiscountScopes.ITEM.equals(scope) && distinct(itemIds).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select at least one product");
        }
        if (DiscountScopes.CATEGORY.equals(scope) && distinct(categoryIds).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select at least one category");
        }
        if (DiscountScopes.SUPPLIER.equals(scope) && distinct(supplierIds).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select at least one supplier");
        }
    }

    private static void validateWindow(Instant startAt, Instant endAt, boolean published) {
        if (endAt != null && !endAt.isAfter(startAt)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "End must be after start");
        }
        if (endAt != null && startAt.equals(endAt)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Zero-duration discount is not allowed");
        }
    }

    private static void validatePublishWindow(Instant startAt, Instant endAt) {
        validateWindow(startAt, endAt, true);
        if (startAt.isBefore(Instant.now().minusSeconds(60))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Start time cannot be in the past");
        }
    }

    private static void requirePublished(Discount discount) {
        if (discount.getPublishedAt() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Discount is not published");
        }
    }

    private Discount requireDiscount(String businessId, String discountId) {
        return discountRepository.findByIdAndBusinessId(discountId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Discount not found"));
    }

    private static ResponseStatusException conflictVersion(Discount current) {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Discount was updated by another user");
    }

    private List<String> listItemIds(String discountId) {
        return discountItemRepository.findByIdDiscountId(discountId).stream()
                .map(r -> r.getId().getItemId())
                .toList();
    }

    private List<String> listCategoryIds(String discountId) {
        return discountCategoryRepository.findByIdDiscountId(discountId).stream()
                .map(r -> r.getId().getCategoryId())
                .toList();
    }

    private List<String> listSupplierIds(String discountId) {
        return discountSupplierRepository.findByIdDiscountId(discountId).stream()
                .map(r -> r.getId().getSupplierId())
                .toList();
    }

    private boolean includeAnyLinked(String discountId) {
        return discountSupplierRepository.findByIdDiscountId(discountId).stream()
                .anyMatch(DiscountSupplier::isIncludeAnyLinked);
    }

    private List<String> listExcludedItemIds(String discountId) {
        return discountExclusionRepository.findByIdDiscountId(discountId).stream()
                .map(r -> r.getId().getItemId())
                .toList();
    }

    private static List<String> distinct(List<String> ids) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
    }

    private static Set<String> descendantsIncludingSelf(String rootId, Map<String, Category> byId) {
        Set<String> out = new HashSet<>();
        collectDescendants(rootId, byId, out, 0);
        return out;
    }

    private static void collectDescendants(
            String id,
            Map<String, Category> byId,
            Set<String> out,
            int depth
    ) {
        if (id == null || depth > 128) {
            return;
        }
        out.add(id);
        for (Category c : byId.values()) {
            if (id.equals(c.getParentId())) {
                collectDescendants(c.getId(), byId, out, depth + 1);
            }
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private void publishAudit(String businessId, String userId, String eventType, Discount discount) {
        auditEventPublisher.publish(auditEventBuilder.builder(AuditEventCategory.PRODUCTS, eventType, AuditEventSeverity.INFO)
                .businessId(businessId)
                .branchId(discount.getBranchId())
                .actor(userId, AuditEventActorType.USER)
                .target("discount", discount.getId())
                .targetLabel(discount.getName())
                .source("web_admin")
                .build());
    }
}
