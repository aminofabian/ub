package zelisline.ub.desktop.api;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.desktop.application.DesktopTradeActionService;
import zelisline.ub.wholesale.api.dto.AnswerSalesOrderRequest;
import zelisline.ub.wholesale.api.dto.SalesOrderResponse;
import zelisline.ub.wholesale.api.dto.SalesInvoiceResponse;
import zelisline.ub.wholesale.api.dto.DecideTradeAccountRequest;
import zelisline.ub.wholesale.api.dto.RecordSalesReceiptRequest;
import zelisline.ub.wholesale.application.SalesInvoiceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import zelisline.ub.wholesale.application.SalesOrderService;

/**
 * Desktop till only. A supplier answers, dispatches or cancels a mirrored order here. The action is
 * queued and the mirror's status moves at once; the online shop applies it for real on the next sync.
 * Returns the order in the same shape the web endpoints do, so the UI flow is identical.
 * See DESKTOP_SUPPLIER_TRADE_SYNC_SCOPE.md §S2.
 */
@RestController
@RequestMapping("/api/v1/desktop/trade")
@Profile("desktop")
@RequiredArgsConstructor
public class DesktopTradeController {

    private final DesktopTradeActionService actionService;
    private final SalesOrderService salesOrderService;
    private final SalesInvoiceService salesInvoiceService;
    private final ObjectMapper objectMapper;

    @PostMapping("/orders/{orderId}/answer")
    @PreAuthorize("hasPermission(null, 'storefront.orders.read')")
    public SalesOrderResponse answer(
            @PathVariable String orderId,
            @Valid @RequestBody AnswerSalesOrderRequest body,
            HttpServletRequest request) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        List<DesktopTradeActionService.AnswerLine> lines = body.lines().stream()
            .map(l -> new DesktopTradeActionService.AnswerLine(l.lineId(), l.acceptedQty()))
            .toList();
        actionService.recordAnswer(businessId, orderId, actorId(request), lines);
        return salesOrderService.get(businessId, orderId);
    }

    @PostMapping("/orders/{orderId}/dispatch")
    @PreAuthorize("hasPermission(null, 'storefront.orders.read')")
    public SalesOrderResponse dispatch(@PathVariable String orderId, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        actionService.recordDispatch(businessId, orderId, actorId(request));
        return salesOrderService.get(businessId, orderId);
    }

    @PostMapping("/orders/{orderId}/cancel")
    @PreAuthorize("hasPermission(null, 'storefront.orders.read')")
    public SalesOrderResponse cancel(@PathVariable String orderId, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        actionService.recordCancel(businessId, orderId, actorId(request));
        return salesOrderService.get(businessId, orderId);
    }

    @PostMapping("/accounts/{accountId}/decision")
    @PreAuthorize("hasPermission(null, 'credits.customers.write')")
    public void decideAccount(
            @PathVariable String accountId,
            @Valid @RequestBody DecideTradeAccountRequest body,
            HttpServletRequest request) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        actionService.recordApproveAccount(businessId, accountId, actorId(request), toJson(body));
    }

    @PostMapping("/invoices/{invoiceId}/receipts")
    @PreAuthorize("hasPermission(null, 'credits.customers.write')")
    public SalesInvoiceResponse recordReceipt(
            @PathVariable String invoiceId,
            @Valid @RequestBody RecordSalesReceiptRequest body,
            HttpServletRequest request) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        actionService.recordPayment(businessId, invoiceId, actorId(request), toJson(body));
        return salesInvoiceService.get(businessId, invoiceId);
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "Could not save your change");
        }
    }

    private static String actorId(HttpServletRequest request) {
        try {
            return CurrentTenantUser.requireHuman(request).userId();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
