package zelisline.ub.kplc.domain;

/**
 * Human labels for Kenya Power token levy codes. Unknown codes keep their
 * identifier so a new EPRA line still shows an amount.
 */
public final class KplcConceptCodes {

    private KplcConceptCodes() {
    }

    public static String label(String code) {
        if (code == null || code.isBlank()) {
            return "Other";
        }
        return switch (code.trim().toUpperCase()) {
            case "RESSTEP0" -> "Lifeline electricity (0–50 kWh)";
            case "RESSTEP1" -> "Electricity (band 1)";
            case "FUEL" -> "Fuel cost adjustment";
            case "FOREX" -> "Foreign exchange adjustment";
            case "LEVY_REA" -> "Rural electrification levy";
            case "LEVY_WARMA" -> "Water resources levy";
            case "LEVY_ERC" -> "EPRA levy";
            case "INFRA" -> "Infrastructure levy";
            case "VAT" -> "VAT";
            default -> code.trim();
        };
    }

    public static String kind(String code) {
        if (code == null || code.isBlank()) {
            return "OTHER";
        }
        String key = code.trim().toUpperCase();
        if (key.startsWith("RESSTEP")) {
            return "ENERGY";
        }
        return switch (key) {
            case "FUEL", "FOREX" -> "ADJUSTMENT";
            case "LEVY_REA", "LEVY_WARMA", "LEVY_ERC", "INFRA" -> "LEVY";
            case "VAT" -> "TAX";
            default -> "OTHER";
        };
    }
}
