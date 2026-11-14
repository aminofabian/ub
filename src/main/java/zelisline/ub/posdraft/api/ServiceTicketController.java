package zelisline.ub.posdraft.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.posdraft.PosDraftConstants;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.ServiceBoardResponse;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.CancelTicketRequest;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.SetHelpersRequest;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.TakeDepositRequest;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.NoShowRequest;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.SetPriorityRequest;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.SetFloorStatusRequest;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.FloorStaffResponse;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.RescheduleRequest;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.CreateAppointmentRequest;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.CreateServiceTicketRequest;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.PaidTicketResponse;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.PayTicketRequest;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.StartTicketRequest;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.TicketLineInput;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.TicketResponse;
import zelisline.ub.posdraft.application.ServiceTicketService;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.tenancy.application.BranchResolutionService;
import zelisline.ub.till.application.TillDeviceService;

/**
 * Walk-in service tickets — the queue and the floor board of a service business (ADR 0014).
 * Anyone who can sell can run the queue; a branch-locked role only sees its own branch.
 */
@Validated
@RestController
@RequestMapping("/api/v1/service-tickets")
@RequiredArgsConstructor
@PreAuthorize("hasPermission(null, 'sales.sell')")
public class ServiceTicketController {

    private final ServiceTicketService serviceTicketService;
    private final BranchResolutionService branchResolutionService;
    private final zelisline.ub.payroll.application.StaffProfileService staffProfileService;
    private final zelisline.ub.platform.security.CurrentUserPermissions currentUserPermissions;

    @GetMapping
    public ServiceBoardResponse board(@RequestParam String branchId, HttpServletRequest request) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        String branch = branchResolutionService.requireBranchForLockedRole(
                caller.roleId(), caller.branchId(), branchId);
        return serviceTicketService.board(TenantRequestIds.resolveBusinessId(request), branch);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TicketResponse create(@Valid @RequestBody CreateServiceTicketRequest body, HttpServletRequest request) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        String branch = branchResolutionService.requireBranchForLockedRole(
                caller.roleId(), caller.branchId(), body.branchId());
        CreateServiceTicketRequest safe = new CreateServiceTicketRequest(
                branch, body.clientTicketId(), body.customerId(), body.requestedStaffProfileId(), body.lines(),
                body.redoOfSaleId());
        return serviceTicketService.create(TenantRequestIds.resolveBusinessId(request), safe, caller.userId());
    }

    @PostMapping("/{id}/lines")
    public TicketResponse addLine(
            @PathVariable String id, @Valid @RequestBody TicketLineInput body, HttpServletRequest request) {
        requireOwnBranch(CurrentTenantUser.requireHuman(request), request, id);
        return serviceTicketService.addLine(TenantRequestIds.resolveBusinessId(request), id, body);
    }

    @DeleteMapping("/{id}/lines/{lineId}")
    public TicketResponse removeLine(
            @PathVariable String id, @PathVariable String lineId, HttpServletRequest request) {
        requireOwnBranch(CurrentTenantUser.requireHuman(request), request, id);
        return serviceTicketService.removeLine(TenantRequestIds.resolveBusinessId(request), id, lineId);
    }

    /** Adds a product the customer is buying on this visit, at today's shelf price. */
    @PostMapping("/{id}/products")
    public TicketResponse addProduct(
            @PathVariable String id,
            @Valid @RequestBody zelisline.ub.posdraft.api.dto.ServiceTicketDtos.AddProductRequest body,
            HttpServletRequest request) {
        requireOwnBranch(CurrentTenantUser.requireHuman(request), request, id);
        return serviceTicketService.addProduct(TenantRequestIds.resolveBusinessId(request), id, body);
    }

    @DeleteMapping("/{id}/products/{lineId}")
    public TicketResponse removeProduct(@PathVariable String id, @PathVariable String lineId, HttpServletRequest request) {
        requireOwnBranch(CurrentTenantUser.requireHuman(request), request, id);
        return serviceTicketService.removeProduct(TenantRequestIds.resolveBusinessId(request), id, lineId);
    }

    /** Who does one service and where, without starting it. */
    @PostMapping("/{id}/lines/{lineId}/assign")
    public TicketResponse assignLine(
            @PathVariable String id, @PathVariable String lineId,
            @Valid @RequestBody zelisline.ub.posdraft.api.dto.ServiceTicketDtos.LineAssignmentRequest body,
            HttpServletRequest request) {
        requireOwnBranch(CurrentTenantUser.requireHuman(request), request, id);
        return serviceTicketService.assignLine(TenantRequestIds.resolveBusinessId(request), id, lineId, body);
    }

    /** Starts one service: refused when its person or its chair, bed or room is busy. */
    @PostMapping("/{id}/lines/{lineId}/start")
    public TicketResponse startLine(
            @PathVariable String id, @PathVariable String lineId,
            @Valid @RequestBody(required = false) zelisline.ub.posdraft.api.dto.ServiceTicketDtos.LineAssignmentRequest body,
            HttpServletRequest request) {
        requireOwnBranch(CurrentTenantUser.requireHuman(request), request, id);
        return serviceTicketService.startLine(TenantRequestIds.resolveBusinessId(request), id, lineId,
                body != null ? body : new zelisline.ub.posdraft.api.dto.ServiceTicketDtos.LineAssignmentRequest(null, null));
    }

    @PostMapping("/{id}/lines/{lineId}/done")
    public TicketResponse finishLine(@PathVariable String id, @PathVariable String lineId, HttpServletRequest request) {
        requireOwnBranch(CurrentTenantUser.requireHuman(request), request, id);
        return serviceTicketService.finishLine(TenantRequestIds.resolveBusinessId(request), id, lineId);
    }

    /** One visit, as it stands. */
    @GetMapping("/{id}")
    public TicketResponse get(@PathVariable String id, HttpServletRequest request) {
        requireOwnBranch(CurrentTenantUser.requireHuman(request), request, id);
        return serviceTicketService.get(TenantRequestIds.resolveBusinessId(request), id);
    }

    /** Each person's commission on each service and the shop's share. Staff earnings are private. */
    @GetMapping("/{id}/earnings")
    @org.springframework.security.access.prepost.PreAuthorize("hasPermission(null, 'commissions.read')")
    public zelisline.ub.posdraft.api.dto.ServiceTicketDtos.VisitEarningsResponse earnings(
            @PathVariable String id, HttpServletRequest request) {
        requireOwnBranch(CurrentTenantUser.requireHuman(request), request, id);
        return serviceTicketService.earnings(TenantRequestIds.resolveBusinessId(request), id);
    }

    /**
     * Everyone working on one service, the first leading it, with what each is paid for it where
     * that is set. Setting an amount is a commission decision and needs the permission for one.
     */
    @PostMapping("/{id}/lines/{lineId}/team")
    public TicketResponse setTeam(
            @PathVariable String id,
            @PathVariable String lineId,
            @Valid @RequestBody zelisline.ub.posdraft.api.dto.ServiceTicketDtos.SetTeamRequest body,
            HttpServletRequest request
    ) {
        requireOwnBranch(CurrentTenantUser.requireHuman(request), request, id);
        if (body.members().stream().anyMatch(m -> m.amount() != null)) {
            currentUserPermissions.requireAny("commissions.plans.manage", "commissions.adjust");
        }
        return serviceTicketService.setTeam(TenantRequestIds.resolveBusinessId(request), id, lineId, body.members());
    }

    @PostMapping("/{id}/lines/{lineId}/helpers")
    public TicketResponse setHelpers(
            @PathVariable String id,
            @PathVariable String lineId,
            @Valid @RequestBody SetHelpersRequest body,
            HttpServletRequest request
    ) {
        requireOwnBranch(CurrentTenantUser.requireHuman(request), request, id);
        // Pay from a role the owner set beforehand needs no permission here; a typed amount does.
        boolean maySetAmounts = currentUserPermissions.has("commissions.plans.manage")
                || currentUserPermissions.has("commissions.adjust");
        return serviceTicketService.setHelpers(
                TenantRequestIds.resolveBusinessId(request), id, lineId, body.helpers(), maySetAmounts);
    }

    @PostMapping("/{id}/start")
    public TicketResponse start(
            @PathVariable String id, @Valid @RequestBody StartTicketRequest body, HttpServletRequest request) {
        requireOwnBranch(CurrentTenantUser.requireHuman(request), request, id);
        return serviceTicketService.start(TenantRequestIds.resolveBusinessId(request), id, body);
    }

    @PostMapping("/{id}/done")
    public TicketResponse done(@PathVariable String id, HttpServletRequest request) {
        requireOwnBranch(CurrentTenantUser.requireHuman(request), request, id);
        return serviceTicketService.finish(TenantRequestIds.resolveBusinessId(request), id);
    }

    @PostMapping("/{id}/reopen")
    public TicketResponse reopen(@PathVariable String id, HttpServletRequest request) {
        requireOwnBranch(CurrentTenantUser.requireHuman(request), request, id);
        return serviceTicketService.reopen(TenantRequestIds.resolveBusinessId(request), id);
    }

    @PostMapping("/{id}/cancel")
    public TicketResponse cancel(
            @PathVariable String id,
            @Valid @RequestBody(required = false) CancelTicketRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        ServiceTicketService.TicketRef ticket = requireOwnBranch(caller, request, id);
        // Anyone who can sell may note a customer who left the queue. Once the customer has been
        // served, cancelling throws away work that should be paid for, so it needs the same
        // permission as clearing any held sale.
        if (ticket.open()
                && !PosDraftConstants.STAGE_WAITING.equals(ticket.stage())
                && !PosDraftConstants.STAGE_BOOKED.equals(ticket.stage())) {
            currentUserPermissions.requireAny("pos.drafts.cancel.any", "services.catalog.manage");
        }
        return serviceTicketService.cancel(TenantRequestIds.resolveBusinessId(request), id, body, caller.userId());
    }

    @PostMapping("/{id}/pay")
    public PaidTicketResponse pay(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PayTicketRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required");
        }
        requireOwnBranch(caller, request, id);
        return serviceTicketService.pay(
                TenantRequestIds.resolveBusinessId(request), id, body, idempotencyKey, caller.userId(),
                caller.roleId(), request.getHeader(TillDeviceService.TILL_DEVICE_HEADER));
    }

    @PostMapping("/{id}/priority")
    public TicketResponse setPriority(
            @PathVariable String id, @Valid @RequestBody SetPriorityRequest body, HttpServletRequest request) {
        requireOwnBranch(CurrentTenantUser.requireHuman(request), request, id);
        return serviceTicketService.setPriority(TenantRequestIds.resolveBusinessId(request), id, body.priority());
    }

    /** Whether a staff member can take the next customer. Set from the floor board. */
    @PostMapping("/staff/{staffProfileId}/floor-status")
    public FloorStaffResponse setFloorStatus(
            @PathVariable String staffProfileId,
            @Valid @RequestBody SetFloorStatusRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String status = staffProfileService.setFloorStatus(businessId, staffProfileId, body.status());
        return new FloorStaffResponse(staffProfileId,
                staffProfileService.displayNamesByIds(businessId, java.util.List.of(staffProfileId))
                        .getOrDefault(staffProfileId, "Staff"),
                status);
    }

    // ------------------------------------------------------------ appointments

    @GetMapping("/appointments")
    public java.util.List<TicketResponse> appointments(
            @RequestParam String branchId,
            @RequestParam java.time.Instant from,
            @RequestParam java.time.Instant to,
            HttpServletRequest request
    ) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        String branch = branchResolutionService.requireBranchForLockedRole(
                caller.roleId(), caller.branchId(), branchId);
        if (!to.isAfter(from) || java.time.Duration.between(from, to).toDays() > 62) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a period of up to two months");
        }
        return serviceTicketService.appointments(TenantRequestIds.resolveBusinessId(request), branch, from, to);
    }

    /** The branch calendar for a window of up to two weeks. */
    @GetMapping("/calendar")
    public zelisline.ub.posdraft.api.dto.ServiceTicketDtos.CalendarResponse calendar(
            @RequestParam String branchId,
            @RequestParam java.time.Instant from,
            @RequestParam java.time.Instant to,
            HttpServletRequest request
    ) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        String branch = branchResolutionService.requireBranchForLockedRole(
                caller.roleId(), caller.branchId(), branchId);
        if (!to.isAfter(from) || java.time.Duration.between(from, to).toDays() > 14) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a period of up to two weeks");
        }
        return serviceTicketService.calendar(TenantRequestIds.resolveBusinessId(request), branch, from, to);
    }

    @PostMapping("/appointments")
    @ResponseStatus(HttpStatus.CREATED)
    public TicketResponse book(@Valid @RequestBody CreateAppointmentRequest body, HttpServletRequest request) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        String branch = branchResolutionService.requireBranchForLockedRole(
                caller.roleId(), caller.branchId(), body.branchId());
        CreateAppointmentRequest safe = new CreateAppointmentRequest(
                branch, body.clientTicketId(), body.staffProfileId(), body.start(), body.lines(),
                body.customerId(), body.name(), body.phone(), body.sendReminder(), body.resourceId());
        return serviceTicketService.book(TenantRequestIds.resolveBusinessId(request), safe, caller.userId());
    }

    @PostMapping("/{id}/reschedule")
    public TicketResponse reschedule(
            @PathVariable String id, @Valid @RequestBody RescheduleRequest body, HttpServletRequest request) {
        requireOwnBranch(CurrentTenantUser.requireHuman(request), request, id);
        return serviceTicketService.reschedule(TenantRequestIds.resolveBusinessId(request), id, body);
    }

    @PostMapping("/{id}/arrive")
    public TicketResponse arrive(@PathVariable String id, HttpServletRequest request) {
        requireOwnBranch(CurrentTenantUser.requireHuman(request), request, id);
        return serviceTicketService.arrive(TenantRequestIds.resolveBusinessId(request), id);
    }

    @PostMapping("/{id}/no-show")
    public TicketResponse noShow(
            @PathVariable String id,
            @RequestBody(required = false) NoShowRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        requireOwnBranch(caller, request, id);
        return serviceTicketService.noShow(TenantRequestIds.resolveBusinessId(request), id, caller.userId(),
                body != null && Boolean.TRUE.equals(body.keepDepositAsCredit()));
    }

    /** Money taken in advance against a ticket or booking. Cash goes into the open shift's drawer. */
    @PostMapping("/{id}/deposit")
    public TicketResponse takeDeposit(
            @PathVariable String id, @Valid @RequestBody TakeDepositRequest body, HttpServletRequest request) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        requireOwnBranch(caller, request, id);
        return serviceTicketService.takeDeposit(TenantRequestIds.resolveBusinessId(request), id, body,
                caller.userId(), request.getHeader(TillDeviceService.TILL_DEVICE_HEADER));
    }

    /** A branch-locked role may only act on tickets of its own branch, as with sales. */
    private ServiceTicketService.TicketRef requireOwnBranch(
            TenantPrincipal caller, HttpServletRequest request, String ticketId) {
        ServiceTicketService.TicketRef ticket =
                serviceTicketService.ref(TenantRequestIds.resolveBusinessId(request), ticketId);
        branchResolutionService.requireBranchForLockedRole(caller.roleId(), caller.branchId(), ticket.branchId());
        return ticket;
    }
}
