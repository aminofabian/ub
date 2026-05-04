package zelisline.ub.integrations.webhook;

/** Canonical outbound webhook event identifiers (Phase 8 Slice 2 / implement.md §12). */
public final class WebhookEventTypes {

    public static final String SALE_COMPLETED = "sale.completed";
    public static final String INVOICE_OVERDUE = "invoice.overdue";
    public static final String STOCK_LOW_STOCK = "stock.low_stock";

    private WebhookEventTypes() {
    }

    public static boolean isKnown(String eventType) {
        return SALE_COMPLETED.equals(eventType)
                || INVOICE_OVERDUE.equals(eventType)
                || STOCK_LOW_STOCK.equals(eventType);
    }
}
