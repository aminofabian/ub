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
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.ServiceBoardResponse;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.CancelTicketRequest;
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
                branch, body.clientTicketId(), body.customerId(), body.requestedStaffProfileId(), body.lines());
        return serviceTicketService.create(TenantRequestIds.resolveBusinessId(request), safe, caller.userId());
    }

    @PostMapping("/{id}/lines")
    public TicketResponse addLine(
            @PathVariable String id, @Valid @RequestBody TicketLineInput body, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return serviceTicketService.addLine(TenantRequestIds.resolveBusinessId(request), id, body);
    }

    @DeleteMapping("/{id}/lines/{lineId}")
    public TicketResponse removeLine(
            @PathVariable String id, @PathVariable String lineId, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return serviceTicketService.removeLine(TenantRequestIds.resolveBusinessId(request), id, lineId);
    }

    @PostMapping("/{id}/start")
    public TicketResponse start(
            @PathVariable String id, @Valid @RequestBody StartTicketRequest body, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return serviceTicketService.start(TenantRequestIds.resolveBusinessId(request), id, body);
    }

    @PostMapping("/{id}/done")
    public TicketResponse done(@PathVariable String id, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return serviceTicketService.finish(TenantRequestIds.resolveBusinessId(request), id);
    }

    @PostMapping("/{id}/reopen")
    public TicketResponse reopen(@PathVariable String id, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return serviceTicketService.reopen(TenantRequestIds.resolveBusinessId(request), id);
    }

    @PostMapping("/{id}/cancel")
    public TicketResponse cancel(
            @PathVariable String id,
            @Valid @RequestBody(required = false) CancelTicketRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
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
        return serviceTicketService.pay(
                TenantRequestIds.resolveBusinessId(request), id, body, idempotencyKey, caller.userId(),
                caller.roleId(), request.getHeader(TillDeviceService.TILL_DEVICE_HEADER));
    }
}
