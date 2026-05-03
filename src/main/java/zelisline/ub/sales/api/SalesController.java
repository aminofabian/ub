package zelisline.ub.sales.api;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.sales.api.dto.PostRefundRequest;
import zelisline.ub.sales.api.dto.PostSaleRequest;
import zelisline.ub.sales.api.dto.PostVoidSaleRequest;
import zelisline.ub.sales.api.dto.RefundResponse;
import zelisline.ub.sales.api.dto.SaleResponse;
import zelisline.ub.sales.application.SaleRefundService;
import zelisline.ub.sales.application.SaleService;
import zelisline.ub.sales.application.SaleVoidService;
import zelisline.ub.sales.receipt.SaleReceiptService;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/sales")
@RequiredArgsConstructor
public class SalesController {

    private final SaleService saleService;
    private final SaleVoidService saleVoidService;
    private final SaleRefundService saleRefundService;
    private final SaleReceiptService saleReceiptService;

    @PostMapping
    @PreAuthorize("hasPermission(null, 'sales.sell')")
    public ResponseEntity<SaleResponse> createSale(
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody PostSaleRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.require(request);
        var out = saleService.createSale(
                TenantRequestIds.resolveBusinessId(request),
                idempotencyKey,
                body,
                user.userId()
        );
        HttpStatus status = out.createdNew() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(out.response());
    }

    @PostMapping("/{saleId}/void")
    @PreAuthorize("hasPermission(null, 'sales.void.any') or hasPermission(null, 'sales.void.own')")
    public SaleResponse voidSale(
            @PathVariable String saleId,
            @Valid @RequestBody(required = false) PostVoidSaleRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.require(request);
        PostVoidSaleRequest payload = body != null ? body : new PostVoidSaleRequest(null);
        return saleVoidService.voidSale(
                TenantRequestIds.resolveBusinessId(request),
                saleId,
                user.userId(),
                user.roleId(),
                payload
        );
    }

    @PostMapping("/{saleId}/refund")
    @PreAuthorize("hasPermission(null, 'sales.refund.create')")
    public RefundResponse refundSale(
            @PathVariable String saleId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody PostRefundRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.require(request);
        return saleRefundService.createRefund(
                TenantRequestIds.resolveBusinessId(request),
                saleId,
                idempotencyKey,
                body,
                user.userId()
        );
    }

    @GetMapping("/{saleId}/receipt.pdf")
    @PreAuthorize("hasPermission(null, 'sales.sell')")
    public ResponseEntity<byte[]> receiptPdf(@PathVariable String saleId, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        byte[] body = saleReceiptService.buildPdf(
                TenantRequestIds.resolveBusinessId(request),
                saleId
        );
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=receipt-" + saleId + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(body);
    }

    @GetMapping("/{saleId}/receipt/thermal")
    @PreAuthorize("hasPermission(null, 'sales.sell')")
    public ResponseEntity<byte[]> receiptThermal(
            @PathVariable String saleId,
            @RequestParam(defaultValue = "58") int widthMm,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        byte[] body = saleReceiptService.buildEscPos(
                TenantRequestIds.resolveBusinessId(request),
                saleId,
                widthMm
        );
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=receipt-" + saleId + ".bin")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(body);
    }
}
