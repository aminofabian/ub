package zelisline.ub.audit;

/**
 * Canonical event type strings for the unified audit log.
 *
 * <p>These are stored in {@code audit_events.event_type} and exposed to clients.
 * Prefer human-readable dotted names grouped by category.
 */
public final class AuditEventTypes {

    private AuditEventTypes() {}

    // Security & access
    public static final String LOGIN_SUCCEEDED = "login.succeeded";
    public static final String LOGIN_FAILED = "login.failed";
    public static final String LOGOUT = "logout";
    public static final String LOGOUT_ALL = "logout.all";
    /** Admin signed another user out of every device from the users console. */
    public static final String USER_SESSIONS_REVOKED = "user.sessions_revoked";
    /** Super-admin opened a short-lived tenant session as a tenant user. */
    public static final String IMPERSONATION_STARTED = "impersonation.started";
    public static final String PASSWORD_CHANGED = "password.changed";
    public static final String PASSWORD_RESET_REQUESTED = "password.reset.requested";
    public static final String PASSWORD_RESET_USED = "password.reset.used";
    public static final String PIN_CHANGED = "pin.changed";
    public static final String PIN_VIEWED = "pin.viewed";
    public static final String ACCOUNT_LOCKED_SOFT = "account.locked.soft";
    public static final String ACCOUNT_LOCKED_HARD = "account.locked.hard";
    public static final String ACCOUNT_UNLOCKED = "account.unlocked";
    public static final String API_KEY_CREATED = "api_key.created";
    public static final String API_KEY_REVOKED = "api_key.revoked";
    public static final String API_KEY_USED = "api_key.used";
    public static final String API_KEY_INVALID = "api_key.invalid";
    public static final String ROLE_CREATED = "role.created";
    public static final String ROLE_UPDATED = "role.updated";
    public static final String ROLE_DELETED = "role.deleted";
    public static final String USER_ROLE_ASSIGNED = "user.role.assigned";
    public static final String PERMISSION_DENIED = "permission.denied";

    // Staff & user management
    public static final String USER_CREATED = "user.created";
    public static final String USER_UPDATED = "user.updated";
    public static final String USER_DEACTIVATED = "user.deactivated";
    public static final String USER_ACTIVATED = "user.activated";
    public static final String USER_ANONYMISED = "user.anonymised";

    // Sales & payments
    public static final String SALE_COMPLETED = "sale.completed";
    public static final String SALE_VOIDED = "sale.voided";
    public static final String REFUND_ISSUED = "refund.issued";
    public static final String PAYMENT_TENDERED = "payment.tendered";
    public static final String PAYMENT_ADJUSTED = "payment.adjusted";
    public static final String PAYMENT_GATEWAY_WEBHOOK_RECEIVED = "payment.gateway.webhook.received";
    public static final String STK_PUSH_INITIATED = "stk_push.initiated";
    public static final String STK_PUSH_COMPLETED = "stk_push.completed";
    public static final String STK_PUSH_FAILED = "stk_push.failed";

    // Cash drawer & shifts
    public static final String SHIFT_OPENED = "shift.opened";
    public static final String SHIFT_SUSPENDED = "shift.suspended";
    public static final String SHIFT_RESUMED = "shift.resumed";
    public static final String SHIFT_CLOSED = "shift.closed";
    /** Owner/admin corrected opening float on an open shift. */
    public static final String SHIFT_OPENING_UPDATED = "shift.opening_updated";
    public static final String CASH_PAID_IN = "cash.paid_in";
    public static final String CASH_PAID_OUT = "cash.paid_out";
    public static final String CASH_DROP = "cash.drop";
    public static final String CASH_SALE_ADDED = "cash.sale_added";
    public static final String CASH_REFUND_REMOVED = "cash.refund_removed";
    public static final String CASH_VOID_REMOVED = "cash.void_removed";
    public static final String CASH_PAYMENT_ADJUSTED = "cash.payment_adjusted";
    public static final String VARIANCE_APPROVED = "variance.approved";
    public static final String DRAWOUT_INITIATED = "drawout.initiated";
    public static final String DRAWOUT_APPROVED = "drawout.approved";
    public static final String DRAWOUT_REJECTED = "drawout.rejected";
    public static final String DRAWOUT_VOIDED = "drawout.voided";
    public static final String DRAWOUT_EXPIRED = "drawout.expired";

    // Inventory & stock
    public static final String STOCK_RECEIVED = "stock.received";
    public static final String STOCK_ADJUSTED = "stock.adjusted";
    public static final String STOCK_TRANSFERRED_OUT = "stock.transferred_out";
    public static final String STOCK_TRANSFERRED_IN = "stock.transferred_in";
    public static final String STOCK_TRANSFER_CANCELLED = "stock.transfer_cancelled";
    public static final String STOCK_WASTED = "stock.wasted";
    public static final String BATCH_CLEARED = "batch.cleared";
    public static final String STOCK_TAKE_OPENED = "stock_take.opened";
    public static final String STOCK_TAKE_CLOSED = "stock_take.closed";
    public static final String STOCK_COUNT_SUBMITTED = "stock_count.submitted";
    public static final String STOCK_COUNT_EDITED = "stock_count.edited";

    // Orders & fulfillment
    public static final String ORDER_CREATED = "order.created";
    public static final String ORDER_PAID = "order.paid";
    public static final String ORDER_PAYMENT_FAILED = "order.payment_failed";
    public static final String ORDER_CONFIRMED = "order.confirmed";
    public static final String ORDER_DISPATCHED = "order.dispatched";
    public static final String ORDER_COMPLETED = "order.completed";
    public static final String ORDER_CANCELLED = "order.cancelled";
    public static final String ORDER_RETURNED = "order.returned";
    public static final String POS_DRAFT_CREATED = "pos_draft.created";
    public static final String POS_DRAFT_UPDATED = "pos_draft.updated";
    public static final String POS_DRAFT_CANCELLED = "pos_draft.cancelled";
    public static final String POS_DRAFT_COMPLETED = "pos_draft.completed";

    // Customers & loyalty
    public static final String CUSTOMER_CREATED = "customer.created";
    public static final String CUSTOMER_UPDATED = "customer.updated";
    public static final String CUSTOMER_CREDIT_LIMIT_CHANGED = "customer.credit_limit_changed";
    public static final String CUSTOMER_DELETED = "customer.deleted";
    public static final String CUSTOMER_CREDIT_TRANSACTION = "customer.credit_transaction";
    public static final String CUSTOMER_WALLET_TRANSACTION = "customer.wallet_transaction";
    public static final String CUSTOMER_LOYALTY_TRANSACTION = "customer.loyalty_transaction";

    // Products & pricing
    public static final String ITEM_CREATED = "item.created";
    public static final String ITEM_UPDATED = "item.updated";
    public static final String ITEM_DELETED = "item.deleted";
    public static final String GLOBAL_PRODUCT_PROMOTED = "global_product.promoted";
    public static final String GLOBAL_PRODUCT_PUBLISHED = "global_product.published";
    public static final String GLOBAL_PRODUCT_IMAGE_UPLOADED = "global_product.image_uploaded";
    /**
     * Intentional non-POS barcode scan (catalog, stock-take, missing-barcodes).
     * Not emitted for every cashier POS lookup.
     */
    public static final String ITEM_SCANNED = "item.scanned";
    public static final String SELLING_PRICE_CHANGED = "selling_price.changed";
    public static final String BUYING_PRICE_CHANGED = "buying_price.changed";
    public static final String TAX_RATE_CHANGED = "tax_rate.changed";
    /** Admin correction of an item's unit cost (rewrites active batch costs + reference cost). */
    public static final String ITEM_COST_ADJUSTED = "item.cost.adjusted";
    public static final String DISCOUNT_CREATED = "discount.created";
    public static final String DISCOUNT_PUBLISHED = "discount.published";
    public static final String DISCOUNT_UPDATED = "discount.updated";
    public static final String DISCOUNT_PAUSED = "discount.paused";
    public static final String DISCOUNT_RESUMED = "discount.resumed";
    public static final String DISCOUNT_EXPIRED = "discount.expired";
    public static final String DISCOUNT_DELETED = "discount.deleted";
    public static final String DISCOUNT_DUPLICATED = "discount.duplicated";

    // Suppliers
    public static final String SUPPLIER_CREATED = "supplier.created";
    public static final String SUPPLIER_UPDATED = "supplier.updated";
    public static final String SUPPLIER_DELETED = "supplier.deleted";
    public static final String SUPPLIER_CONTACT_ADDED = "supplier.contact_added";
    public static final String SUPPLIER_CONTACT_UPDATED = "supplier.contact_updated";
    public static final String SUPPLIER_CONTACT_DELETED = "supplier.contact_deleted";
    public static final String SUPPLIER_CLAIMED_ACCOUNT = "supplier.claimed_account";
    public static final String SUPPLIER_PORTAL_INVITE_CREATED = "supplier.portal_invite_created";
    public static final String SUPPLIER_PORTAL_USER_SUSPENDED = "supplier.portal_user_suspended";
    public static final String SUPPLIER_PORTAL_USER_UNSUSPENDED = "supplier.portal_user_unsuspended";
    public static final String SUPPLIER_PORTAL_PASSWORD_RESET = "supplier.portal_password_reset";
    public static final String SUPPLIER_PORTAL_FORCE_LOGOUT = "supplier.portal_force_logout";
    public static final String SUPPLIER_PORTAL_USER_UNLOCKED = "supplier.portal_user_unlocked";
    public static final String SUPPLIER_PORTAL_PAYMENT_DETAILS_UPDATED = "supplier.portal_payment_details_updated";
    public static final String SUPPLIER_MARKETPLACE_SUSPENDED = "supplier.marketplace_suspended";

    // System & integrations
    public static final String SCHEDULER_STARTED = "scheduler.started";
    public static final String SCHEDULER_COMPLETED = "scheduler.completed";
    public static final String SCHEDULER_FAILED = "scheduler.failed";
    public static final String WEBHOOK_DELIVERED = "webhook.delivered";
    public static final String WEBHOOK_FAILED = "webhook.failed";
    public static final String IMPORT_JOB_COMPLETED = "import_job.completed";
    public static final String IMPORT_JOB_FAILED = "import_job.failed";
    public static final String BACKUP_COMPLETED = "backup.completed";
    public static final String BACKUP_FAILED = "backup.failed";
    public static final String SYSTEM_EXCEPTION = "system.exception";

    // Onboarding / regional catalog funnel
    public static final String ONBOARDING_COUNTRY_SELECTED = "onboarding.country_selected";
    public static final String ONBOARDING_VERTICAL_SELECTED = "onboarding.vertical_selected";
    public static final String CATALOG_RESOLVED = "catalog.resolved";
    public static final String CATALOG_PACK_ADOPTED = "catalog.pack_adopted";
    public static final String BUSINESS_REGION_CHANGED = "business.region_changed";
}
