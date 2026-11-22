package zelisline.ub.trade.api;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.trade.api.dto.ConnectedShopResponse;
import zelisline.ub.trade.api.dto.SendToShopRequest;
import zelisline.ub.trade.api.dto.TradeOrderResponse;
import zelisline.ub.trade.application.TradeOrderService;

/** The supplier's side of the trade lane: send an accepted order to a shop it is connected to. */
@RestController
@RequestMapping("/api/v1/trade-orders/outgoing")
@RequiredArgsConstructor
public class SupplierTradeOrdersController {

    private final TradeOrderService tradeOrderService;

    @GetMapping("/connected-shops")
    @PreAuthorize("hasPermission(null, 'storefront.orders.read')")
    public List<ConnectedShopResponse> connectedShops(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return tradeOrderService.connectedShops(TenantRequestIds.resolveBusinessId(request));
    }

    @PostMapping("/{salesOrderId}")
    @PreAuthorize("hasPermission(null, 'storefront.orders.read')")
    public TradeOrderResponse send(
            @PathVariable String salesOrderId,
            @Valid @RequestBody SendToShopRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return tradeOrderService.sendToShop(
                TenantRequestIds.resolveBusinessId(request), salesOrderId, body.buyerBusinessId());
    }

    /** The supplier has dealt with what the shop disputed. */
    @PostMapping("/{salesOrderId}/resolve")
    @PreAuthorize("hasPermission(null, 'storefront.orders.read')")
    public TradeOrderResponse resolve(@PathVariable String salesOrderId, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return tradeOrderService.resolveDispute(TenantRequestIds.resolveBusinessId(request), salesOrderId);
    }

    @GetMapping("/{salesOrderId}")
    @PreAuthorize("hasPermission(null, 'storefront.orders.read')")
    public TradeOrderResponse sent(@PathVariable String salesOrderId, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return tradeOrderService.forSalesOrder(TenantRequestIds.resolveBusinessId(request), salesOrderId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "This order has not been sent to a shop"));
    }
}
