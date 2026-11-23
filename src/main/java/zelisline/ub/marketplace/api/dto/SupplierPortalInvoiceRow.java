package zelisline.ub.marketplace.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record SupplierPortalInvoiceRow(
        String invoiceId,
        String businessId,
        String businessName,
        String invoiceNumber,
        LocalDate invoiceDate,
        LocalDate dueDate,
        BigDecimal subtotal,
        BigDecimal taxTotal,
        BigDecimal grandTotal,
        BigDecimal amountPaid,
        BigDecimal balanceOpen,
        String paymentStatus,
        String status,
        List<Line> lines
) {

    /** What the shop's invoice says was supplied. Quantities are base stock units. */
    public record Line(
            String id,
            String description,
            BigDecimal qty,
            BigDecimal unitCost,
            BigDecimal lineTotal,
            String purchaseUnit,
            BigDecimal purchaseQty,
            BigDecimal unitsPerPurchaseUnit,
            BigDecimal packPrice
    ) {
    }
}

