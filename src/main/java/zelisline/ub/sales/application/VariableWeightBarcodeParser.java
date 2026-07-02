package zelisline.ub.sales.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Parses configurable EAN-13 variable-weight scale labels (prefix digit + PLU + value field).
 */
public final class VariableWeightBarcodeParser {

    private VariableWeightBarcodeParser() {
    }

    public static Optional<VariableWeightBarcodeParseResult> parse(
            String rawBarcode,
            VariableWeightBarcodeConfig config
    ) {
        if (config == null || !config.enabled()) {
            return Optional.empty();
        }
        String digits = digitsOnly(rawBarcode);
        if (digits.length() != 13) {
            return Optional.empty();
        }
        if (digits.charAt(0) != config.prefixDigit()) {
            return Optional.empty();
        }
        if (config.validateCheckDigit() && !isValidEan13CheckDigit(digits)) {
            return Optional.empty();
        }
        int pluEnd = config.pluStart() + config.pluLength();
        int valueEnd = config.valueStart() + config.valueLength();
        if (pluEnd > digits.length() || valueEnd > digits.length()) {
            return Optional.empty();
        }
        String plu = digits.substring(config.pluStart(), pluEnd);
        String valueRaw = digits.substring(config.valueStart(), valueEnd);
        int valueInt;
        try {
            valueInt = Integer.parseInt(valueRaw);
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
        if (valueInt <= 0) {
            return Optional.empty();
        }

        BigDecimal embeddedWeightKg = null;
        BigDecimal embeddedPrice = null;
        if (config.embeddedField() == VariableWeightBarcodeConfig.EmbeddedField.WEIGHT) {
            if (config.weightUnit() == VariableWeightBarcodeConfig.WeightUnit.GRAMS) {
                embeddedWeightKg = BigDecimal.valueOf(valueInt)
                        .divide(BigDecimal.valueOf(1000), 4, RoundingMode.HALF_UP);
            }
        } else {
            embeddedPrice = BigDecimal.valueOf(valueInt)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }

        return Optional.of(new VariableWeightBarcodeParseResult(
                digits,
                plu,
                embeddedWeightKg,
                embeddedPrice,
                config.embeddedField()
        ));
    }

    static String digitsOnly(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static boolean isValidEan13CheckDigit(String digits) {
        if (digits.length() != 13) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int d = digits.charAt(i) - '0';
            sum += (i % 2 == 0) ? d : d * 3;
        }
        int check = (10 - (sum % 10)) % 10;
        return check == (digits.charAt(12) - '0');
    }
}
