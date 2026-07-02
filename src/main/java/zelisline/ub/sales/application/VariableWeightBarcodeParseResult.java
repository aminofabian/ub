package zelisline.ub.sales.application;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Parsed payload from a variable-weight EAN-13 scale label.
 */
public record VariableWeightBarcodeParseResult(
        String normalizedBarcode,
        String pluCode,
        BigDecimal embeddedWeightKg,
        BigDecimal embeddedPrice,
        VariableWeightBarcodeConfig.EmbeddedField embeddedField
) {
    public boolean hasWeight() {
        return embeddedWeightKg != null;
    }

    public boolean hasPrice() {
        return embeddedPrice != null;
    }
}
