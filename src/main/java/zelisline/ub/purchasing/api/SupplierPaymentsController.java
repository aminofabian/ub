package zelisline.ub.purchasing.api;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.purchasing.api.dto.ApAgingTotalsResponse;
import zelisline.ub.purchasing.api.dto.OpenSupplierInvoiceRow;
import zelisline.ub.purchasing.api.dto.PostSupplierPaymentRequest;
import zelisline.ub.purchasing.api.dto.PostSupplierPaymentResponse;
import zelisline.ub.purchasing.application.SupplierPaymentService;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/purchasing")
@RequiredArgsConstructor
public class SupplierPaymentsController {

    private final SupplierPaymentService supplierPaymentService;

    @PostMapping("/supplier-payments")
    @PreAuthorize("hasPermission(null, 'purchasing.payment.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public PostSupplierPaymentResponse postPayment(
            @Valid @RequestBody PostSupplierPaymentRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return supplierPaymentService.postPayment(TenantRequestIds.resolveBusinessId(request), body, idempotencyKey);
    }

    @GetMapping("/ap-aging")
    @PreAuthorize("hasPermission(null, 'purchasing.payment.read')")
    public ApAgingTotalsResponse apAging(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(required = false) String supplierId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return supplierPaymentService.apAging(TenantRequestIds.resolveBusinessId(request), asOf, supplierId);
    }

    @GetMapping("/open-supplier-invoices")
    @PreAuthorize("hasPermission(null, 'purchasing.payment.read')")
    public List<OpenSupplierInvoiceRow> openSupplierInvoices(
            @RequestParam(required = false) String supplierId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return supplierPaymentService.listOpenSupplierInvoices(
                TenantRequestIds.resolveBusinessId(request), supplierId);
    }
}
