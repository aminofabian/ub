package zelisline.ub.desktop.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/** Supplier invoices (with their receipts and credit notes) mirrored cloud -> till (S3, pull only). Upsert by id. */
public record SalesInvoiceSyncSnapshot(@Valid List<InvoiceData> invoices) {

    public record InvoiceData(
            @NotBlank String id,
            @NotBlank String businessId,
            String salesOrderId,
            String invoiceNo,
            String status,
            String currency,
            BigDecimal total,
            BigDecimal amountPaid,
            BigDecimal amountCredited,
            BigDecimal costOfGoods,
            LocalDate issuedOn,
            LocalDate dueOn,
            String buyerName,
            String buyerBusinessName,
            String buyerPhone,
            Instant createdAt,
            Instant updatedAt,
            @Valid List<ReceiptData> receipts,
            @Valid List<CreditNoteData> creditNotes
    ) {}

    public record ReceiptData(
            @NotBlank String id,
            BigDecimal amount,
            String method,
            String reference,
            LocalDate receivedOn,
            Instant createdAt
    ) {}

    public record CreditNoteData(
            @NotBlank String id,
            BigDecimal amount,
            String reason,
            Instant createdAt
    ) {}
}
