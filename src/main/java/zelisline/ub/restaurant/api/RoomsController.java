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
import zelisline.ub.restaurant.api.dto.RoomDtos.FrontDeskResponse;
import zelisline.ub.restaurant.api.dto.RoomDtos.RoomRequest;
import zelisline.ub.restaurant.api.dto.RoomDtos.RoomResponse;
import zelisline.ub.restaurant.application.DineInService;
import zelisline.ub.serviceops.application.ServiceResourceService;
import zelisline.ub.serviceops.application.ServiceResourceService.RoomRecord;
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
    private final BranchResolutionService branchResolutionService;

    @GetMapping
    @PreAuthorize(SEE)
    public List<RoomResponse> rooms(@RequestParam String branchId, HttpServletRequest request) {
        return resourceService.rooms(business(request), branch(request, branchId)).stream()
                .map(RoomsController::toResponse).toList();
    }

    /** The front desk: every room with its stays (in-house and booked for later). */
    @GetMapping("/front-desk")
    @PreAuthorize(SEE)
    public FrontDeskResponse frontDesk(@RequestParam String branchId, HttpServletRequest request) {
        return dineInService.frontDesk(business(request), branch(request, branchId));
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

    // ------------------------------------------------------------------ helpers

    private static RoomResponse toResponse(RoomRecord room) {
        return new RoomResponse(room.id(), room.branchId(), room.name(), room.nightlyRate(),
                room.area(), room.active(), room.sortOrder());
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
