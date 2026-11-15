package zelisline.ub.restaurant.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentUserPermissions;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.restaurant.api.dto.OrderDtos.AddLinesRequest;
import zelisline.ub.restaurant.api.dto.OrderDtos.BillRequestedRequest;
import zelisline.ub.restaurant.api.dto.OrderDtos.CancelOrderRequest;
import zelisline.ub.restaurant.api.dto.OrderDtos.ChangeLineRequest;
import zelisline.ub.restaurant.api.dto.OrderDtos.CloseBillRequest;
import zelisline.ub.restaurant.api.dto.OrderDtos.CreateBillRequest;
import zelisline.ub.restaurant.api.dto.OrderDtos.FloorResponse;
import zelisline.ub.restaurant.api.dto.OrderDtos.MoveRequest;
import zelisline.ub.restaurant.api.dto.OrderDtos.OpenOrderRequest;
import zelisline.ub.restaurant.api.dto.OrderDtos.OrderDetailsRequest;
import zelisline.ub.restaurant.api.dto.OrderDtos.OrderResponse;
import zelisline.ub.restaurant.api.dto.OrderDtos.PayBillRequest;
import zelisline.ub.restaurant.api.dto.OrderDtos.TableRequest;
import zelisline.ub.restaurant.api.dto.OrderDtos.TableResponse;
import zelisline.ub.restaurant.api.dto.OrderDtos.VoidLineRequest;
import zelisline.ub.restaurant.application.BillService;
import zelisline.ub.restaurant.application.DineInService;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.tenancy.application.BranchResolutionService;
import zelisline.ub.till.application.TillDeviceService;

/**
 * The restaurant floor: tables, orders that are paid after they are eaten, and their bills
 * (ADR 0020, phase 1b). Everything is scoped to the caller's business and, for someone tied to a
 * branch, to that branch. Opening an order, adding lines and paying a bill are safe to repeat:
 * each carries an id made once by the device.
 */
@Validated
@RestController
@RequestMapping("/api/v1/restaurant")
@RequiredArgsConstructor
public class RestaurantOrdersController {

    private static final String SERVE = "hasPermission(null, 'restaurant.orders.write')";
    private static final String SEE_FLOOR = "hasPermission(null, 'restaurant.orders.write')"
            + " or hasPermission(null, 'sales.sell') or hasPermission(null, 'restaurant.menu.manage')";
    private static final String MANAGE = "hasPermission(null, 'restaurant.menu.manage')";
    /** Taking money is the till's permission, as everywhere else. */
    private static final String TAKE_MONEY = "hasPermission(null, 'sales.sell')";
    private static final String BILL = "hasPermission(null, 'restaurant.orders.write') or hasPermission(null, 'sales.sell')";
    private static final String COMP = "hasPermission(null, 'restaurant.bills.comp')";

    private final DineInService dineInService;
    private final BillService billService;
    private final BranchResolutionService branchResolutionService;
    private final CurrentUserPermissions currentUserPermissions;

    // ------------------------------------------------------------------ tables

    @GetMapping("/tables")
    @PreAuthorize(SEE_FLOOR)
    public List<TableResponse> tables(@RequestParam String branchId, HttpServletRequest request) {
        return dineInService.tables(business(request), branch(request, branchId));
    }

    @PostMapping("/tables")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(MANAGE)
    public TableResponse createTable(@Valid @RequestBody TableRequest body, HttpServletRequest request) {
        return dineInService.createTable(business(request), branch(request, body.branchId()), body);
    }

    @PatchMapping("/tables/{tableId}")
    @PreAuthorize(MANAGE)
    public TableResponse updateTable(
            @PathVariable String tableId, @Valid @RequestBody TableRequest body, HttpServletRequest request) {
        String businessId = business(request);
        branch(request, dineInService.branchOfTable(businessId, tableId));
        return dineInService.updateTable(businessId, tableId, body);
    }

    /** Gives a table a new QR code for customers to order from (the old one stops working), or removes it. */
    @PostMapping("/tables/{tableId}/qr")
    @PreAuthorize(MANAGE)
    public TableResponse setTableQr(
            @PathVariable String tableId,
            @Valid @RequestBody zelisline.ub.restaurant.api.dto.OrderDtos.TableQrRequest body,
            HttpServletRequest request) {
        String businessId = business(request);
        branch(request, dineInService.branchOfTable(businessId, tableId));
        return dineInService.setTableQr(businessId, tableId, body.enabled());
    }

    /** Who can be named as serving an order. */
    @GetMapping("/servers")
    @PreAuthorize(SEE_FLOOR)
    public List<zelisline.ub.restaurant.api.dto.OrderDtos.ServerResponse> servers(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return dineInService.servers(business(request));
    }

    // ------------------------------------------------------------------ the floor and orders

    @GetMapping("/floor")
    @PreAuthorize(SEE_FLOOR)
    public FloorResponse floor(@RequestParam String branchId, HttpServletRequest request) {
        return dineInService.floor(business(request), branch(request, branchId));
    }

    @PostMapping("/open-orders")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(SERVE)
    public OrderResponse open(@Valid @RequestBody OpenOrderRequest body, HttpServletRequest request) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        return dineInService.open(business(request), branch(request, body.branchId()), caller.userId(), body);
    }

    @GetMapping("/open-orders/{orderId}")
    @PreAuthorize(SEE_FLOOR)
    public OrderResponse get(@PathVariable String orderId, HttpServletRequest request) {
        String businessId = orderBranchChecked(request, orderId);
        return dineInService.get(businessId, orderId);
    }

    @PostMapping("/open-orders/{orderId}/lines")
    @PreAuthorize(SERVE)
    public OrderResponse addLines(
            @PathVariable String orderId, @Valid @RequestBody AddLinesRequest body, HttpServletRequest request) {
        return dineInService.addLines(orderBranchChecked(request, orderId), orderId, body.lines());
    }

    @PatchMapping("/open-orders/{orderId}/lines/{lineId}")
    @PreAuthorize(SERVE)
    public OrderResponse changeLine(
            @PathVariable String orderId, @PathVariable String lineId, @Valid @RequestBody ChangeLineRequest body,
            HttpServletRequest request) {
        return dineInService.changeLine(orderBranchChecked(request, orderId), orderId, lineId, body.quantity(),
                body.note(), body.seatNo());
    }

    /**
     * Cancels a line. Removing one that was never sent needs only the right to take orders;
     * cancelling one the kitchen already has also needs {@code restaurant.orders.void_sent} and a reason.
     */
    @PostMapping("/open-orders/{orderId}/lines/{lineId}/cancel")
    @PreAuthorize(SERVE)
    public OrderResponse voidLine(
            @PathVariable String orderId, @PathVariable String lineId,
            @Valid @RequestBody(required = false) VoidLineRequest body, HttpServletRequest request) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        return dineInService.voidLine(orderBranchChecked(request, orderId), orderId, lineId,
                body == null ? null : body.reason(), caller.userId(),
                currentUserPermissions.has("restaurant.orders.void_sent"));
    }

    /** Sends everything not yet sent to the kitchen. Sending twice sends nothing the second time. */
    @PostMapping("/open-orders/{orderId}/send")
    @PreAuthorize(SERVE)
    public OrderResponse send(@PathVariable String orderId, HttpServletRequest request) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        return dineInService.send(orderBranchChecked(request, orderId), orderId, caller.userId());
    }

    @PostMapping("/open-orders/{orderId}/move")
    @PreAuthorize(SERVE)
    public OrderResponse move(
            @PathVariable String orderId, @Valid @RequestBody MoveRequest body, HttpServletRequest request) {
        return dineInService.move(orderBranchChecked(request, orderId), orderId, body.tableId());
    }

    /** Joins this order into another open order at the same branch; this one is closed. 409 if it has a bill. */
    @PostMapping("/open-orders/{orderId}/merge")
    @PreAuthorize(SERVE)
    public OrderResponse merge(
            @PathVariable String orderId,
            @Valid @RequestBody zelisline.ub.restaurant.api.dto.OrderDtos.MergeRequest body,
            HttpServletRequest request) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        String businessId = orderBranchChecked(request, orderId);
        branch(request, dineInService.branchOf(businessId, body.intoOrderId()));
        return dineInService.merge(businessId, orderId, body.intoOrderId(), caller.userId());
    }

    /** A delivery order's fee, rider, and whether it has left or arrived. */
    @PostMapping("/open-orders/{orderId}/delivery")
    @PreAuthorize(SERVE)
    public OrderResponse delivery(
            @PathVariable String orderId,
            @Valid @RequestBody zelisline.ub.restaurant.api.dto.OrderDtos.DeliveryRequest body,
            HttpServletRequest request) {
        return dineInService.setDelivery(orderBranchChecked(request, orderId), orderId, body.deliveryFee(),
                body.riderName(), body.dispatched(), body.delivered());
    }

    @PatchMapping("/open-orders/{orderId}")
    @PreAuthorize(SERVE)
    public OrderResponse setDetails(
            @PathVariable String orderId, @Valid @RequestBody OrderDetailsRequest body, HttpServletRequest request) {
        return dineInService.setDetails(orderBranchChecked(request, orderId), orderId, body);
    }

    @PostMapping("/open-orders/{orderId}/bill-requested")
    @PreAuthorize(SERVE)
    public OrderResponse billRequested(
            @PathVariable String orderId, @Valid @RequestBody BillRequestedRequest body, HttpServletRequest request) {
        return dineInService.requestBill(orderBranchChecked(request, orderId), orderId, body.requested());
    }

    /** A booking's guests have arrived. */
    @PostMapping("/open-orders/{orderId}/seat")
    @PreAuthorize(SERVE)
    public OrderResponse seat(@PathVariable String orderId, HttpServletRequest request) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        return dineInService.seat(orderBranchChecked(request, orderId), orderId, caller.userId());
    }

    /** Everything the kitchen has finished has been carried to the table. */
    @PostMapping("/open-orders/{orderId}/served")
    @PreAuthorize(SERVE)
    public OrderResponse served(@PathVariable String orderId, HttpServletRequest request) {
        return dineInService.markServed(orderBranchChecked(request, orderId), orderId);
    }

    @PostMapping("/open-orders/{orderId}/cancel")
    @PreAuthorize(SERVE)
    public OrderResponse cancel(
            @PathVariable String orderId, @Valid @RequestBody(required = false) CancelOrderRequest body,
            HttpServletRequest request) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        return dineInService.cancel(orderBranchChecked(request, orderId), orderId,
                body == null ? null : body.reason(), caller.userId());
    }

    // ------------------------------------------------------------------ bills

    @PostMapping("/open-orders/{orderId}/bills")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(BILL)
    public OrderResponse createBill(
            @PathVariable String orderId, @Valid @RequestBody(required = false) CreateBillRequest body,
            HttpServletRequest request) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        return billService.create(orderBranchChecked(request, orderId), orderId, caller.userId(), body);
    }

    @DeleteMapping("/open-orders/{orderId}/bills/{billId}")
    @PreAuthorize(BILL)
    public OrderResponse discardBill(
            @PathVariable String orderId, @PathVariable String billId, HttpServletRequest request) {
        return billService.discard(orderBranchChecked(request, orderId), orderId, billId);
    }

    /** Takes payment for a bill: one ordinary sale. Paying the same bill again rings nothing more. */
    @PostMapping("/open-orders/{orderId}/bills/{billId}/pay")
    @PreAuthorize(TAKE_MONEY)
    public OrderResponse payBill(
            @PathVariable String orderId, @PathVariable String billId, @Valid @RequestBody PayBillRequest body,
            HttpServletRequest request) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        return billService.pay(orderBranchChecked(request, orderId), orderId, billId, body, caller.userId(),
                caller.roleId(), request.getHeader(TillDeviceService.TILL_DEVICE_HEADER));
    }

    /** Closes a bill with no sale: on the house, or left without paying. */
    @PostMapping("/open-orders/{orderId}/bills/{billId}/close")
    @PreAuthorize(COMP)
    public OrderResponse closeBill(
            @PathVariable String orderId, @PathVariable String billId, @Valid @RequestBody CloseBillRequest body,
            HttpServletRequest request) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        return billService.closeWithoutSale(orderBranchChecked(request, orderId), orderId, billId, body.outcome(),
                body.reason(), caller.userId());
    }

    // ------------------------------------------------------------------ helpers

    private static String business(HttpServletRequest request) {
        return TenantRequestIds.resolveBusinessId(request);
    }

    /** The branch the caller may act on: the one asked for, unless their role ties them to another. */
    private String branch(HttpServletRequest request, String branchId) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        if (branchId == null || branchId.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a branch");
        }
        return branchResolutionService.requireBranchForLockedRole(caller.roleId(), caller.branchId(), branchId.trim());
    }

    /** Checks the caller may act at the order's branch and returns the business id. */
    private String orderBranchChecked(HttpServletRequest request, String orderId) {
        String businessId = business(request);
        branch(request, dineInService.branchOf(businessId, orderId));
        return businessId;
    }
}
