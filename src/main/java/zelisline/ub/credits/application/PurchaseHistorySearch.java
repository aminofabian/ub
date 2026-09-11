package zelisline.ub.credits.application;

import zelisline.ub.credits.api.dto.TabPurchaseLineResponse;
import zelisline.ub.credits.api.dto.TabPurchaseRowResponse;

/**
 * Year-later lookup: match a typed query against a purchase row's receipt
 * and line name / SKU / barcode. Compact codes so {@code F-11301} ≡ {@code f11301}.
 */
public final class PurchaseHistorySearch {

    private PurchaseHistorySearch() {
    }

    public static boolean matches(TabPurchaseRowResponse row, String rawQuery) {
        if (row == null) {
            return false;
        }
        if (rawQuery == null || rawQuery.isBlank()) {
            return true;
        }
        String q = rawQuery.trim().toLowerCase();
        String compactQ = compact(q);
        if (row.receiptNo() != null && String.valueOf(row.receiptNo()).contains(q)) {
            return true;
        }
        if (row.saleId() != null && row.saleId().toLowerCase().contains(q)) {
            return true;
        }
        if (row.lines() == null) {
            return false;
        }
        for (TabPurchaseLineResponse line : row.lines()) {
            if (fieldMatches(line.itemName(), q, compactQ)
                    || fieldMatches(line.itemSku(), q, compactQ)
                    || fieldMatches(line.itemBarcode(), q, compactQ)) {
                return true;
            }
        }
        return false;
    }

    static boolean fieldMatches(String field, String q, String compactQ) {
        if (field == null || field.isBlank()) {
            return false;
        }
        String raw = field.toLowerCase();
        if (raw.contains(q)) {
            return true;
        }
        return compactQ.length() >= 3 && compact(raw).contains(compactQ);
    }

    static String compact(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}
