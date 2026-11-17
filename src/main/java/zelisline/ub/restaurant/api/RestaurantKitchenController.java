package zelisline.ub.restaurant.api;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
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
import zelisline.ub.restaurant.api.dto.RestaurantDtos.KitchenBoardResponse;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.KitchenPrintJobResponse;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.KitchenPrintJobsResponse;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.KitchenTicketResponse;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.UnprintedTicketsResponse;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.KitchenTimesReport;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.MenuSalesReport;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.OrderListResponse;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.OrderSummaryResponse;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.StageRequest;
import zelisline.ub.restaurant.application.KitchenPrintService;
import zelisline.ub.restaurant.application.KitchenService;
import zelisline.ub.restaurant.application.RestaurantReportsService;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.tenancy.application.BranchResolutionService;

/**
 * The kitchen board, the counter's list of orders, and the restaurant reports (ADR 0020).
 * Everything is scoped to the caller's business and, for someone tied to a branch, to that branch.
 * The stage changes are idempotent: asking for the stage a line already has changes nothing.
 */
@Validated
@RestController
@RequestMapping("/api/v1/restaurant")
@RequiredArgsConstructor
public class RestaurantKitchenController {

    private static final String KITCHEN = "hasPermission(null, 'restaurant.kitchen.update')";
    private static final String KITCHEN_OR_COUNTER =
            "hasPermission(null, 'restaurant.kitchen.update') or hasPermission(null, 'sales.sell')";
    private static final String COUNTER = "hasPermission(null, 'sales.sell')";
    private static final String REPORTS =
            "hasPermission(null, 'restaurant.menu.manage') or hasPermission(null, 'sales.intelligence.read')";

    private final KitchenService kitchenService;
    private final KitchenPrintService kitchenPrintService;
    private final RestaurantReportsService reportsService;
    private final BranchResolutionService branchResolutionService;

    @GetMapping("/kitchen/tickets")
    @PreAuthorize(KITCHEN_OR_COUNTER)
    public KitchenBoardResponse board(
            @RequestParam String branchId, @RequestParam(required = false) String stationId,
            HttpServletRequest request) {
        return kitchenService.board(TenantRequestIds.resolveBusinessId(request), branch(request, branchId), stationId);
    }

    @PostMapping("/kitchen/lines/{lineId}/stage")
    @PreAuthorize(KITCHEN)
    public KitchenTicketResponse setLineStage(
            @PathVariable String lineId, @Valid @RequestBody StageRequest body, HttpServletRequest request) {
        String businessId = TenantRequestIds.resolveBusinessId(request);
        branch(request, kitchenService.branchOfLine(businessId, lineId));
        return kitchenService.setLineStage(businessId, lineId, body.stage());
    }

    @PostMapping("/kitchen/tickets/{ticketId}/stage")
    @PreAuthorize(KITCHEN)
    public KitchenTicketResponse setTicketStage(
            @PathVariable String ticketId, @Valid @RequestBody StageRequest body, HttpServletRequest request) {
        String businessId = TenantRequestIds.resolveBusinessId(request);
        branch(request, kitchenService.branchOfTicket(businessId, ticketId));
        return kitchenService.setTicketStage(businessId, ticketId, body.stage());
    }

    @PostMapping("/kitchen/tickets/{ticketId}/acknowledge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(KITCHEN)
    public void acknowledge(@PathVariable String ticketId, HttpServletRequest request) {
        String businessId = TenantRequestIds.resolveBusinessId(request);
        branch(request, kitchenService.branchOfTicket(businessId, ticketId));
        kitchenService.acknowledgeVoid(businessId, ticketId);
    }

    /** Orders paid for and not yet handed over. */
    @GetMapping("/orders")
    @PreAuthorize(KITCHEN_OR_COUNTER)
    public OrderListResponse orders(@RequestParam String branchId, HttpServletRequest request) {
        return kitchenService.ordersInProgress(TenantRequestIds.resolveBusinessId(request), branch(request, branchId));
    }

    /** The order a sale produced; 404 when the sale sent nothing to a kitchen. */
    @GetMapping("/orders/by-sale/{saleId}")
    @PreAuthorize(COUNTER)
    public OrderSummaryResponse orderForSale(@PathVariable String saleId, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return kitchenService.orderForSale(TenantRequestIds.resolveBusinessId(request), saleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No kitchen order for that sale"));
    }

    @PostMapping("/orders/{orderId}/handed-over")
    @PreAuthorize(COUNTER)
    public OrderSummaryResponse handOver(@PathVariable String orderId, HttpServletRequest request) {
        String businessId = TenantRequestIds.resolveBusinessId(request);
        branch(request, kitchenService.branchOfOrder(businessId, orderId));
        return kitchenService.handOver(businessId, orderId);
    }

    @GetMapping("/reports/menu-sales")
    @PreAuthorize(REPORTS)
    public MenuSalesReport menuSales(
            @RequestParam String branchId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletRequest request) {
        return reportsService.menuSales(TenantRequestIds.resolveBusinessId(request), branch(request, branchId), from, to);
    }

    /** What each table and each waiter took over a period, from the bills paid. */
    @GetMapping("/reports/tables")
    @PreAuthorize(REPORTS)
    public zelisline.ub.restaurant.api.dto.RestaurantDtos.TableTakingsReport tableTakings(
            @RequestParam String branchId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletRequest request) {
        return reportsService.tableTakings(TenantRequestIds.resolveBusinessId(request), branch(request, branchId), from, to);
    }

    @GetMapping("/reports/kitchen-times")
    @PreAuthorize(REPORTS)
    public KitchenTimesReport kitchenTimes(
            @RequestParam String branchId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletRequest request) {
        return reportsService.kitchenTimes(TenantRequestIds.resolveBusinessId(request), branch(request, branchId), from, to);
    }

    // ------------------------------------------------------------------ kitchen print queue

    /** Print jobs a device should print now: its station's, and the implicit kitchen's, oldest first. */
    @GetMapping("/kitchen/print-jobs")
    @PreAuthorize(KITCHEN_OR_COUNTER)
    public KitchenPrintJobsResponse printJobs(
            @RequestParam String branchId, @RequestParam(required = false) String stationId, HttpServletRequest request) {
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return new KitchenPrintJobsResponse(
                kitchenPrintService.pending(businessId, branch(request, branchId), stationId));
    }

    /** Claim one job for this device; exactly one device wins, the rest get 404 and leave it. */
    @PostMapping("/kitchen/print-jobs/{jobId}/claim")
    @PreAuthorize(KITCHEN_OR_COUNTER)
    public KitchenPrintJobResponse claimPrintJob(@PathVariable String jobId, HttpServletRequest request) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        return kitchenPrintService.claim(TenantRequestIds.resolveBusinessId(request), caller.userId(), jobId);
    }

    /** A device confirms a job's ticket came out of the printer. */
    @PostMapping("/kitchen/print-jobs/{jobId}/printed")
    @PreAuthorize(KITCHEN_OR_COUNTER)
    public void markPrinted(@PathVariable String jobId, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        kitchenPrintService.markPrinted(TenantRequestIds.resolveBusinessId(request), jobId);
    }

    /** Reprint a ticket from the counter; the new slip is marked a reprint. */
    @PostMapping("/kitchen/tickets/{ticketId}/reprint")
    @PreAuthorize(KITCHEN_OR_COUNTER)
    public KitchenPrintJobResponse reprint(@PathVariable String ticketId, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return kitchenPrintService.reprint(TenantRequestIds.resolveBusinessId(request), ticketId);
    }

    /** Tickets the counter should warn have not printed at the kitchen. */
    @GetMapping("/kitchen/unprinted")
    @PreAuthorize(COUNTER)
    public UnprintedTicketsResponse unprinted(@RequestParam String branchId, HttpServletRequest request) {
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return new UnprintedTicketsResponse(kitchenPrintService.unprinted(businessId, branch(request, branchId)));
    }

    /** The branch the caller may act on: the one asked for, unless their role ties them to another. */
    private String branch(HttpServletRequest request, String branchId) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        return branchResolutionService.requireBranchForLockedRole(caller.roleId(), caller.branchId(), branchId);
    }
}
