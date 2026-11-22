package zelisline.ub.wholesale.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A sales invoice as the supplier's shop sees it. {@code receipts} is absent in a list. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SalesInvoiceResponse(
        String id,
        String invoiceNo,
        String salesOrderId,
        String status,
        String currency,
        BigDecimal total,
        BigDecimal amountPaid,
        BigDecimal balance,
        LocalDate issuedOn,
        LocalDate dueOn,
        String buyerName,
        String buyerBusinessName,
        String buyerPhone,
        List<Receipt> receipts,
        /** Taken off what is owed by credit notes. */
        BigDecimal amountCredited
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Receipt(String id, BigDecimal amount, String method, String reference, LocalDate receivedOn) {
    }
}
