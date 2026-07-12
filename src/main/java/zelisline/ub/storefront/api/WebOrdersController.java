package zelisline.ub.storefront.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
import zelisline.ub.storefront.api.dto.UpdateWebOrderFulfillmentRequest;
import zelisline.ub.storefront.api.dto.WebOrderDetailResponse;
import zelisline.ub.storefront.api.dto.WebOrderPickupTicketClaimResponse;
import zelisline.ub.storefront.api.dto.WebOrderSummaryResponse;
import zelisline.ub.storefront.application.WebOrderAdminService;
import zelisline.ub.storefront.application.WebOrderFulfillmentService;
import zelisline.ub.storefront.application.WebOrderReceiptService;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/web-orders")
@RequiredArgsConstructor
public class WebOrdersController {

    private final WebOrderAdminService webOrderAdminService;
    private final WebOrderFulfillmentService webOrderFulfillmentService;
    private final WebOrderReceiptService webOrderReceiptService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'storefront.orders.read')")
    public Page<WebOrderSummaryResponse> list(
            @PageableDefault(size = 50) Pageable pageable,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return webOrderAdminService.pageOrders(TenantRequestIds.resolveBusinessId(request), pageable);
    }

    @GetMapping("/{orderId}")
    @PreAuthorize("hasPermission(null, 'storefront.orders.read')")
    public WebOrderDetailResponse detail(@PathVariable String orderId, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return webOrderAdminService.getOrder(TenantRequestIds.resolveBusinessId(request), orderId.trim());
    }

    /**
     * ESC/POS pickup ticket for thermal printers. Cashiers with {@code sales.sell} can print
     * without needing storefront admin read permission.
     */
    @GetMapping("/{orderId}/receipt/thermal")
    @PreAuthorize("hasPermission(null, 'sales.sell') or hasPermission(null, 'storefront.orders.read')")
    public ResponseEntity<byte[]> receiptThermal(
            @PathVariable String orderId,
            @RequestParam(defaultValue = "80") int widthMm,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        byte[] body = webOrderReceiptService.buildEscPos(
                TenantRequestIds.resolveBusinessId(request),
                orderId.trim(),
                widthMm
        );
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=web-order-" + orderId + ".bin")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(body);
    }

    /**
     * Claim a one-time cashier auto-print of the pickup ticket.
     * Returns {@code claimed=false} when already printed or the order is older than 1 hour.
     */
    @PostMapping("/{orderId}/pickup-ticket/claim")
    @PreAuthorize("hasPermission(null, 'sales.sell') or hasPermission(null, 'storefront.orders.read')")
    public WebOrderPickupTicketClaimResponse claimPickupTicket(
            @PathVariable String orderId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        return webOrderReceiptService.claimPickupTicketPrint(
                TenantRequestIds.resolveBusinessId(request),
                orderId.trim()
        );
    }

    @PatchMapping("/{orderId}/fulfillment")
    @PreAuthorize("hasPermission(null, 'storefront.orders.read')")
    public WebOrderDetailResponse updateFulfillment(
            @PathVariable String orderId,
            @Valid @RequestBody UpdateWebOrderFulfillmentRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return webOrderFulfillmentService.advance(
                TenantRequestIds.resolveBusinessId(request),
                orderId.trim(),
                body.fulfillmentStatus());
    }
}
