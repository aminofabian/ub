package zelisline.ub.purchasing.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.purchasing.api.dto.AddPathAPurchaseOrderLineRequest;
import zelisline.ub.purchasing.api.dto.CreatePathAPurchaseOrderRequest;
import zelisline.ub.purchasing.api.dto.PathAPurchaseOrderDetailResponse;
import zelisline.ub.purchasing.api.dto.PathAPurchaseOrderLineResponse;
import zelisline.ub.purchasing.api.dto.PostGoodsReceiptRequest;
import zelisline.ub.purchasing.api.dto.PostGoodsReceiptResponse;
import zelisline.ub.purchasing.api.dto.PostGrnSupplierInvoiceRequest;
import zelisline.ub.purchasing.api.dto.PostGrnSupplierInvoiceResponse;
import zelisline.ub.purchasing.application.PathAPurchaseService;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/purchasing/path-a")
@RequiredArgsConstructor
public class PathAPurchasingController {

    private final PathAPurchaseService pathAPurchaseService;

    @PostMapping("/purchase-orders")
    @PreAuthorize("hasPermission(null, 'purchasing.path_a.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public PathAPurchaseOrderDetailResponse createPurchaseOrder(
            @Valid @RequestBody CreatePathAPurchaseOrderRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return pathAPurchaseService.createPurchaseOrder(TenantRequestIds.resolveBusinessId(request), body);
    }

    @GetMapping("/purchase-orders/{purchaseOrderId}")
    @PreAuthorize("hasPermission(null, 'purchasing.path_a.read')")
    public PathAPurchaseOrderDetailResponse getPurchaseOrder(
            @PathVariable String purchaseOrderId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return pathAPurchaseService.getPurchaseOrder(TenantRequestIds.resolveBusinessId(request), purchaseOrderId);
    }

    @PostMapping("/purchase-orders/{purchaseOrderId}/lines")
    @PreAuthorize("hasPermission(null, 'purchasing.path_a.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public PathAPurchaseOrderLineResponse addLine(
            @PathVariable String purchaseOrderId,
            @Valid @RequestBody AddPathAPurchaseOrderLineRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return pathAPurchaseService.addPurchaseOrderLine(
                TenantRequestIds.resolveBusinessId(request),
                purchaseOrderId,
                body
        );
    }

    @PostMapping("/purchase-orders/{purchaseOrderId}/send")
    @PreAuthorize("hasPermission(null, 'purchasing.path_a.write')")
    public PathAPurchaseOrderDetailResponse send(
            @PathVariable String purchaseOrderId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return pathAPurchaseService.sendPurchaseOrder(TenantRequestIds.resolveBusinessId(request), purchaseOrderId);
    }

    @PostMapping("/purchase-orders/{purchaseOrderId}/cancel")
    @PreAuthorize("hasPermission(null, 'purchasing.path_a.write')")
    public PathAPurchaseOrderDetailResponse cancel(
            @PathVariable String purchaseOrderId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return pathAPurchaseService.cancelPurchaseOrder(TenantRequestIds.resolveBusinessId(request), purchaseOrderId);
    }

    @PostMapping("/goods-receipts")
    @PreAuthorize("hasPermission(null, 'purchasing.path_a.write')")
    public PostGoodsReceiptResponse postGoodsReceipt(
            @Valid @RequestBody PostGoodsReceiptRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return pathAPurchaseService.postGoodsReceipt(
                TenantRequestIds.resolveBusinessId(request),
                body,
                idempotencyKey
        );
    }

    @PostMapping("/goods-receipts/{goodsReceiptId}/supplier-invoice")
    @PreAuthorize("hasPermission(null, 'purchasing.path_a.write')")
    public PostGrnSupplierInvoiceResponse postSupplierInvoice(
            @PathVariable String goodsReceiptId,
            @Valid @RequestBody PostGrnSupplierInvoiceRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return pathAPurchaseService.postSupplierInvoiceForGrn(
                TenantRequestIds.resolveBusinessId(request),
                goodsReceiptId,
                body,
                idempotencyKey
        );
    }
}
