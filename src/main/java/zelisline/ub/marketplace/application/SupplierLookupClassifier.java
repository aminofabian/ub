package zelisline.ub.marketplace.application;

/**
 * Classifies a single free-text lookup into name / phone / supplier-number keys
 * so the UI can use one combined field.
 */
public final class SupplierLookupClassifier {

    private SupplierLookupClassifier() {
    }

    public record ClassifiedLookup(String name, String phone, String supplierNumber) {
        public boolean isEmpty() {
            return (name == null || name.isBlank())
                    && (phone == null || phone.isBlank())
                    && (supplierNumber == null || supplierNumber.isBlank());
        }
    }

    public static ClassifiedLookup classify(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ClassifiedLookup(null, null, null);
        }
        String trimmed = raw.trim();
        if (SupplierNumberFormat.looksLikeSupplierNumber(trimmed)) {
            return new ClassifiedLookup(null, null, SupplierNumberFormat.normalize(trimmed));
        }
        String digits = trimmed.replaceAll("[^0-9]", "");
        boolean mostlyPhone = trimmed.matches("^[\\d\\s+().-]+$") && digits.length() >= 9;
        if (mostlyPhone) {
            return new ClassifiedLookup(null, trimmed, null);
        }
        return new ClassifiedLookup(trimmed, null, null);
    }
}
