package zelisline.ub.grocery.application;

import java.math.BigDecimal;
import java.util.List;

import zelisline.ub.messaging.application.CreditSaleReminderLineItem;

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
        int lineCount,
        List<CreditSaleReminderLineItem> items
) {
}
