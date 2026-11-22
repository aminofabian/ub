package zelisline.ub.trade.api;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.trade.api.dto.MakePurchaseOrderRequest;
import zelisline.ub.trade.api.dto.RecordReceivedRequest;
import zelisline.ub.trade.api.dto.TradeOrderDecisionRequest;
import zelisline.ub.trade.api.dto.TradeOrderMatchResponse;
import zelisline.ub.trade.api.dto.TradeOrderPurchaseOrderResponse;
import zelisline.ub.trade.api.dto.TradeOrderResponse;
import zelisline.ub.trade.application.TradeOrderService;

/** The buyer's side of the trade lane: orders suppliers sent this shop, to confirm or decline. */
@RestController
@RequestMapping("/api/v1/trade-orders/incoming")
@RequiredArgsConstructor
public class BuyerTradeOrdersController {

    private final TradeOrderService tradeOrderService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'purchasing.path_a.read')")
    public List<TradeOrderResponse> list(
            @RequestParam(required = false) String state,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return tradeOrderService.incoming(TenantRequestIds.resolveBusinessId(request), state);
    }

    @GetMapping("/{tradeOrderId}")
    @PreAuthorize("hasPermission(null, 'purchasing.path_a.read')")
    public TradeOrderResponse get(@PathVariable String tradeOrderId, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return tradeOrderService.incomingOne(TenantRequestIds.resolveBusinessId(request), tradeOrderId);
    }

    @PostMapping("/{tradeOrderId}/decision")
    @PreAuthorize("hasPermission(null, 'purchasing.path_a.write')")
    public TradeOrderResponse decide(
            @PathVariable String tradeOrderId,
            @Valid @RequestBody TradeOrderDecisionRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return tradeOrderService.decide(
                TenantRequestIds.resolveBusinessId(request), tradeOrderId, "confirm".equals(body.decision()));
    }

    /** What arrived, for every line. A shortage marks the order disputed. */
    @PostMapping("/{tradeOrderId}/received")
    @PreAuthorize("hasPermission(null, 'purchasing.path_a.write')")
    public TradeOrderResponse received(
            @PathVariable String tradeOrderId,
            @Valid @RequestBody RecordReceivedRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return tradeOrderService.recordReceived(TenantRequestIds.resolveBusinessId(request), tradeOrderId, body);
    }

    /** Each line with this shop's product of the same barcode, if it has one. */
    @GetMapping("/{tradeOrderId}/matches")
    @PreAuthorize("hasPermission(null, 'purchasing.path_a.read')")
    public List<TradeOrderMatchResponse> matches(@PathVariable String tradeOrderId, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return tradeOrderService.matches(TenantRequestIds.resolveBusinessId(request), tradeOrderId);
    }

    /** Makes this shop's own draft purchase order from a confirmed order. */
    @PostMapping("/{tradeOrderId}/purchase-order")
    @PreAuthorize("hasPermission(null, 'purchasing.path_a.write')")
    public TradeOrderPurchaseOrderResponse makePurchaseOrder(
            @PathVariable String tradeOrderId,
            @Valid @RequestBody(required = false) MakePurchaseOrderRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return tradeOrderService.makePurchaseOrder(
                TenantRequestIds.resolveBusinessId(request), tradeOrderId, body);
    }
}
