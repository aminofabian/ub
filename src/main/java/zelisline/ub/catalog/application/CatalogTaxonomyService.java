package zelisline.ub.catalog.application;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.api.dto.AisleResponse;
import zelisline.ub.catalog.api.dto.CategoryResponse;
import zelisline.ub.catalog.api.dto.CreateAisleRequest;
import zelisline.ub.catalog.api.dto.CreateCategoryRequest;
import zelisline.ub.catalog.api.dto.CreateItemTypeRequest;
import zelisline.ub.catalog.api.dto.ItemTypeResponse;
import zelisline.ub.catalog.api.dto.PatchCategoryRequest;
import zelisline.ub.catalog.domain.Aisle;
import zelisline.ub.catalog.domain.Category;
import zelisline.ub.catalog.domain.ItemType;
import zelisline.ub.catalog.repository.AisleRepository;
import zelisline.ub.catalog.repository.CategoryRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;

@Service
@RequiredArgsConstructor
public class CatalogTaxonomyService {

    private final CategoryRepository categoryRepository;
    private final AisleRepository aisleRepository;
    private final ItemTypeRepository itemTypeRepository;

    @Transactional(readOnly = true)
    public List<CategoryResponse> listCategories(String businessId) {
        return categoryRepository.findByBusinessIdOrderByPositionAsc(businessId).stream()
                .map(this::toCategoryResponse)
                .toList();
    }

    @Transactional
    public CategoryResponse createCategory(String businessId, CreateCategoryRequest request) {
        if (request.parentId() != null && !request.parentId().isBlank()) {
            String pid = request.parentId().trim();
            categoryRepository.findByIdAndBusinessId(pid, businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parent category not found"));
            if (CategoryCycleDetector.wouldIntroduceCycle(null, pid, id ->
                    categoryRepository.findByIdAndBusinessId(id, businessId).map(Category::getParentId))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid parent category");
            }
        }

        Category entity = new Category();
        entity.setBusinessId(businessId);
        entity.setName(request.name().trim());
        entity.setSlug(uniqueSlug(businessId, TaxonomySlug.fromName(request.name())));
        entity.setPosition(request.position() != null ? request.position() : 0);
        entity.setIcon(trimToNull(request.icon()));
        entity.setParentId(trimToNull(request.parentId()));
        entity.setActive(true);
        categoryRepository.save(entity);
        return toCategoryResponse(entity);
    }

    @Transactional
    public CategoryResponse patchCategory(String businessId, String categoryId, PatchCategoryRequest request) {
        Category category = categoryRepository.findByIdAndBusinessId(categoryId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found"));

        if (request.name() != null && !request.name().isBlank()) {
            category.setName(request.name().trim());
        }
        if (request.slug() != null && !request.slug().isBlank()) {
            String next = request.slug().trim().toLowerCase(Locale.ROOT);
            if (!next.equals(category.getSlug()) && categoryRepository.existsByBusinessIdAndSlug(businessId, next)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Slug already in use");
            }
            category.setSlug(next);
        }
        if (request.icon() != null) {
            category.setIcon(trimToNull(request.icon()));
        }
        if (request.active() != null) {
            category.setActive(request.active());
        }
        if (request.position() != null) {
            category.setPosition(request.position());
        }
        if (request.parentId() != null && !request.parentId().isBlank()) {
            String newParent = request.parentId().trim();
            if (!categoryRepository.findByIdAndBusinessId(newParent, businessId).isPresent()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parent category not found");
            }
            if (CategoryCycleDetector.wouldIntroduceCycle(category.getId(), newParent, id ->
                    categoryRepository.findByIdAndBusinessId(id, businessId).map(Category::getParentId))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Category parent would create a cycle");
            }
            category.setParentId(newParent);
        }

        categoryRepository.save(category);
        return toCategoryResponse(category);
    }

    @Transactional(readOnly = true)
    public List<AisleResponse> listAisles(String businessId) {
        return aisleRepository.findByBusinessIdOrderBySortOrderAsc(businessId).stream()
                .map(this::toAisleResponse)
                .toList();
    }

    @Transactional
    public AisleResponse createAisle(String businessId, CreateAisleRequest request) {
        String code = request.code().trim();
        if (aisleRepository.existsByBusinessIdAndCode(businessId, code)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Aisle code already exists");
        }
        Aisle row = new Aisle();
        row.setBusinessId(businessId);
        row.setName(request.name().trim());
        row.setCode(code);
        row.setSortOrder(request.sortOrder() != null ? request.sortOrder() : 0);
        row.setActive(true);
        aisleRepository.save(row);
        return toAisleResponse(row);
    }

    @Transactional(readOnly = true)
    public List<ItemTypeResponse> listItemTypes(String businessId) {
        return itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(businessId).stream()
                .map(this::toItemTypeResponse)
                .toList();
    }

    @Transactional
    public ItemTypeResponse createItemType(String businessId, CreateItemTypeRequest request) {
        String key = TaxonomySlug.normalizeItemTypeKey(request.key());
        if (key.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid item type key");
        }
        if (itemTypeRepository.existsByBusinessIdAndTypeKey(businessId, key)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Item type key already exists");
        }
        ItemType row = new ItemType();
        row.setBusinessId(businessId);
        row.setTypeKey(key);
        row.setLabel(request.label().trim());
        row.setIcon(trimToNull(request.icon()));
        row.setColor(trimToNull(request.color()));
        row.setSortOrder(request.sortOrder() != null ? request.sortOrder() : 0);
        row.setActive(true);
        itemTypeRepository.save(row);
        return toItemTypeResponse(row);
    }

    private String uniqueSlug(String businessId, String base) {
        String slug = base;
        int i = 2;
        while (categoryRepository.existsByBusinessIdAndSlug(businessId, slug)) {
            slug = base + "-" + i++;
        }
        return slug;
    }

    private CategoryResponse toCategoryResponse(Category c) {
        return new CategoryResponse(
                c.getId(),
                c.getName(),
                c.getSlug(),
                c.getPosition(),
                c.getIcon(),
                c.getParentId(),
                c.isActive()
        );
    }

    private AisleResponse toAisleResponse(Aisle a) {
        return new AisleResponse(
                a.getId(),
                a.getName(),
                a.getCode(),
                a.getSortOrder(),
                a.isActive()
        );
    }

    private ItemTypeResponse toItemTypeResponse(ItemType t) {
        return new ItemTypeResponse(
                t.getId(),
                t.getTypeKey(),
                t.getLabel(),
                t.getIcon(),
                t.getColor(),
                t.getSortOrder(),
                t.isActive()
        );
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
