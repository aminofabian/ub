package zelisline.ub.platform.persistence;

import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Classifies {@link DataIntegrityViolationException} messages from MySQL/MariaDB/H2.
 */
public final class DataIntegrityProblems {

    private DataIntegrityProblems() {
    }

    public static String message(DataIntegrityViolationException ex) {
        return String.valueOf(ex.getMostSpecificCause().getMessage()) + " " + ex.getMessage();
    }

    public static boolean isDuplicateSku(DataIntegrityViolationException ex) {
        String m = message(ex).toLowerCase(Locale.ROOT);
        return m.contains("uq_items_business_sku")
                || m.contains("business_sku")
                || (m.contains("duplicate") && m.contains("sku"));
    }

    public static boolean isDuplicateCustomerPhone(DataIntegrityViolationException ex) {
        String m = message(ex).toLowerCase(Locale.ROOT);
        return m.contains("uq_customer_phones_business_phone");
    }

    public static boolean isDuplicateBarcode(DataIntegrityViolationException ex) {
        String m = message(ex).toLowerCase(Locale.ROOT);
        return m.contains("barcode") && m.contains("duplicate");
    }

    public static boolean isDuplicateSku(ResponseStatusException ex) {
        return ex.getStatusCode().value() == 409
                && "SKU already in use".equals(ex.getReason());
    }

    public static boolean isDuplicateBarcode(ResponseStatusException ex) {
        return ex.getStatusCode().value() == 409
                && "Barcode already in use".equals(ex.getReason());
    }
}
