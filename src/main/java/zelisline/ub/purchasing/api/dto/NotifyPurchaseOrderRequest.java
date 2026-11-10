package zelisline.ub.purchasing.api.dto;

/**
 * Optional "tell people about this order" step after a purchase order is saved.
 * Everything is opt-in: a recipient is only messaged when its flag is {@code true}.
 *
 * @param notifyAdmin     send the order list to the business's verified ops-alert (admin) number
 * @param adminChannel    {@code whatsapp}, {@code sms} or {@code both} (default {@code whatsapp})
 * @param notifySupplier  send the order list to the supplier's primary contact number
 * @param supplierChannel {@code whatsapp}, {@code sms} or {@code both} (default {@code whatsapp})
 */
public record NotifyPurchaseOrderRequest(
        boolean notifyAdmin,
        String adminChannel,
        boolean notifySupplier,
        String supplierChannel
) {
}
