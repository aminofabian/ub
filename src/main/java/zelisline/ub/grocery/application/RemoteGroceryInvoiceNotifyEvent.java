package zelisline.ub.grocery.application;

import java.math.BigDecimal;

/**
 * Published after a remote grocery invoice is created — triggers customer WhatsApp/SMS.
 */
public record RemoteGroceryInvoiceNotifyEvent(
        String businessId,
        String invoiceId,
        String branchId,
        String customerPhone,
        String barcodeCode,
        BigDecimal grandTotal,
        int lineCount
) {
}
