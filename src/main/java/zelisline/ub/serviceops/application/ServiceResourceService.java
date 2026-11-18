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
import zelisline.ub.serviceops.domain.RoomType;
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
    /** A lodging room. Booked for a stay; carries a nightly rate. Never used by a service. */
    public static final String TYPE_ROOM = "ROOM";
    private static final int MAX_RESOURCES = 200;

    // Housekeeping states of a room (ADR 0030), separate from occupancy and from `active`.
    /** Turned round and ready to let. A room with no stored status reads as CLEAN. */
    public static final String HOUSEKEEPING_CLEAN = "CLEAN";
    /** Just vacated (or marked by staff); needs turning round before the next guest. */
    public static final String HOUSEKEEPING_DIRTY = "DIRTY";
    /** Being turned round now. */
    public static final String HOUSEKEEPING_CLEANING = "CLEANING";
    /** Cleaned and checked; ready to let, for properties that inspect. */
    public static final String HOUSEKEEPING_INSPECTED = "INSPECTED";
    /** Out for repair; not bookable until cleared, whatever its occupancy. */
    public static final String HOUSEKEEPING_MAINTENANCE = "MAINTENANCE";

    private static final java.util.Set<String> HOUSEKEEPING_STATES = java.util.Set.of(
            HOUSEKEEPING_CLEAN, HOUSEKEEPING_DIRTY, HOUSEKEEPING_CLEANING, HOUSEKEEPING_INSPECTED,
            HOUSEKEEPING_MAINTENANCE);
    /** The states in which a room may take a new stay; the rest hold it back (ADR 0030). */
    private static final java.util.Set<String> HOUSEKEEPING_BOOKABLE = java.util.Set.of(
            HOUSEKEEPING_CLEAN, HOUSEKEEPING_INSPECTED);

    private final ServiceResourceRepository resourceRepository;
    private final zelisline.ub.serviceops.repository.RoomTypeRepository roomTypeRepository;
    private final BranchRepository branchRepository;
    private final PosDraftLineRepository lineRepository;
    private final ServiceOfferingRepository serviceRepository;

    /** Resources of a branch with what each is doing now. Creates the numbered chairs if they are missing. */
    @Transactional
    public List<ResourceResponse> list(String businessId, String branchId) {
        // A restaurant's tables and a guest house's rooms live in the same table but are not places
        // a service is done: a shop with a salon floor, a dining room and rooms upstairs must not see
        // any of those among its chairs.
        List<ServiceResource> rows = ensureForStations(businessId, branchId).stream()
                .filter(r -> !TYPE_TABLE.equals(r.getResourceType()) && !TYPE_ROOM.equals(r.getResourceType()))
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
        if (TYPE_ROOM.equals(request.resourceType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Add rooms from the front desk");
        }
        if (rows.stream().filter(r -> !TYPE_TABLE.equals(r.getResourceType()) && !TYPE_ROOM.equals(r.getResourceType()))
                .count() >= MAX_RESOURCES) {
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
                .filter(r -> !TYPE_TABLE.equals(r.getResourceType()) && !TYPE_ROOM.equals(r.getResourceType()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found"));
        if (TYPE_TABLE.equals(request.resourceType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Add tables from the restaurant floor");
        }
        if (TYPE_ROOM.equals(request.resourceType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Add rooms from the front desk");
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
                .filter(r -> !TYPE_TABLE.equals(r.getResourceType()) && !TYPE_ROOM.equals(r.getResourceType()))
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
            String qrToken, String businessId, String assignedStaffProfileId) {
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

    /**
     * Names the waiter who looks after a table, or with null takes them off it. The caller checks
     * that the staff profile is one of this business's.
     */
    @Transactional
    public TableRecord assignTableWaiter(String businessId, String tableId, String staffProfileId) {
        ServiceResource row = resourceRepository.findByIdAndBusinessIdForUpdate(tableId, businessId)
                .filter(r -> TYPE_TABLE.equals(r.getResourceType()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Table not found"));
        row.setAssignedStaffProfileId(staffProfileId == null || staffProfileId.isBlank() ? null : staffProfileId.trim());
        saveUnique(row);
        return toTable(row);
    }

    private static TableRecord toTable(ServiceResource row) {
        return new TableRecord(row.getId(), row.getBranchId(), row.getName(), row.getCapacity(), row.getArea(),
                row.isActive(), row.getSortOrder(), row.getQrToken(), row.getBusinessId(),
                row.getAssignedStaffProfileId());
    }

    // ------------------------------------------------------------------ lodging rooms (ADR 0028)

    /**
     * A room a guest house lets. Stored as a resource of type {@code ROOM}; a stay's occupancy is the
     * booking order's business, like a table. {@code area} is the floor or wing it is on.
     */
    public record RoomRecord(
            String id, String branchId, String name, java.math.BigDecimal nightlyRate, String area,
            boolean active, int sortOrder, String businessId, String housekeepingStatus,
            boolean readyToLet, String roomTypeId,
            /** The rate a guest is actually charged a weekday night: the type's, or the room's own. */
            java.math.BigDecimal effectiveNightlyRate,
            /** What a weekend night costs, when the type sets one; else the same as the weekday rate. */
            java.math.BigDecimal effectiveWeekendRate,
            /** A short housekeeping note (damage, missing amenity), or null. */
            String housekeepingNote,
            /** The staff member responsible for cleaning this room now, or null. */
            String cleanerStaffProfileId) {
    }

    /** A room with no stored housekeeping status reads as CLEAN (ADR 0030). */
    public static String housekeepingOf(ServiceResource row) {
        String stored = row.getHousekeepingStatus();
        return stored == null || stored.isBlank() ? HOUSEKEEPING_CLEAN : stored;
    }

    /**
     * Whether a room may take a new stay on its housekeeping and active axes alone (dates are a
     * separate, order-level check). A room out of use, or not yet turned round, is held back.
     */
    public static boolean roomReadyToLet(ServiceResource row) {
        return row.isActive() && HOUSEKEEPING_BOOKABLE.contains(housekeepingOf(row));
    }

    /** The rooms of a branch. Like {@link #tables}, never creates the numbered chairs. */
    @Transactional(readOnly = true)
    public List<RoomRecord> rooms(String businessId, String branchId) {
        List<ServiceResource> rows = resourceRepository
                .findByBusinessIdAndBranchIdAndResourceTypeOrderBySortOrderAscNameAsc(businessId, branchId.trim(), TYPE_ROOM);
        // Load the branch's types once, so each room's effective rate is resolved without an N+1.
        java.util.Map<String, RoomType> types = new HashMap<>();
        for (RoomType t : roomTypeRepository.findByBusinessIdAndBranchIdOrderBySortOrderAscNameAsc(businessId, branchId.trim())) {
            types.put(t.getId(), t);
        }
        return rows.stream().map(r -> toRoom(r, r.getRoomTypeId() == null ? null : types.get(r.getRoomTypeId()))).toList();
    }

    @Transactional(readOnly = true)
    public java.util.Optional<RoomRecord> room(String businessId, String roomId) {
        return resourceRepository.findByIdAndBusinessId(roomId, businessId)
                .filter(r -> TYPE_ROOM.equals(r.getResourceType()))
                .map(r -> toRoom(r, r.getRoomTypeId() == null ? null
                        : roomTypeRepository.findByIdAndBusinessId(r.getRoomTypeId(), businessId).orElse(null)));
    }

    @Transactional
    public RoomRecord createRoom(
            String businessId, String branchId, String name, java.math.BigDecimal nightlyRate, String area) {
        List<ServiceResource> rows = resourceRepository
                .findByBusinessIdAndBranchIdAndResourceTypeOrderBySortOrderAscNameAsc(businessId, branchId.trim(), TYPE_ROOM);
        if (rows.size() >= MAX_RESOURCES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This branch already has " + MAX_RESOURCES + " rooms");
        }
        ServiceResource row = new ServiceResource();
        row.setBusinessId(businessId);
        row.setBranchId(branchId.trim());
        row.setName(name.trim());
        row.setResourceType(TYPE_ROOM);
        row.setNightlyRate(requireRate(nightlyRate));
        row.setHousekeepingStatus(HOUSEKEEPING_CLEAN);
        row.setArea(area == null || area.isBlank() ? null : area.trim());
        row.setSortOrder(rows.stream().mapToInt(ServiceResource::getSortOrder).max().orElse(0) + 1);
        saveUnique(row);
        return toRoomResolved(row);
    }

    /** Null leaves a field as it is; a blank area clears it. */
    @Transactional
    public RoomRecord updateRoom(
            String businessId, String roomId, String name, java.math.BigDecimal nightlyRate, String area, Boolean active) {
        ServiceResource row = resourceRepository.findByIdAndBusinessIdForUpdate(roomId, businessId)
                .filter(r -> TYPE_ROOM.equals(r.getResourceType()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
        if (name != null && !name.isBlank()) {
            row.setName(name.trim());
        }
        if (nightlyRate != null) {
            row.setNightlyRate(requireRate(nightlyRate));
        }
        if (area != null) {
            row.setArea(area.isBlank() ? null : area.trim());
        }
        if (active != null) {
            row.setActive(active);
        }
        saveUnique(row);
        return toRoomResolved(row);
    }

    /** Assigns a room to a type (its rate card), or clears it with a null/blank type id (ADR 0030). */
    @Transactional
    public RoomRecord setRoomType(String businessId, String roomId, String roomTypeId) {
        ServiceResource row = resourceRepository.findByIdAndBusinessIdForUpdate(roomId, businessId)
                .filter(r -> TYPE_ROOM.equals(r.getResourceType()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
        String typeId = roomTypeId == null || roomTypeId.isBlank() ? null : roomTypeId.trim();
        if (typeId != null) {
            RoomType type = roomTypeRepository.findByIdAndBusinessId(typeId, businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Room type not found"));
            if (!type.getBranchId().equals(row.getBranchId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That room type is at another branch");
            }
        }
        row.setRoomTypeId(typeId);
        resourceRepository.save(row);
        return toRoomResolved(row);
    }

    // ------------------------------------------------------------------ room types (rate cards)

    public record RoomTypeRecord(
            String id, String branchId, String name, java.math.BigDecimal defaultNightlyRate,
            java.math.BigDecimal weekendNightlyRate, Integer capacity, String bedConfig, String amenities,
            int sortOrder) {
    }

    @Transactional(readOnly = true)
    public java.util.Optional<RoomTypeRecord> roomType(String businessId, String typeId) {
        return roomTypeRepository.findByIdAndBusinessId(typeId, businessId).map(ServiceResourceService::toRoomType);
    }

    @Transactional(readOnly = true)
    public List<RoomTypeRecord> roomTypes(String businessId, String branchId) {
        return roomTypeRepository.findByBusinessIdAndBranchIdOrderBySortOrderAscNameAsc(businessId, branchId.trim())
                .stream().map(ServiceResourceService::toRoomType).toList();
    }

    @Transactional
    public RoomTypeRecord createRoomType(String businessId, String branchId, String name,
            java.math.BigDecimal defaultRate, java.math.BigDecimal weekendRate, Integer capacity,
            String bedConfig, String amenities) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the room type a name");
        }
        if (roomTypeRepository.countByBusinessIdAndBranchId(businessId, branchId.trim()) >= MAX_RESOURCES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This branch already has too many room types");
        }
        RoomType type = new RoomType();
        type.setBusinessId(businessId);
        type.setBranchId(branchId.trim());
        type.setName(name.trim());
        type.setDefaultNightlyRate(requireRate(defaultRate));
        type.setWeekendNightlyRate(weekendRate == null ? null : requireRate(weekendRate));
        type.setCapacity(capacity == null || capacity <= 0 ? null : capacity);
        type.setBedConfig(blankToNull(bedConfig));
        type.setAmenities(blankToNull(amenities));
        type.setSortOrder((int) roomTypeRepository.countByBusinessIdAndBranchId(businessId, branchId.trim()) + 1);
        saveUniqueType(type);
        return toRoomType(type);
    }

    /** Null leaves a field unchanged; a weekend rate of 0 clears it (weekends cost the weekday rate). */
    @Transactional
    public RoomTypeRecord updateRoomType(String businessId, String typeId, String name,
            java.math.BigDecimal defaultRate, java.math.BigDecimal weekendRate, Integer capacity,
            String bedConfig, String amenities) {
        RoomType type = roomTypeRepository.findByIdAndBusinessId(typeId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room type not found"));
        if (name != null && !name.isBlank()) {
            type.setName(name.trim());
        }
        if (defaultRate != null) {
            type.setDefaultNightlyRate(requireRate(defaultRate));
        }
        if (weekendRate != null) {
            type.setWeekendNightlyRate(weekendRate.signum() == 0 ? null : requireRate(weekendRate));
        }
        if (capacity != null) {
            type.setCapacity(capacity <= 0 ? null : capacity);
        }
        if (bedConfig != null) {
            type.setBedConfig(blankToNull(bedConfig));
        }
        if (amenities != null) {
            type.setAmenities(blankToNull(amenities));
        }
        saveUniqueType(type);
        return toRoomType(type);
    }

    private static RoomTypeRecord toRoomType(RoomType t) {
        return new RoomTypeRecord(t.getId(), t.getBranchId(), t.getName(), t.getDefaultNightlyRate(),
                t.getWeekendNightlyRate(), t.getCapacity(), t.getBedConfig(), t.getAmenities(), t.getSortOrder());
    }

    private void saveUniqueType(RoomType type) {
        try {
            roomTypeRepository.saveAndFlush(type);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A room type here already has that name");
        }
    }

    private static String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    // ------------------------------------------------------------------ rate resolution (ADR 0030)

    private static final java.time.ZoneId STAY_ZONE = java.time.ZoneId.of("Africa/Nairobi");

    /** One tier of a stay's room charge: a count of nights at one frozen rate, with a label suffix. */
    public record RoomNightTier(long nights, java.math.BigDecimal rate, boolean weekend) {
    }

    /**
     * The room-night charge for a stay, resolved and ready to freeze at check-in (ADR 0030). A room's
     * own {@code nightly_rate} is the rate unless it has a type, in which case the type's rate card
     * applies: a weekday night at the default rate, a weekend night (Friday or Saturday, Nairobi) at
     * the weekend rate when the type sets one. The result is one tier when every night is the same
     * rate, or two (weekday + weekend) when they differ — each a whole number of nights at one frozen
     * rate. The nights of the stay are the calendar dates slept (check-out morning excluded).
     */
    @Transactional(readOnly = true)
    public List<RoomNightTier> resolveRoomNights(String businessId, String roomId,
            java.time.Instant checkIn, java.time.Instant checkOut) {
        ServiceResource room = resourceRepository.findByIdAndBusinessId(roomId, businessId)
                .filter(r -> TYPE_ROOM.equals(r.getResourceType()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "This stay's room is gone"));
        RoomType type = room.getRoomTypeId() == null ? null
                : roomTypeRepository.findByIdAndBusinessId(room.getRoomTypeId(), businessId).orElse(null);
        // A room with a type takes its rate card; the type leads so a weekend rate can apply. A room
        // with no type falls back to its own nightly rate. (A per-room override of a typed room's rate
        // is a later refinement — today a type is authoritative for the rooms assigned to it.)
        java.math.BigDecimal weekdayRate = type != null ? type.getDefaultNightlyRate() : room.getNightlyRate();
        if (weekdayRate == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This room has no rate set");
        }
        java.math.BigDecimal weekendRate = (type != null && type.getWeekendNightlyRate() != null)
                ? type.getWeekendNightlyRate() : weekdayRate;

        java.time.LocalDate in = checkIn == null ? java.time.LocalDate.now(STAY_ZONE)
                : checkIn.atZone(STAY_ZONE).toLocalDate();
        java.time.LocalDate out = checkOut == null ? in.plusDays(1) : checkOut.atZone(STAY_ZONE).toLocalDate();
        long totalNights = Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(in, out));

        long weekendNights = 0;
        for (long i = 0; i < totalNights; i++) {
            java.time.DayOfWeek day = in.plusDays(i).getDayOfWeek();
            if (day == java.time.DayOfWeek.FRIDAY || day == java.time.DayOfWeek.SATURDAY) {
                weekendNights++;
            }
        }
        long weekdayNights = totalNights - weekendNights;

        // One tier when the rates are equal (no weekend rate, or none of the nights are weekends).
        if (weekdayRate.compareTo(weekendRate) == 0 || weekendNights == 0 || weekdayNights == 0) {
            java.math.BigDecimal rate = weekendNights == totalNights ? weekendRate : weekdayRate;
            return List.of(new RoomNightTier(totalNights, rate, weekendNights == totalNights));
        }
        return List.of(
                new RoomNightTier(weekdayNights, weekdayRate, false),
                new RoomNightTier(weekendNights, weekendRate, true));
    }

    /**
     * Sets a room's housekeeping state (ADR 0030) — the cleaning cycle, not occupancy and not the
     * owner's {@code active} switch. The caller passes one of the HOUSEKEEPING_* states.
     */
    @Transactional
    public RoomRecord setRoomHousekeeping(String businessId, String roomId, String status) {
        return setRoomHousekeeping(businessId, roomId, status, null, null, false, false);
    }

    /**
     * Sets a room's housekeeping state, and optionally who is cleaning it and a note about it (ADR
     * 0030). A null cleaner/note leaves it; {@code clearCleaner}/{@code clearNote} remove it. When a
     * room is turned CLEAN its note and cleaner are cleared, so a cleaned room starts fresh.
     */
    @Transactional
    public RoomRecord setRoomHousekeeping(String businessId, String roomId, String status,
            String cleanerStaffProfileId, String note, boolean clearCleaner, boolean clearNote) {
        String next = normalizeHousekeeping(status);
        ServiceResource row = resourceRepository.findByIdAndBusinessIdForUpdate(roomId, businessId)
                .filter(r -> TYPE_ROOM.equals(r.getResourceType()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
        row.setHousekeepingStatus(next);
        if (clearCleaner) {
            row.setAssignedStaffProfileId(null);
        } else if (cleanerStaffProfileId != null && !cleanerStaffProfileId.isBlank()) {
            row.setAssignedStaffProfileId(cleanerStaffProfileId.trim());
        }
        if (clearNote) {
            row.setHousekeepingNote(null);
        } else if (note != null && !note.isBlank()) {
            row.setHousekeepingNote(note.trim());
        }
        // A room turned round starts fresh: its note and the cleaner on it are cleared.
        if (HOUSEKEEPING_CLEAN.equals(next) || HOUSEKEEPING_INSPECTED.equals(next)) {
            row.setHousekeepingNote(null);
            row.setAssignedStaffProfileId(null);
        }
        resourceRepository.save(row);
        return toRoomResolved(row);
    }

    /**
     * Sends a room to DIRTY at check-out (ADR 0030): a settled stay does not free the room, it goes
     * to housekeeping. Lenient — does nothing if the room is gone or is already out for repair (a
     * maintenance flag outranks a routine turnaround). Internal; no permission of its own.
     */
    @Transactional
    public void markRoomDirtyOnCheckout(String businessId, String roomId) {
        if (roomId == null) {
            return;
        }
        resourceRepository.findByIdAndBusinessIdForUpdate(roomId, businessId)
                .filter(r -> TYPE_ROOM.equals(r.getResourceType()))
                .filter(r -> !HOUSEKEEPING_MAINTENANCE.equals(housekeepingOf(r)))
                .ifPresent(r -> {
                    r.setHousekeepingStatus(HOUSEKEEPING_DIRTY);
                    resourceRepository.save(r);
                });
    }

    /** Upper-cases and checks a housekeeping state; rejects anything outside the known set. */
    private static String normalizeHousekeeping(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a housekeeping state");
        }
        String status = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (!HOUSEKEEPING_STATES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That is not a housekeeping state");
        }
        return status;
    }

    /** A positive KES amount with at most two decimal places, under a million a night. */
    private static java.math.BigDecimal requireRate(java.math.BigDecimal rate) {
        if (rate == null || rate.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A room needs a nightly rate above zero");
        }
        if (rate.scale() > 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A nightly rate has at most two decimal places");
        }
        if (rate.compareTo(new java.math.BigDecimal("1000000")) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That nightly rate is too high");
        }
        return rate.setScale(2, java.math.RoundingMode.UNNECESSARY);
    }

    /** Maps a room row to its record, resolving the rate a guest actually pays from its type (ADR 0030). */
    private static RoomRecord toRoom(ServiceResource row, RoomType type) {
        // A typed room is charged from its type's rate card; an untyped room from its own nightly rate.
        java.math.BigDecimal weekday = type != null ? type.getDefaultNightlyRate() : row.getNightlyRate();
        java.math.BigDecimal weekend = (type != null && type.getWeekendNightlyRate() != null)
                ? type.getWeekendNightlyRate() : weekday;
        return new RoomRecord(row.getId(), row.getBranchId(), row.getName(), row.getNightlyRate(), row.getArea(),
                row.isActive(), row.getSortOrder(), row.getBusinessId(), housekeepingOf(row), roomReadyToLet(row),
                row.getRoomTypeId(), weekday, weekend, row.getHousekeepingNote(), row.getAssignedStaffProfileId());
    }

    /** {@link #toRoom} for a single row, looking its type up when it has one. */
    private RoomRecord toRoomResolved(ServiceResource row) {
        RoomType type = row.getRoomTypeId() == null ? null
                : roomTypeRepository.findByIdAndBusinessId(row.getRoomTypeId(), row.getBusinessId()).orElse(null);
        return toRoom(row, type);
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
