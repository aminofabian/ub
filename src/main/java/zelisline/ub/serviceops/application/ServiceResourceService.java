package zelisline.ub.serviceops.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.posdraft.domain.PosDraftLine;
import zelisline.ub.posdraft.repository.PosDraftLineRepository;
import zelisline.ub.serviceops.api.dto.ResourceDtos.CreateResourceRequest;
import zelisline.ub.serviceops.api.dto.ResourceDtos.ResourceResponse;
import zelisline.ub.serviceops.api.dto.ResourceDtos.UpdateResourceRequest;
import zelisline.ub.serviceops.domain.ServiceOffering;
import zelisline.ub.serviceops.domain.ServiceResource;
import zelisline.ub.serviceops.repository.ServiceOfferingRepository;
import zelisline.ub.serviceops.repository.ServiceResourceRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.repository.BranchRepository;

/**
 * Chairs, beds, rooms and machines (ADR 0016). A resource is used by one service at a time.
 * People and resources are separate constraints: a free barber does not mean a free chair.
 *
 * <p>A branch that only ever said "three chairs" gets three resources named Chair 1..3 the first
 * time its resources are read, so shops set up before this change keep working.</p>
 */
@Service
@RequiredArgsConstructor
public class ServiceResourceService {

    public static final String TYPE_CHAIR = "CHAIR";
    /** A restaurant table. Never used by a service, never given a chair number. */
    public static final String TYPE_TABLE = "TABLE";
    private static final int MAX_RESOURCES = 200;

    private final ServiceResourceRepository resourceRepository;
    private final BranchRepository branchRepository;
    private final PosDraftLineRepository lineRepository;
    private final ServiceOfferingRepository serviceRepository;

    /** Resources of a branch with what each is doing now. Creates the numbered chairs if they are missing. */
    @Transactional
    public List<ResourceResponse> list(String businessId, String branchId) {
        // A restaurant's tables live in the same table but are not places a service is done:
        // a shop with both a salon floor and a dining room must not see tables among its chairs.
        List<ServiceResource> rows = ensureForStations(businessId, branchId).stream()
                .filter(r -> !TYPE_TABLE.equals(r.getResourceType()))
                .toList();
        Map<String, String> busy = new HashMap<>();
        for (PosDraftLine line : lineRepository.findInServiceAtBranch(businessId, branchId.trim())) {
            if (line.getResourceId() != null) {
                busy.put(line.getResourceId(), line.getDraftId());
            }
        }
        Instant now = Instant.now();
        List<ResourceResponse> out = new ArrayList<>(rows.size());
        for (ServiceResource row : rows) {
            String ticket = busy.get(row.getId());
            Instant freeAt = null;
            if (ticket == null) {
                Instant until = unavailableUntil(businessId, row, now);
                freeAt = until != null && until.isAfter(now) ? until : null;
            }
            out.add(toResponse(row, ticket, freeAt));
        }
        return out;
    }

    /** Makes sure Chair 1..N exist for the branch's chair count. Never removes anything. */
    @Transactional
    public List<ServiceResource> ensureForStations(String businessId, String branchId) {
        // Locked so two screens opening at once do not both create the chairs.
        Branch branch = branchRepository.findByIdAndBusinessIdForUpdate(branchId.trim(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found"));
        List<ServiceResource> rows =
                resourceRepository.findByBusinessIdAndBranchIdOrderBySortOrderAscNameAsc(businessId, branch.getId());
        boolean added = false;
        for (int n = 1; n <= branch.getServiceStationCount(); n++) {
            final int number = n;
            if (rows.stream().noneMatch(r -> r.getStationNo() != null && r.getStationNo() == number)) {
                ServiceResource chair = new ServiceResource();
                chair.setBusinessId(businessId);
                chair.setBranchId(branch.getId());
                chair.setName(freeName(rows, "Chair " + n));
                chair.setResourceType(TYPE_CHAIR);
                chair.setStationNo(n);
                chair.setSortOrder(n);
                resourceRepository.save(chair);
                added = true;
            }
        }
        if (added) {
            resourceRepository.flush();
            rows = resourceRepository.findByBusinessIdAndBranchIdOrderBySortOrderAscNameAsc(businessId, branch.getId());
        }
        return rows;
    }

    @Transactional
    public ResourceResponse create(String businessId, CreateResourceRequest request) {
        List<ServiceResource> rows = ensureForStations(businessId, request.branchId());
        if (TYPE_TABLE.equals(request.resourceType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Add tables from the restaurant floor");
        }
        if (rows.stream().filter(r -> !TYPE_TABLE.equals(r.getResourceType())).count() >= MAX_RESOURCES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This branch already has " + MAX_RESOURCES + " resources");
        }
        ServiceResource row = new ServiceResource();
        row.setBusinessId(businessId);
        row.setBranchId(request.branchId().trim());
        row.setName(request.name().trim());
        row.setResourceType(request.resourceType());
        row.setCleanupMinutes(request.cleanupMinutes() == null ? 0 : request.cleanupMinutes());
        row.setSortOrder(rows.stream().mapToInt(ServiceResource::getSortOrder).max().orElse(0) + 1);
        saveUnique(row);
        return toResponse(row, null, null);
    }

    @Transactional
    public ResourceResponse update(String businessId, String resourceId, UpdateResourceRequest request) {
        ServiceResource row = resourceRepository.findByIdAndBusinessIdForUpdate(resourceId, businessId)
                .filter(r -> !TYPE_TABLE.equals(r.getResourceType()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found"));
        if (TYPE_TABLE.equals(request.resourceType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Add tables from the restaurant floor");
        }
        boolean takingOutOfUse = Boolean.FALSE.equals(request.active())
                || (request.blockedUntil() != null && request.blockedUntil().isAfter(Instant.now()));
        if (takingOutOfUse && !lineRepository.findInServiceByResource(businessId, row.getId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    row.getName() + " is in use; finish the service on it first");
        }
        if (request.name() != null && !request.name().isBlank()) {
            row.setName(request.name().trim());
        }
        if (request.resourceType() != null) {
            row.setResourceType(request.resourceType());
        }
        if (request.active() != null) {
            row.setActive(request.active());
        }
        if (request.cleanupMinutes() != null) {
            row.setCleanupMinutes(request.cleanupMinutes());
        }
        if (Boolean.TRUE.equals(request.clearBlock())) {
            row.setBlockedUntil(null);
            row.setBlockedReason(null);
        } else if (request.blockedUntil() != null) {
            row.setBlockedUntil(request.blockedUntil());
            row.setBlockedReason(request.blockedReason() == null || request.blockedReason().isBlank()
                    ? null : request.blockedReason().trim());
        }
        saveUnique(row);
        return toResponse(row, null, null);
    }

    /** Says which kind of resource a service needs; blank clears it. */
    @Transactional
    public void setServiceResourceType(String businessId, String serviceId, String resourceType) {
        ServiceOffering service = serviceRepository.findByIdAndBusinessIdAndDeletedAtIsNull(serviceId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found"));
        service.setResourceType(resourceType == null || resourceType.isBlank() ? null : resourceType);
        serviceRepository.save(service);
    }

    /**
     * Takes a resource for a service that is starting now, or refuses. Locks the resource row
     * first; the busy check is a locking read, so two tills cannot both get the same chair.
     *
     * @param forLineId the line being started, ignored in the busy check (a double tap)
     */
    @Transactional
    public ServiceResource claim(String businessId, String branchId, String resourceId, String forLineId, String serviceId) {
        ServiceResource row = resourceRepository.findByIdAndBusinessIdForUpdate(resourceId, businessId)
                .filter(r -> !TYPE_TABLE.equals(r.getResourceType()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Resource not found"));
        if (!row.getBranchId().equals(branchId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, row.getName() + " is at another branch");
        }
        if (!row.isActive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, row.getName() + " is not in use");
        }
        String needs = serviceId == null ? null : serviceRepository.findById(serviceId)
                .filter(s -> businessId.equals(s.getBusinessId()))
                .map(ServiceOffering::getResourceType)
                .orElse(null);
        if (needs != null && !needs.equals(row.getResourceType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This service needs a " + needs.toLowerCase(java.util.Locale.ROOT).replace('_', ' ')
                            + ", and " + row.getName() + " is not one");
        }
        for (PosDraftLine other : lineRepository.findInServiceByResource(businessId, row.getId())) {
            if (!other.getId().equals(forLineId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, row.getName() + " is taken");
            }
        }
        Instant now = Instant.now();
        Instant until = unavailableUntil(businessId, row, now);
        if (until != null && until.isAfter(now)) {
            boolean blocked = row.getBlockedUntil() != null && row.getBlockedUntil().isAfter(now);
            throw new ResponseStatusException(HttpStatus.CONFLICT, row.getName()
                    + (blocked ? " is out of use" : " is being prepared; free in about "
                            + Math.max(1, ChronoUnit.MINUTES.between(now, until)) + " min"));
        }
        return row;
    }

    @Transactional(readOnly = true)
    public Map<String, String> namesByIds(String businessId, java.util.Collection<String> ids) {
        Map<String, String> out = new HashMap<>();
        if (ids == null || ids.isEmpty()) {
            return out;
        }
        for (ServiceResource row : resourceRepository.findAllById(ids)) {
            if (businessId.equals(row.getBusinessId())) {
                out.put(row.getId(), row.getName());
            }
        }
        return out;
    }

    /** The later of its maintenance block and the end of its cleaning time; null when neither applies. */
    private Instant unavailableUntil(String businessId, ServiceResource row, Instant now) {
        Instant until = row.getBlockedUntil() != null && row.getBlockedUntil().isAfter(now) ? row.getBlockedUntil() : null;
        if (row.getCleanupMinutes() > 0) {
            Instant last = lineRepository.lastFinishedOnResource(businessId, row.getId());
            if (last != null) {
                Instant clean = last.plus(row.getCleanupMinutes(), ChronoUnit.MINUTES);
                if (clean.isAfter(now) && (until == null || clean.isAfter(until))) {
                    until = clean;
                }
            }
        }
        return until;
    }

    // ------------------------------------------------------------------ restaurant tables (ADR 0020)

    /** A table of a restaurant floor. Stored as a resource of type {@code TABLE}; occupancy is the order's business. */
    public record TableRecord(
            String id, String branchId, String name, Integer capacity, String area, boolean active, int sortOrder,
            String qrToken, String businessId) {
    }

    /**
     * Gives a table a new QR code, replacing any old one (which stops working), or with
     * {@code enabled} false takes its code away.
     */
    @Transactional
    public TableRecord setTableQr(String businessId, String tableId, boolean enabled) {
        ServiceResource row = resourceRepository.findByIdAndBusinessIdForUpdate(tableId, businessId)
                .filter(r -> TYPE_TABLE.equals(r.getResourceType()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Table not found"));
        if (enabled) {
            byte[] bytes = new byte[24];
            new java.security.SecureRandom().nextBytes(bytes);
            row.setQrToken(java.util.HexFormat.of().formatHex(bytes));
        } else {
            row.setQrToken(null);
        }
        resourceRepository.saveAndFlush(row);
        return toTable(row);
    }

    /** The table a QR code belongs to, whichever business it is in. The caller checks the business. */
    @Transactional(readOnly = true)
    public java.util.Optional<TableRecord> tableByQrToken(String token) {
        if (token == null || token.length() < 32 || token.length() > 64) {
            return java.util.Optional.empty();
        }
        return resourceRepository.findByQrToken(token)
                .filter(r -> TYPE_TABLE.equals(r.getResourceType()) && r.isActive())
                .map(ServiceResourceService::toTable);
    }

    /** How many tables a business has in use, at all its branches. */
    @Transactional(readOnly = true)
    public long countTables(String businessId) {
        return resourceRepository.countByBusinessIdAndResourceTypeAndActiveTrue(businessId, TYPE_TABLE);
    }

    /** The tables of a branch. Unlike {@link #list}, this never creates the numbered chairs. */
    @Transactional(readOnly = true)
    public List<TableRecord> tables(String businessId, String branchId) {
        return resourceRepository
                .findByBusinessIdAndBranchIdAndResourceTypeOrderBySortOrderAscNameAsc(businessId, branchId.trim(), TYPE_TABLE)
                .stream().map(ServiceResourceService::toTable).toList();
    }

    @Transactional(readOnly = true)
    public java.util.Optional<TableRecord> table(String businessId, String tableId) {
        return resourceRepository.findByIdAndBusinessId(tableId, businessId)
                .filter(r -> TYPE_TABLE.equals(r.getResourceType()))
                .map(ServiceResourceService::toTable);
    }

    @Transactional
    public TableRecord createTable(String businessId, String branchId, String name, Integer capacity, String area) {
        List<ServiceResource> rows = resourceRepository
                .findByBusinessIdAndBranchIdAndResourceTypeOrderBySortOrderAscNameAsc(businessId, branchId.trim(), TYPE_TABLE);
        if (rows.size() >= MAX_RESOURCES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This branch already has " + MAX_RESOURCES + " tables");
        }
        ServiceResource row = new ServiceResource();
        row.setBusinessId(businessId);
        row.setBranchId(branchId.trim());
        row.setName(name.trim());
        row.setResourceType(TYPE_TABLE);
        row.setCapacity(capacity);
        row.setArea(area == null || area.isBlank() ? null : area.trim());
        row.setSortOrder(rows.stream().mapToInt(ServiceResource::getSortOrder).max().orElse(0) + 1);
        saveUnique(row);
        return toTable(row);
    }

    /** Null leaves a field as it is; a blank area clears it. */
    @Transactional
    public TableRecord updateTable(
            String businessId, String tableId, String name, Integer capacity, String area, Boolean active) {
        ServiceResource row = resourceRepository.findByIdAndBusinessIdForUpdate(tableId, businessId)
                .filter(r -> TYPE_TABLE.equals(r.getResourceType()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Table not found"));
        if (name != null && !name.isBlank()) {
            row.setName(name.trim());
        }
        if (capacity != null) {
            row.setCapacity(capacity <= 0 ? null : capacity);
        }
        if (area != null) {
            row.setArea(area.isBlank() ? null : area.trim());
        }
        if (active != null) {
            row.setActive(active);
        }
        saveUnique(row);
        return toTable(row);
    }

    private static TableRecord toTable(ServiceResource row) {
        return new TableRecord(row.getId(), row.getBranchId(), row.getName(), row.getCapacity(), row.getArea(),
                row.isActive(), row.getSortOrder(), row.getQrToken(), row.getBusinessId());
    }

    private void saveUnique(ServiceResource row) {
        try {
            resourceRepository.saveAndFlush(row);
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "There is already a resource called " + row.getName());
        }
    }

    private static String freeName(List<ServiceResource> rows, String wanted) {
        String name = wanted;
        int n = 2;
        while (true) {
            final String candidate = name;
            if (rows.stream().noneMatch(r -> r.getName().equalsIgnoreCase(candidate))) {
                return name;
            }
            name = wanted + " (" + n++ + ")";
        }
    }

    private static ResourceResponse toResponse(ServiceResource row, String busyTicketId, Instant freeAt) {
        return new ResourceResponse(row.getId(), row.getBranchId(), row.getName(), row.getResourceType(),
                row.getStationNo(), row.isActive(), row.getCleanupMinutes(), row.getBlockedUntil(),
                row.getBlockedReason(), busyTicketId, freeAt);
    }
}
