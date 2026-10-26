package zelisline.ub.domainoffer.domain;

import java.util.Locale;

/** The four directions the merchant can ask for without knowing theme ids. */
public enum DomainOfferThemeDirection {
    WARM_MARKET("Warm market", "Paper, produce, hand-written signs. The neighbourhood shop."),
    CLEAN_MODERN("Clean and modern", "Quiet type, white space, a calm grid."),
    BOLD_LOUD("Bold and loud", "Big type, strong colour, a shop that shouts."),
    DESIGNER_CHOICE("Kiosk designer's pick", "Tell us the shop and we will choose the look.");

    private final String label;
    private final String hint;

    DomainOfferThemeDirection(String label, String hint) {
        this.label = label;
        this.hint = hint;
    }

    public String label() {
        return label;
    }

    public String hint() {
        return hint;
    }

    public static DomainOfferThemeDirection fromWire(String raw) {
        if (raw == null || raw.isBlank()) {
            return DESIGNER_CHOICE;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (DomainOfferThemeDirection value : values()) {
            if (value.name().equals(normalized)) {
                return value;
            }
        }
        return null;
    }
}
