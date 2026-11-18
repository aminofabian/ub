package zelisline.ub.payments.application;

/**
 * Safaricom Daraja AccountReference / BillRef helpers (≈12 char cap on many shortcodes).
 */
public final class DarajaAccountReferences {

    public static final int MAX_LEN = 12;

    private DarajaAccountReferences() {
    }

    /** Canonical storefront order code (8 hex) — also used as Paybill Account Number. */
    public static String forWebOrder(String orderId) {
        return truncate(zelisline.ub.storefront.WebOrderCodes.code(orderId), MAX_LEN);
    }

    /**
     * Grocery barcode without punctuation so {@code GI-XXXXXXXXXX} (13) fits in 12 chars
     * as {@code GIXXXXXXXXXX}.
     */
    public static String forGroceryBarcode(String barcodeCode) {
        if (barcodeCode == null || barcodeCode.isBlank()) {
            return "GROCERY";
        }
        String compact = barcodeCode.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        return truncate(compact, MAX_LEN);
    }

    /** Room-deposit reference {@code RM} + compacted stay id, trimmed to 12 chars (ADR 0031). */
    public static String forRoomDeposit(String stayId) {
        if (stayId == null || stayId.isBlank()) {
            return "ROOMDEPOSIT";
        }
        return truncate("RM" + stayId.replaceAll("[^A-Za-z0-9]", "").toUpperCase(), MAX_LEN);
    }

    public static boolean groceryBarcodeMatches(String billRef, String barcodeCode) {
        if (billRef == null || billRef.isBlank() || barcodeCode == null) {
            return false;
        }
        String a = billRef.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        String b = barcodeCode.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        if (a.equals(b)) {
            return true;
        }
        // Truncated AccountReference is a prefix of the compact barcode
        return b.startsWith(a) || a.startsWith(truncate(b, MAX_LEN));
    }

    public static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value == null ? "" : value;
        }
        return value.substring(0, max);
    }
}
