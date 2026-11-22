package zelisline.ub.wholesale.api;

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
import zelisline.ub.wholesale.api.dto.IssueCreditNoteRequest;
import zelisline.ub.wholesale.api.dto.ReceivablesAgeingResponse;
import zelisline.ub.wholesale.api.dto.RecordSalesReceiptRequest;
import zelisline.ub.wholesale.api.dto.SalesInvoiceResponse;
import zelisline.ub.wholesale.application.SalesInvoiceService;

/** The signed-in shop's sales invoices and what buyers owe. Uses the customer-credit permissions. */
@RestController
@RequestMapping("/api/v1/sales-invoices")
@RequiredArgsConstructor
public class SalesInvoicesController {

    private final SalesInvoiceService salesInvoiceService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'credits.customers.read')")
    public List<SalesInvoiceResponse> list(
            @RequestParam(defaultValue = "false") boolean unpaid,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return salesInvoiceService.list(TenantRequestIds.resolveBusinessId(request), unpaid);
    }

    /**
     * The shop's invoices as a CSV file, for its accountant or another system (P4, data out).
     * A cell that a spreadsheet would run as a formula is made plain text.
     */
    @GetMapping(value = "/export.csv", produces = "text/csv")
    @PreAuthorize("hasPermission(null, 'credits.customers.read')")
    public org.springframework.http.ResponseEntity<String> exportCsv(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        StringBuilder csv = new StringBuilder(
                "Invoice,Issued,Due,Buyer,Phone,Currency,Total,Received,Credited,Owed,Status\r\n");
        for (SalesInvoiceResponse i : salesInvoiceService.list(TenantRequestIds.resolveBusinessId(request), false)) {
            csv.append(String.join(",",
                    cell(i.invoiceNo()),
                    cell(String.valueOf(i.issuedOn())),
                    cell(String.valueOf(i.dueOn())),
                    cell(i.buyerBusinessName() != null ? i.buyerBusinessName() : i.buyerName()),
                    cell(i.buyerPhone()),
                    cell(i.currency()),
                    cell(i.total().toPlainString()),
                    cell(i.amountPaid().toPlainString()),
                    cell(i.amountCredited() == null ? "0" : i.amountCredited().toPlainString()),
                    cell(i.balance().toPlainString()),
                    cell(i.status())))
                    .append("\r\n");
        }
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"sales-invoices.csv\"")
                .header("Cache-Control", "no-store")
                .body(csv.toString());
    }

    private static String cell(String raw) {
        String value = raw == null ? "" : raw;
        // A spreadsheet treats a leading = + - @ as a formula; a quote in front makes it text.
        if (!value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0) {
            value = "'" + value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    @GetMapping("/ageing")
    @PreAuthorize("hasPermission(null, 'credits.customers.read')")
    public ReceivablesAgeingResponse ageing(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return salesInvoiceService.ageing(TenantRequestIds.resolveBusinessId(request));
    }

    @GetMapping("/{invoiceId}")
    @PreAuthorize("hasPermission(null, 'credits.customers.read')")
    public SalesInvoiceResponse get(@PathVariable String invoiceId, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return salesInvoiceService.get(TenantRequestIds.resolveBusinessId(request), invoiceId);
    }

    @PostMapping("/{invoiceId}/receipts")
    @PreAuthorize("hasPermission(null, 'credits.customers.write')")
    public SalesInvoiceResponse recordReceipt(
            @PathVariable String invoiceId,
            @Valid @RequestBody RecordSalesReceiptRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return salesInvoiceService.recordReceipt(TenantRequestIds.resolveBusinessId(request), invoiceId, body);
    }

    /** Takes money off what is owed: a shortage, a return, a price put right. */
    @PostMapping("/{invoiceId}/credit-notes")
    @PreAuthorize("hasPermission(null, 'credits.customers.write')")
    public SalesInvoiceResponse creditNote(
            @PathVariable String invoiceId,
            @Valid @RequestBody IssueCreditNoteRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return salesInvoiceService.issueCreditNote(
                TenantRequestIds.resolveBusinessId(request), invoiceId, body.amount(), body.reason());
    }
}
