package zelisline.ub.sales.application;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Per-tenant variable-weight barcode layout (EAN-13 prefix-2 style by default).
 */
public record VariableWeightBarcodeConfig(
        boolean enabled,
        char prefixDigit,
        int pluStart,
        int pluLength,
        int valueStart,
        int valueLength,
        EmbeddedField embeddedField,
        WeightUnit weightUnit,
        boolean validateCheckDigit
) {
    public enum EmbeddedField {
        WEIGHT,
        PRICE
    }

    public enum WeightUnit {
        /** Value field is grams; quantity in kg = value / 1000. */
        GRAMS
    }

    public static VariableWeightBarcodeConfig disabled() {
        return new VariableWeightBarcodeConfig(
                false,
                '2',
                1,
                5,
                6,
                5,
                EmbeddedField.WEIGHT,
                WeightUnit.GRAMS,
                true
        );
    }

  /** Standard in-store EAN-13 layout when butcher variable-weight is enabled. */
    public static VariableWeightBarcodeConfig standardEnabled() {
        return new VariableWeightBarcodeConfig(
                true,
                '2',
                1,
                5,
                6,
                5,
                EmbeddedField.WEIGHT,
                WeightUnit.GRAMS,
                true
        );
    }

    public static VariableWeightBarcodeConfig fromBusinessSettings(JsonNode butcherNode) {
        if (butcherNode == null || !butcherNode.isObject()) {
            return disabled();
        }
        JsonNode vw = butcherNode.get("variableWeightBarcode");
        if (vw == null || !vw.isObject()) {
            return disabled();
        }
        JsonNode enabledNode = vw.get("enabled");
        if (enabledNode == null || !enabledNode.asBoolean(false)) {
            return disabled();
        }
        char prefix = '2';
        JsonNode prefixNode = vw.get("prefixDigit");
        if (prefixNode != null && prefixNode.isTextual() && prefixNode.asText().length() == 1) {
            prefix = prefixNode.asText().charAt(0);
        }
        int pluStart = intOrDefault(vw.get("pluStart"), 1);
        int pluLength = intOrDefault(vw.get("pluLength"), 5);
        int valueStart = intOrDefault(vw.get("valueStart"), 6);
        int valueLength = intOrDefault(vw.get("valueLength"), 5);
        EmbeddedField field = EmbeddedField.WEIGHT;
        JsonNode fieldNode = vw.get("embeddedField");
        if (fieldNode != null && fieldNode.isTextual()) {
            String raw = fieldNode.asText().trim().toLowerCase();
            if ("price".equals(raw)) {
                field = EmbeddedField.PRICE;
            }
        }
        WeightUnit weightUnit = WeightUnit.GRAMS;
        JsonNode wu = vw.get("weightUnit");
        if (wu != null && wu.isTextual() && "grams".equalsIgnoreCase(wu.asText().trim())) {
            weightUnit = WeightUnit.GRAMS;
        }
        boolean validateCheck = true;
        JsonNode checkNode = vw.get("validateCheckDigit");
        if (checkNode != null && checkNode.isBoolean()) {
            validateCheck = checkNode.asBoolean();
        }
        return new VariableWeightBarcodeConfig(
                true,
                prefix,
                pluStart,
                pluLength,
                valueStart,
                valueLength,
                field,
                weightUnit,
                validateCheck
        );
    }

    private static int intOrDefault(JsonNode node, int fallback) {
        if (node == null || !node.isNumber()) {
            return fallback;
        }
        return node.asInt(fallback);
    }
}
