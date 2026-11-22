package zelisline.ub.wholesale.api;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.wholesale.api.dto.AnswerSalesOrderRequest;
import zelisline.ub.wholesale.api.dto.SalesOrderResponse;
import zelisline.ub.wholesale.application.SalesOrderService;

/** The signed-in shop's own trade orders. */
@RestController
@RequestMapping("/api/v1/sales-orders")
@RequiredArgsConstructor
public class SalesOrdersController {

    private final SalesOrderService salesOrderService;
    private final zelisline.ub.wholesale.application.SalesFiguresService salesFiguresService;
    private final zelisline.ub.wholesale.application.StandingOrderService standingOrderService;
    private final zelisline.ub.wholesale.application.SupplierReadinessService supplierReadinessService;
    private final zelisline.ub.wholesale.application.SupplierStandingService supplierStandingService;
    private final zelisline.ub.wholesale.application.SupplierAppealService supplierAppealService;
    private final zelisline.ub.wholesale.application.CounterOrderService counterOrderService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'storefront.orders.read')")
    public List<SalesOrderResponse> list(
            @RequestParam(required = false) String status,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return salesOrderService.list(TenantRequestIds.resolveBusinessId(request), status);
    }

    /** The shop's own trade figures over a number of days. */
    @GetMapping("/figures")
    @PreAuthorize("hasPermission(null, 'storefront.orders.read')")
    public zelisline.ub.wholesale.api.dto.SalesFiguresResponse figures(
            @RequestParam(defaultValue = "30") int days,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return salesFiguresService.figures(TenantRequestIds.resolveBusinessId(request), days);
    }

    /** The standing orders buyers have with this shop. */
    @GetMapping("/standing")
    @PreAuthorize("hasPermission(null, 'storefront.orders.read')")
    public List<zelisline.ub.wholesale.api.dto.StandingOrderResponse> standing(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return standingOrderService.forSupplier(TenantRequestIds.resolveBusinessId(request));
    }

    /** The supplier stops a standing order a buyer has with it. */
    @PostMapping("/standing/{standingOrderId}/stop")
    @PreAuthorize("hasPermission(null, 'storefront.orders.read')")
    public void stopStanding(@PathVariable String standingOrderId, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        standingOrderService.stopAsSupplier(TenantRequestIds.resolveBusinessId(request), standingOrderId);
    }

    /** The supplier's own standing over 90 days. Supplier-only; empty and disabled unless its flags are on. */
    @GetMapping("/standing-measures")
    @PreAuthorize("hasPermission(null, 'storefront.orders.read')")
    public zelisline.ub.wholesale.api.dto.SupplierStandingResponse standingMeasures(
            @RequestParam(defaultValue = "90") int days,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return supplierStandingService.standing(TenantRequestIds.resolveBusinessId(request), days);
    }

    /** The supplier replaces its closed days (ISO dates). A closed day stops the working clock. Supplier-only. */
    @PutMapping("/closed-days")
    @PreAuthorize("hasPermission(null, 'storefront.orders.read')")
    public void setClosedDays(
            @Valid @RequestBody zelisline.ub.wholesale.api.dto.SetClosedDaysRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        supplierStandingService.setClosedDays(TenantRequestIds.resolveBusinessId(request), body.dates());
    }

    /** The supplier sets its dispatch lead time, in working days. Supplier-only. */
    @PutMapping("/lead-time")
    @PreAuthorize("hasPermission(null, 'storefront.orders.read')")
    public void setLeadTime(
            @Valid @RequestBody zelisline.ub.wholesale.api.dto.SetDispatchLeadTimeRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        supplierStandingService.setLeadWorkingDays(TenantRequestIds.resolveBusinessId(request), body.workingDays());
    }

    /** The supplier readiness checklist for the signed-in shop. Empty and disabled unless its flags are on. */
    @GetMapping("/readiness")
    @PreAuthorize("hasPermission(null, 'storefront.orders.read')")
    public zelisline.ub.wholesale.api.dto.SupplierReadinessResponse readiness(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return supplierReadinessService.readiness(TenantRequestIds.resolveBusinessId(request));
    }

    @GetMapping("/{orderId}")
    @PreAuthorize("hasPermission(null, 'storefront.orders.read')")
    public SalesOrderResponse get(@PathVariable String orderId, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return salesOrderService.get(TenantRequestIds.resolveBusinessId(request), orderId);
    }

    /** Accept, part-accept or reject each line. Same permission as handling a web order. */
    @PostMapping("/{orderId}/answer")
    @PreAuthorize("hasPermission(null, 'storefront.orders.read')")
    public SalesOrderResponse answer(
            @PathVariable String orderId,
            @Valid @RequestBody AnswerSalesOrderRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return salesOrderService.answer(TenantRequestIds.resolveBusinessId(request), orderId, body);
    }

    /** Opens or updates the account of the shop a signed-in marketplace buyer ordered for. */
    @PostMapping("/{orderId}/account")
    @PreAuthorize("hasPermission(null, 'credits.customers.write')")
    public zelisline.ub.wholesale.api.dto.TradeAccountResponse decideAccount(
            @PathVariable String orderId,
            @Valid @RequestBody zelisline.ub.wholesale.api.dto.DecideTradeAccountRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return salesOrderService.decideAccountForOrder(TenantRequestIds.resolveBusinessId(request), orderId, body);
    }

    @PostMapping("/{orderId}/dispatch")
    @PreAuthorize("hasPermission(null, 'storefront.orders.read')")
    public SalesOrderResponse dispatch(@PathVariable String orderId, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return salesOrderService.dispatch(TenantRequestIds.resolveBusinessId(request), orderId);
    }

    @PostMapping("/{orderId}/cancel")
    @PreAuthorize("hasPermission(null, 'storefront.orders.read')")
    public SalesOrderResponse cancel(@PathVariable String orderId, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return salesOrderService.cancel(TenantRequestIds.resolveBusinessId(request), orderId);
    }

    /** A supplier's "This is wrong" appeal on one counted order: posted into its own support chat (D9). */
    @PostMapping("/{orderId}/appeal")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasPermission(null, 'storefront.orders.read')")
    public zelisline.ub.support.api.dto.SupportMessageDto appeal(
            @PathVariable String orderId,
            @Valid @RequestBody zelisline.ub.wholesale.api.dto.AppealSalesOrderRequest body,
            HttpServletRequest request
    ) {
        zelisline.ub.platform.security.TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return supplierAppealService.appeal(businessId, principal.userId(), orderId, body.reason());
    }

    /** Counter orders: a buyer's account terms for the clerk pricing an order. */
    @GetMapping("/counter-terms")
    @PreAuthorize("hasPermission(null, 'grocery.invoices.create')")
    public zelisline.ub.wholesale.api.dto.CounterTermsResponse counterTerms(
            @RequestParam String phone, HttpServletRequest request) {
        return counterOrderService.terms(TenantRequestIds.resolveBusinessId(request), phone);
    }

    /** Counter orders: an owner or admin puts a pending counter invoice on the buyer's account. */
    @PostMapping("/counter-on-account")
    @PreAuthorize("hasPermission(null, 'business.manage_settings')")
    public SalesOrderResponse counterOnAccount(
            @Valid @RequestBody zelisline.ub.wholesale.api.dto.CounterOnAccountRequest body,
            HttpServletRequest request
    ) {
        zelisline.ub.platform.security.TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        return counterOrderService.putOnAccount(
                TenantRequestIds.resolveBusinessId(request), principal.userId(), body.invoiceId());
    }

    /** Counter orders: the clerk records which account an issued invoice was priced for. Prices are re-checked. */
    @PutMapping("/counter-invoices/{invoiceId}/account")
    @PreAuthorize("hasPermission(null, 'grocery.invoices.create')")
    public zelisline.ub.wholesale.api.dto.CounterInvoiceAccountResponse attachCounterAccount(
            @PathVariable String invoiceId,
            @Valid @RequestBody zelisline.ub.wholesale.api.dto.AttachCounterAccountRequest body,
            HttpServletRequest request
    ) {
        return counterOrderService.attachAccount(
                TenantRequestIds.resolveBusinessId(request), invoiceId, body.accountId());
    }

    /** Counter orders: which account, if any, a counter invoice was priced for. */
    @GetMapping("/counter-invoices/{invoiceId}/account")
    @PreAuthorize("hasPermission(null, 'grocery.invoices.read')")
    public zelisline.ub.wholesale.api.dto.CounterInvoiceAccountResponse counterAccount(
            @PathVariable String invoiceId, HttpServletRequest request) {
        return counterOrderService.invoiceAccount(TenantRequestIds.resolveBusinessId(request), invoiceId);
    }
}
