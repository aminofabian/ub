package zelisline.ub.restaurant.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.restaurant.api.dto.RoomDtos.CreateRoomRequest;
import zelisline.ub.restaurant.api.dto.RoomDtos.CreateRoomTypeRequest;
import zelisline.ub.restaurant.api.dto.RoomDtos.FrontDeskResponse;
import zelisline.ub.restaurant.api.dto.RoomDtos.HousekeepingRequest;
import zelisline.ub.restaurant.api.dto.RoomDtos.RoomRequest;
import zelisline.ub.restaurant.api.dto.RoomDtos.RoomResponse;
import zelisline.ub.restaurant.api.dto.RoomDtos.RoomTypeListResponse;
import zelisline.ub.restaurant.api.dto.RoomDtos.RoomTypeRequest;
import zelisline.ub.restaurant.api.dto.RoomDtos.RoomTypeResponse;
import zelisline.ub.restaurant.api.dto.RoomDtos.SetRoomTypeRequest;
import zelisline.ub.restaurant.api.dto.RoomDtos.MaintenanceIssueListResponse;
import zelisline.ub.restaurant.api.dto.RoomDtos.MaintenanceIssueRequest;
import zelisline.ub.restaurant.api.dto.RoomDtos.MaintenanceIssueResponse;
import zelisline.ub.restaurant.api.dto.RoomDtos.MaintenanceIssueUpdate;
import zelisline.ub.restaurant.application.DineInService;
import zelisline.ub.serviceops.application.RoomMaintenanceService;
import zelisline.ub.serviceops.application.RoomMaintenanceService.IssueRecord;
import zelisline.ub.serviceops.application.ServiceResourceService;
import zelisline.ub.serviceops.application.ServiceResourceService.RoomRecord;
import zelisline.ub.serviceops.application.ServiceResourceService.RoomTypeRecord;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.tenancy.application.BranchResolutionService;

/**
 * The front desk of a guest house: its rooms and, later, the stays on them (ADR 0028). A lodging
 * tenant is a bar or restaurant that also lets a few rooms, so managing rooms reuses the restaurant
 * setup permission rather than a permission of its own. Everything is scoped to the caller's
 * business and, for someone tied to a branch, to that branch.
 */
@Validated
@RestController
@RequestMapping("/api/v1/rooms")
@RequiredArgsConstructor
public class RoomsController {

    /** Setting up rooms is a manager's job, like the menu. */
    private static final String MANAGE = "hasPermission(null, 'restaurant.menu.manage')";
    /** Seeing the front desk: anyone who can serve, take money or manage. */
    private static final String SEE = "hasPermission(null, 'restaurant.orders.write')"
            + " or hasPermission(null, 'sales.sell') or hasPermission(null, 'restaurant.menu.manage')";

    private final ServiceResourceService resourceService;
    private final DineInService dineInService;
    private final RoomMaintenanceService maintenanceService;
    private final zelisline.ub.restaurant.application.AccommodationReportService reportService;
    private final BranchResolutionService branchResolutionService;

    @GetMapping
    @PreAuthorize(SEE)
    public List<RoomResponse> rooms(@RequestParam String branchId, HttpServletRequest request) {
        String businessId = business(request);
        String resolvedBranch = branch(request, branchId);
        var openIssues = maintenanceService.openCountsByRoom(businessId, resolvedBranch);
        return resourceService.rooms(businessId, resolvedBranch).stream()
                .map(room -> toResponse(room, openIssues.getOrDefault(room.id(), 0)))
                .toList();
    }

    /** The front desk: every room with its stays (in-house and booked for later). */
    @GetMapping("/front-desk")
    @PreAuthorize(SEE)
    public FrontDeskResponse frontDesk(@RequestParam String branchId, HttpServletRequest request) {
        return dineInService.frontDesk(business(request), branch(request, branchId));
    }

    /** A guest's stay history: their past and present stays with this business (ADR 0030). */
    @GetMapping("/guest-stays")
    @PreAuthorize(SEE)
    public zelisline.ub.restaurant.api.dto.RoomDtos.GuestStaysResponse guestStays(
            @RequestParam String customerId, HttpServletRequest request) {
        return dineInService.guestStays(business(request), customerId);
    }

    /** The accommodation dashboard: today's rooms, and occupancy / ADR / RevPAR over a period (ADR 0030). */
    @GetMapping("/report")
    @PreAuthorize(SEE)
    public zelisline.ub.restaurant.api.dto.RoomDtos.AccommodationReportResponse report(
            @RequestParam String branchId,
            @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso =
                    org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso =
                    org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to,
            HttpServletRequest request) {
        return reportService.report(business(request), branch(request, branchId), from, to);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(MANAGE)
    public RoomResponse createRoom(@Valid @RequestBody CreateRoomRequest body, HttpServletRequest request) {
        RoomRecord room = resourceService.createRoom(
                business(request), branch(request, body.branchId()), body.name(), body.nightlyRate(), body.area());
        return toResponse(room);
    }

    @PatchMapping("/{roomId}")
    @PreAuthorize(MANAGE)
    public RoomResponse updateRoom(
            @PathVariable String roomId, @Valid @RequestBody RoomRequest body, HttpServletRequest request) {
        String businessId = business(request);
        // The room's own branch decides what the caller must be allowed to act on.
        String roomBranch = resourceService.room(businessId, roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"))
                .branchId();
        branch(request, roomBranch);
        RoomRecord room = resourceService.updateRoom(
                businessId, roomId, body.name(), body.nightlyRate(), body.area(), body.active());
        return toResponse(room);
    }

    /**
     * Set a room's housekeeping state (ADR 0030). Open to anyone who can see the front desk, not
     * just a manager: turning a room round is a cleaner's job, and a small guest house's cleaner is
     * front-desk staff. Taking a room out of use entirely stays the manager's {@code active} switch.
     */
    @PatchMapping("/{roomId}/housekeeping")
    @PreAuthorize(SEE)
    public RoomResponse setHousekeeping(
            @PathVariable String roomId, @Valid @RequestBody HousekeepingRequest body, HttpServletRequest request) {
        String businessId = business(request);
        String roomBranch = resourceService.room(businessId, roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"))
                .branchId();
        branch(request, roomBranch);
        // If a cleaner is named, they must be a real staff member of this business.
        if (body.cleanerStaffProfileId() != null && !body.cleanerStaffProfileId().isBlank()) {
            dineInService.requireStaffMember(businessId, body.cleanerStaffProfileId().trim());
        }
        return toResponse(resourceService.setRoomHousekeeping(businessId, roomId, body.status(),
                body.cleanerStaffProfileId(), body.note(), Boolean.TRUE.equals(body.clearCleaner()),
                Boolean.TRUE.equals(body.clearNote())));
    }

    /** Assign a room to a type (its rate card), or clear it (ADR 0030). A manager's job, like the rate. */
    @PatchMapping("/{roomId}/type")
    @PreAuthorize(MANAGE)
    public RoomResponse setRoomType(
            @PathVariable String roomId, @Valid @RequestBody SetRoomTypeRequest body, HttpServletRequest request) {
        String businessId = business(request);
        String roomBranch = resourceService.room(businessId, roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"))
                .branchId();
        branch(request, roomBranch);
        return toResponse(resourceService.setRoomType(businessId, roomId, body.roomTypeId()));
    }

    // ------------------------------------------------------------------ room types (rate cards)

    @GetMapping("/types")
    @PreAuthorize(SEE)
    public RoomTypeListResponse roomTypes(@RequestParam String branchId, HttpServletRequest request) {
        String resolved = branch(request, branchId);
        return new RoomTypeListResponse(resolved, resourceService.roomTypes(business(request), resolved).stream()
                .map(RoomsController::toResponse).toList());
    }

    @PostMapping("/types")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(MANAGE)
    public RoomTypeResponse createRoomType(@Valid @RequestBody CreateRoomTypeRequest body, HttpServletRequest request) {
        return toResponse(resourceService.createRoomType(business(request), branch(request, body.branchId()),
                body.name(), body.defaultNightlyRate(), body.weekendNightlyRate(), body.capacity(),
                body.bedConfig(), body.amenities()));
    }

    @PatchMapping("/types/{typeId}")
    @PreAuthorize(MANAGE)
    public RoomTypeResponse updateRoomType(
            @PathVariable String typeId, @Valid @RequestBody RoomTypeRequest body, HttpServletRequest request) {
        String businessId = business(request);
        // The type's own branch decides what a branch-locked manager may act on.
        branch(request, resourceService.roomType(businessId, typeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room type not found"))
                .branchId());
        return toResponse(resourceService.updateRoomType(businessId, typeId, body.name(), body.defaultNightlyRate(),
                body.weekendNightlyRate(), body.capacity(), body.bedConfig(), body.amenities()));
    }

    // ---------------------------------------------------------------- maintenance issues (ADR 0030 Phase 2)

    /** Raise a maintenance issue on a room; it moves the room to MAINTENANCE and is kept as history. */
    @PostMapping("/{roomId}/issues")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(SEE)
    public MaintenanceIssueResponse openIssue(
            @PathVariable String roomId, @Valid @RequestBody MaintenanceIssueRequest body, HttpServletRequest request) {
        String businessId = business(request);
        // A branch-locked operator may only act on their own branch's room.
        branch(request, resourceService.room(businessId, roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"))
                .branchId());
        IssueRecord issue = maintenanceService.open(businessId, roomId, body.description(), body.photoUrl(),
                body.assignedStaffProfileId(), CurrentTenantUser.requireHuman(request).userId());
        return toResponse(businessId, issue);
    }

    /** A room's maintenance history, newest first. */
    @GetMapping("/{roomId}/issues")
    @PreAuthorize(SEE)
    public MaintenanceIssueListResponse roomIssues(@PathVariable String roomId, HttpServletRequest request) {
        String businessId = business(request);
        String roomBranch = resourceService.room(businessId, roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"))
                .branchId();
        branch(request, roomBranch);
        List<MaintenanceIssueResponse> issues = maintenanceService.forRoom(businessId, roomId).stream()
                .map(issue -> toResponse(businessId, issue)).toList();
        return new MaintenanceIssueListResponse(roomBranch, issues);
    }

    /** Every open (not-yet-done) maintenance issue at a branch — the front desk's maintenance list. */
    @GetMapping("/issues")
    @PreAuthorize(SEE)
    public MaintenanceIssueListResponse issues(
            @RequestParam String branchId,
            @RequestParam(required = false, defaultValue = "true") boolean openOnly,
            HttpServletRequest request) {
        String businessId = business(request);
        String resolvedBranch = branch(request, branchId);
        List<MaintenanceIssueResponse> issues = maintenanceService.list(businessId, resolvedBranch, openOnly).stream()
                .map(issue -> toResponse(businessId, issue)).toList();
        return new MaintenanceIssueListResponse(resolvedBranch, issues);
    }

    /** Move an issue's status, (re)assign it, or set/clear its photo. */
    @PatchMapping("/issues/{issueId}")
    @PreAuthorize(SEE)
    public MaintenanceIssueResponse updateIssue(
            @PathVariable String issueId, @Valid @RequestBody MaintenanceIssueUpdate body, HttpServletRequest request) {
        String businessId = business(request);
        if (body.assignedStaffProfileId() != null && !body.assignedStaffProfileId().isBlank()) {
            dineInService.requireStaffMember(businessId, body.assignedStaffProfileId().trim());
        }
        IssueRecord issue = maintenanceService.update(businessId, issueId, body.status(),
                body.assignedStaffProfileId(), body.photoUrl(),
                Boolean.TRUE.equals(body.clearAssignee()), Boolean.TRUE.equals(body.clearPhoto()));
        return toResponse(businessId, issue);
    }

    // ------------------------------------------------------------------ helpers

    /** A single room: counts its own open issues. Use the batch overload when mapping a list. */
    private RoomResponse toResponse(RoomRecord room) {
        return toResponse(room, (int) maintenanceService.openCount(room.businessId(), room.id()));
    }

    private RoomResponse toResponse(RoomRecord room, int openIssueCount) {
        String cleaner = room.cleanerStaffProfileId() == null ? null
                : dineInService.staffName(room.businessId(), room.cleanerStaffProfileId());
        return new RoomResponse(room.id(), room.branchId(), room.name(), room.nightlyRate(),
                room.area(), room.active(), room.sortOrder(), room.housekeepingStatus(), room.readyToLet(),
                room.roomTypeId(), room.effectiveNightlyRate(), room.effectiveWeekendRate(),
                room.housekeepingNote(), room.cleanerStaffProfileId(), cleaner, openIssueCount);
    }

    /** Resolve an issue's assignee name for the response. */
    private MaintenanceIssueResponse toResponse(String businessId, IssueRecord issue) {
        String assignedName = issue.assignedStaffProfileId() == null ? null
                : dineInService.staffName(businessId, issue.assignedStaffProfileId());
        return new MaintenanceIssueResponse(issue.id(), issue.roomResourceId(), issue.roomName(),
                issue.description(), issue.status(), issue.photoUrl(), issue.assignedStaffProfileId(),
                assignedName, issue.openedAt(), issue.closedAt());
    }

    private static RoomTypeResponse toResponse(RoomTypeRecord t) {
        return new RoomTypeResponse(t.id(), t.branchId(), t.name(), t.defaultNightlyRate(), t.weekendNightlyRate(),
                t.capacity(), t.bedConfig(), t.amenities(), t.sortOrder());
    }

    private static String business(HttpServletRequest request) {
        return TenantRequestIds.resolveBusinessId(request);
    }

    /** The branch the caller may act on: the one asked for, unless their role ties them to another. */
    private String branch(HttpServletRequest request, String branchId) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        if (branchId == null || branchId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a branch");
        }
        return branchResolutionService.requireBranchForLockedRole(caller.roleId(), caller.branchId(), branchId.trim());
    }
}
