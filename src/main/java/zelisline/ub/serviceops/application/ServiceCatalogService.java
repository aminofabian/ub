package zelisline.ub.serviceops.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payroll.application.StaffProfileService;
import zelisline.ub.serviceops.api.dto.CreateServiceCategoryRequest;
import zelisline.ub.serviceops.api.dto.CreateServiceRequest;
import zelisline.ub.serviceops.api.dto.PatchServiceCategoryRequest;
import zelisline.ub.serviceops.api.dto.PatchServiceRequest;
import zelisline.ub.serviceops.api.dto.ServiceCategoryResponse;
import zelisline.ub.serviceops.api.dto.ServiceResponse;
import zelisline.ub.serviceops.domain.ServiceCategory;
import zelisline.ub.serviceops.domain.ServiceCommissionTypes;
import zelisline.ub.serviceops.domain.ServiceOffering;
import zelisline.ub.serviceops.domain.ServiceStaffLink;
import zelisline.ub.serviceops.repository.ServiceCategoryRepository;
import zelisline.ub.serviceops.repository.ServiceOfferingRepository;
import zelisline.ub.serviceops.repository.ServiceStaffLinkRepository;

/**
 * The service catalogue of a service business: categories, services and who may perform each
 * one (ADR 0014). Every method takes the business id resolved from the request and never reads
 * or writes another business's rows.
 */
@Service
@RequiredArgsConstructor
public class ServiceCatalogService {

    public static final String PERM_MANAGE = "services.catalog.manage";

    static final int MAX_PAGE_SIZE = 200;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final Sort LIST_SORT = Sort.by(Sort.Order.asc("sortOrder"), Sort.Order.asc("name"));

    private final ServiceCategoryRepository categoryRepository;
    private final ServiceOfferingRepository serviceRepository;
    private final ServiceStaffLinkRepository staffLinkRepository;
    private final StaffProfileService staffProfileService;

    // ---------------------------------------------------------------- categories

    @Transactional(readOnly = true)
    public List<ServiceCategoryResponse> listCategories(String businessId) {
        return categoryRepository.findByBusinessIdOrderBySortOrderAscNameAsc(businessId).stream()
                .map(ServiceCatalogService::toResponse)
                .toList();
    }

    @Transactional
    public ServiceCategoryResponse createCategory(String businessId, CreateServiceCategoryRequest request) {
        String name = request.name().trim();
        String key = nameKey(name);
        if (categoryRepository.existsByBusinessIdAndNameKey(businessId, key)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A service category with this name already exists");
        }
        ServiceCategory row = new ServiceCategory();
        row.setBusinessId(businessId);
        row.setName(name);
        row.setNameKey(key);
        row.setSortOrder(request.sortOrder() != null ? request.sortOrder() : 0);
        row.setActive(true);
        saveCategory(row);
        return toResponse(row);
    }

    @Transactional
    public ServiceCategoryResponse updateCategory(String businessId, String id, PatchServiceCategoryRequest request) {
        ServiceCategory row = categoryRepository.findByIdAndBusinessId(id, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Service category not found"));
        if (request.name() != null) {
            String name = request.name().trim();
            if (name.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name cannot be blank");
            }
            String key = nameKey(name);
            if (categoryRepository.existsByBusinessIdAndNameKeyAndIdNot(businessId, key, row.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A service category with this name already exists");
            }
            row.setName(name);
            row.setNameKey(key);
        }
        if (request.sortOrder() != null) {
            row.setSortOrder(request.sortOrder());
        }
        if (request.active() != null) {
            row.setActive(request.active());
        }
        saveCategory(row);
        return toResponse(row);
    }

    // ------------------------------------------------------------------ services

    @Transactional(readOnly = true)
    public Page<ServiceResponse> listServices(
            String businessId,
            String categoryId,
            Boolean active,
            String query,
            int page,
            int size
    ) {
        PageRequest pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                LIST_SORT);
        Page<ServiceOffering> rows = serviceRepository.search(
                businessId, blankToNull(categoryId), active, likePattern(query), pageable);
        Map<String, List<String>> staffByService = staffByService(
                businessId, rows.getContent().stream().map(ServiceOffering::getId).toList());
        return rows.map(row -> toResponse(row, staffByService.getOrDefault(row.getId(), List.of())));
    }

    @Transactional(readOnly = true)
    public ServiceResponse getService(String businessId, String id) {
        ServiceOffering row = requireLive(businessId, id);
        return toResponse(row, staffByService(businessId, List.of(id)).getOrDefault(id, List.of()));
    }

    @Transactional
    public ServiceResponse createService(String businessId, CreateServiceRequest request) {
        String name = request.name().trim();
        String key = nameKey(name);
        if (serviceRepository.existsByBusinessIdAndNameKey(businessId, key)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A service with this name already exists");
        }
        ServiceOffering row = new ServiceOffering();
        row.setBusinessId(businessId);
        row.setName(name);
        row.setNameKey(key);
        row.setCategoryId(resolveCategoryId(businessId, request.categoryId()));
        row.setDescription(blankToNull(request.description()));
        row.setDurationMinutes(request.durationMinutes());
        row.setPrice(money(request.price()));
        row.setPriceIsVariable(Boolean.TRUE.equals(request.priceIsVariable()));
        applyCommission(row, request.commissionType(), request.commissionValue());
        row.setSortOrder(request.sortOrder() != null ? request.sortOrder() : 0);
        row.setActive(true);
        List<String> staffIds = validatedStaffIds(businessId, request.staffProfileIds());
        saveService(row);
        saveStaffLinks(businessId, row.getId(), staffIds);
        return toResponse(row, staffIds);
    }

    @Transactional
    public ServiceResponse updateService(String businessId, String id, PatchServiceRequest request) {
        ServiceOffering row = requireLive(businessId, id);
        if (request.version() != null && request.version() != row.getVersion()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "This service was changed by someone else; reload and try again");
        }
        if (request.name() != null) {
            String name = request.name().trim();
            if (name.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name cannot be blank");
            }
            String key = nameKey(name);
            if (serviceRepository.existsByBusinessIdAndNameKeyAndIdNot(businessId, key, row.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A service with this name already exists");
            }
            row.setName(name);
            row.setNameKey(key);
        }
        if (Boolean.TRUE.equals(request.clearCategory())) {
            row.setCategoryId(null);
        } else if (request.categoryId() != null) {
            row.setCategoryId(resolveCategoryId(businessId, request.categoryId()));
        }
        if (Boolean.TRUE.equals(request.clearDescription())) {
            row.setDescription(null);
        } else if (request.description() != null) {
            row.setDescription(blankToNull(request.description()));
        }
        if (request.durationMinutes() != null) {
            row.setDurationMinutes(request.durationMinutes());
        }
        if (request.price() != null) {
            row.setPrice(money(request.price()));
        }
        if (request.priceIsVariable() != null) {
            row.setPriceIsVariable(request.priceIsVariable());
        }
        boolean commissionSent = request.commissionType() != null || request.commissionValue() != null;
        if (Boolean.TRUE.equals(request.clearCommission())) {
            if (commissionSent) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Send either clearCommission or a commission, not both");
            }
            row.setCommissionType(null);
            row.setCommissionValue(null);
        } else if (commissionSent) {
            // Type and value always travel together: a lone type would silently reinterpret the
            // stored number (40 % becoming KES 40).
            applyCommission(row, request.commissionType(), request.commissionValue());
        }
        if (request.sortOrder() != null) {
            row.setSortOrder(request.sortOrder());
        }
        if (request.active() != null) {
            row.setActive(request.active());
        }
        saveService(row);
        return toResponse(row, staffByService(businessId, List.of(id)).getOrDefault(id, List.of()));
    }

    /** Replaces who may perform the service. An empty list means anyone. */
    @Transactional
    public ServiceResponse setServiceStaff(String businessId, String id, List<String> staffProfileIds) {
        ServiceOffering row = requireLive(businessId, id);
        List<String> staffIds = validatedStaffIds(businessId, staffProfileIds);
        // Bump the service's version first. The row update serialises two people editing the same
        // staff list (the second gets a 409 instead of a deadlock or a duplicate-key error) and
        // lets a PATCH carrying a version notice that the staff list changed.
        row.setUpdatedAt(Instant.now());
        serviceRepository.saveAndFlush(row);
        long version = row.getVersion();
        staffLinkRepository.deleteByBusinessIdAndServiceId(businessId, id);
        saveStaffLinks(businessId, id, staffIds);
        return toResponse(row, staffIds, version);
    }

    /**
     * Soft delete. The name is released for reuse; past sales keep their own snapshot of the
     * service name, so nothing historical changes.
     */
    @Transactional
    public void deleteService(String businessId, String id) {
        ServiceOffering row = requireLive(businessId, id);
        row.setDeletedAt(Instant.now());
        row.setNameKey(null);
        row.setActive(false);
        serviceRepository.saveAndFlush(row);
        staffLinkRepository.deleteByBusinessIdAndServiceId(businessId, id);
    }

    /**
     * For the sale path: the service must belong to the business, not be deleted and be active.
     * Anything else is refused, so a removed or switched-off service cannot be rung on a new sale.
     */
    @Transactional(readOnly = true)
    public ServiceOffering requireSellable(String businessId, String serviceId) {
        ServiceOffering row = serviceRepository.findByIdAndBusinessIdAndDeletedAtIsNull(serviceId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Service not found"));
        if (!row.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This service is switched off");
        }
        return row;
    }

    /** Planned minutes by service id, for the given ids of this business (deleted ones included). */
    @Transactional(readOnly = true)
    public Map<String, Integer> durationsByIds(String businessId, java.util.Collection<String> serviceIds) {
        Map<String, Integer> out = new HashMap<>();
        if (serviceIds == null || serviceIds.isEmpty()) {
            return out;
        }
        for (ServiceOffering service : serviceRepository.findAllById(serviceIds)) {
            if (businessId.equals(service.getBusinessId())) {
                out.put(service.getId(), service.getDurationMinutes());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------- helpers

    /** Two creates racing past the name check land on the unique key; report that as the 409 it is. */
    private void saveService(ServiceOffering row) {
        try {
            serviceRepository.saveAndFlush(row);
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A service with this name already exists");
        }
    }

    private void saveCategory(ServiceCategory row) {
        try {
            categoryRepository.saveAndFlush(row);
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "A service category with this name already exists");
        }
    }

    private ServiceOffering requireLive(String businessId, String id) {
        return serviceRepository.findByIdAndBusinessIdAndDeletedAtIsNull(id, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found"));
    }

    private String resolveCategoryId(String businessId, String rawCategoryId) {
        String categoryId = blankToNull(rawCategoryId);
        if (categoryId == null) {
            return null;
        }
        return categoryRepository.findByIdAndBusinessId(categoryId, businessId)
                .map(ServiceCategory::getId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Service category not found"));
    }

    private static void applyCommission(ServiceOffering row, String rawType, BigDecimal value) {
        String type = blankToNull(rawType);
        if (type == null && value == null) {
            row.setCommissionType(null);
            row.setCommissionValue(null);
            return;
        }
        if (type == null || value == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Commission needs both a type and a value");
        }
        type = type.toUpperCase(Locale.ROOT);
        if (!ServiceCommissionTypes.isKnown(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Commission type must be PERCENT or FIXED");
        }
        if (value.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Commission cannot be negative");
        }
        if (ServiceCommissionTypes.PERCENT.equals(type) && value.compareTo(HUNDRED) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Commission percent cannot be more than 100");
        }
        row.setCommissionType(type);
        row.setCommissionValue(money(value));
    }

    /** De-duplicated ids, each checked to be a current (not terminated) staff profile of this business. */
    private List<String> validatedStaffIds(String businessId, List<String> rawIds) {
        if (rawIds == null || rawIds.isEmpty()) {
            return List.of();
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (String raw : rawIds) {
            String id = blankToNull(raw);
            if (id != null) {
                wanted.add(id);
            }
        }
        if (wanted.isEmpty()) {
            return List.of();
        }
        Set<String> known = staffProfileService.findCurrentProfileIds(businessId);
        if (!known.containsAll(wanted)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Staff member not found");
        }
        return List.copyOf(wanted);
    }

    private void saveStaffLinks(String businessId, String serviceId, List<String> staffIds) {
        if (staffIds.isEmpty()) {
            return;
        }
        List<ServiceStaffLink> links = new ArrayList<>(staffIds.size());
        for (String staffId : staffIds) {
            ServiceStaffLink link = new ServiceStaffLink();
            link.setId(new ServiceStaffLink.Id(serviceId, staffId));
            link.setBusinessId(businessId);
            links.add(link);
        }
        staffLinkRepository.saveAll(links);
    }

    private Map<String, List<String>> staffByService(String businessId, List<String> serviceIds) {
        if (serviceIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> out = new HashMap<>();
        for (ServiceStaffLink link : staffLinkRepository.findByBusinessIdAndServiceIdIn(businessId, serviceIds)) {
            out.computeIfAbsent(link.getId().getServiceId(), k -> new ArrayList<>())
                    .add(link.getId().getStaffProfileId());
        }
        out.values().forEach(list -> list.sort(String::compareTo));
        return out;
    }

    static String nameKey(String trimmedName) {
        return trimmedName.toLowerCase(Locale.ROOT);
    }

    /** "contains" pattern for {@code like … escape '!'}; {@code null} when there is no query. */
    static String likePattern(String query) {
        String q = blankToNull(query);
        if (q == null) {
            return null;
        }
        String escaped = q.toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ServiceCategoryResponse toResponse(ServiceCategory row) {
        return new ServiceCategoryResponse(row.getId(), row.getName(), row.getSortOrder(), row.isActive());
    }

    private static ServiceResponse toResponse(ServiceOffering row, List<String> staffProfileIds) {
        return toResponse(row, staffProfileIds, row.getVersion());
    }

    private static ServiceResponse toResponse(ServiceOffering row, List<String> staffProfileIds, long version) {
        return new ServiceResponse(
                row.getId(),
                row.getCategoryId(),
                row.getName(),
                row.getDescription(),
                row.getDurationMinutes(),
                row.getPrice(),
                row.isPriceIsVariable(),
                row.getCommissionType(),
                row.getCommissionValue(),
                row.getSortOrder(),
                row.isActive(),
                staffProfileIds,
                version
        );
    }
}
