package zelisline.ub.sales.api;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.sales.api.dto.AdjustSalePaymentsRequest;
import zelisline.ub.sales.api.dto.PostRefundRequest;
import zelisline.ub.sales.api.dto.PostSaleRequest;
import zelisline.ub.sales.api.dto.PostVoidSaleRequest;
import zelisline.ub.sales.api.dto.PosTopProductResponse;
import zelisline.ub.sales.api.dto.RefundResponse;
import zelisline.ub.sales.api.dto.SaleResponse;
import zelisline.ub.sales.application.PosTopProductsService;
import zelisline.ub.sales.application.SalePaymentAdjustService;
import zelisline.ub.sales.application.SaleRefundService;
import zelisline.ub.sales.application.SaleService;
import zelisline.ub.sales.application.SaleVoidService;
import zelisline.ub.sales.application.VariableWeightBarcodeService;
import zelisline.ub.sales.api.dto.VariableWeightBarcodeLookupResponse;
import zelisline.ub.sales.receipt.SaleReceiptService;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.tenancy.application.BranchResolutionService;

@Validated
@RestController
@RequestMapping("/api/v1/sales")
@RequiredArgsConstructor
public class SalesController {

    private final SaleService saleService;
    private final SaleVoidService saleVoidService;
    private final SaleRefundService saleRefundService;
    private final SalePaymentAdjustService salePaymentAdjustService;
    private final SaleReceiptService saleReceiptService;
    private final BranchResolutionService branchResolutionService;
    private final VariableWeightBarcodeService variableWeightBarcodeService;
    private final PosTopProductsService posTopProductsService;

    /**
     * Server-aggregated top sellers for the POS catalog. Ranks items by units
     * sold (sum of quantities) across completed, non-voided sales at the
     * resolved branch.
     *
     * <p>{@code branchId} defaults to the caller's assigned branch when
     * omitted; {@code limit} clamps to 1..100 and defaults to 20.</p>
     */
    @GetMapping("/top-products")
    @PreAuthorize("hasPermission(null, 'sales.sell')")
    public List<PosTopProductResponse> topProducts(
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false) String itemTypeId,
            @RequestParam(required = false, defaultValue = "20") int limit,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String effectiveBranch = branchResolutionService.resolveEffectiveBranch(
                businessId, branchId, principal.roleId());
        String resolvedBranch = effectiveBranch != null
                ? effectiveBranch
                : principal.branchId();
        if (resolvedBranch == null) {
            return List.of();
        }
        return posTopProductsService.topProductsForBranch(
                businessId, resolvedBranch, itemTypeId, limit);
    }

    @GetMapping("/variable-weight-barcode")
    @PreAuthorize("hasPermission(null, 'sales.sell')")
    public VariableWeightBarcodeLookupResponse lookupVariableWeightBarcode(
            @RequestParam @NotBlank String barcode,
            @RequestParam @NotBlank String branchId,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String validatedBranch = branchResolutionService.requireBranchForLockedRole(
                principal.roleId(), principal.branchId(), branchId);
        return variableWeightBarcodeService.lookupForPos(
                TenantRequestIds.resolveBusinessId(request),
                validatedBranch,
                barcode
        );
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'sales.sell')")
    public ResponseEntity<SaleResponse> createSale(
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody PostSaleRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String validatedBranch = branchResolutionService.requireBranchForLockedRole(
                principal.roleId(), principal.branchId(), body.branchId());
        // Replace request branch with validated one
        PostSaleRequest safe = new PostSaleRequest(
                validatedBranch,
                body.lines(),
                body.payments(),
                body.clientSoldAt(),
                body.customerId(),
                body.cashReceived());
        var out = saleService.createSale(
                TenantRequestIds.resolveBusinessId(request),
                idempotencyKey,
                safe,
                principal.userId(),
                principal.roleId()
        );
        HttpStatus status = out.createdNew() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(out.response());
    }

    @GetMapping("/{saleId}")
    @PreAuthorize("hasPermission(null, 'sales.intelligence.read') or hasPermission(null, 'sales.payment.adjust') or hasPermission(null, 'sales.sell')")
    public SaleResponse getSale(@PathVariable String saleId, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return saleService.requireSale(TenantRequestIds.resolveBusinessId(request), saleId);
    }

    @PatchMapping("/{saleId}/payments")
    @PreAuthorize("hasPermission(null, 'sales.payment.adjust')")
    public SaleResponse adjustSalePayments(
            @PathVariable String saleId,
            @Valid @RequestBody AdjustSalePaymentsRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return salePaymentAdjustService.adjustPayments(
                TenantRequestIds.resolveBusinessId(request),
                saleId,
                body,
                user.userId()
        );
    }

    @PostMapping("/{saleId}/void")
    @PreAuthorize("hasPermission(null, 'sales.void.any') or hasPermission(null, 'sales.void.own')")
    public SaleResponse voidSale(
            @PathVariable String saleId,
            @Valid @RequestBody(required = false) PostVoidSaleRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
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
        var user = CurrentTenantUser.requireHuman(request);
        return saleRefundService.createRefund(
                TenantRequestIds.resolveBusinessId(request),
                saleId,
                idempotencyKey,
                body,
                user.userId(),
                user.roleId()
        );
    }

    @GetMapping("/{saleId}/receipt.pdf")
    @PreAuthorize("hasPermission(null, 'sales.sell')")
    public ResponseEntity<byte[]> receiptPdf(@PathVariable String saleId, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
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
            @RequestParam(required = false) BigDecimal cashReceived,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        byte[] body = saleReceiptService.buildEscPos(
                TenantRequestIds.resolveBusinessId(request),
                saleId,
                widthMm,
                cashReceived
        );
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=receipt-" + saleId + ".bin")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(body);
    }
}
