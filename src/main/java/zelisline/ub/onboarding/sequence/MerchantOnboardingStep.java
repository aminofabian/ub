package zelisline.ub.onboarding.sequence;

/** Step keys for the merchant onboarding message sequence (brief M0–W). */
public enum MerchantOnboardingStep {
    M0_WELCOME("M0", "account.welcome", null),
    M1_FILL_SHELF("M1", "onboarding.fill_shelf", "/products/catalog"),
    M2_SIZES("M2", "onboarding.sizes_right", "/products"),
    M3_MONEY_LOOP("M3", "onboarding.money_loop", "/suppliers"),
    M4_FIRST_SALE("M4", "onboarding.first_sale", "/shifts"),
    M4_FALLBACK("M4_FALLBACK", "onboarding.reengage", "/products/catalog"),
    M5_GO_LIVE("M5", "onboarding.go_live", "/business/settings"),
    M6_TEAM("M6", "onboarding.team_rhythm", "/users"),
    W_WEEK_CHECKIN("W", "onboarding.week_checkin", "/business"),
    N1_LOOKALIKE("N1", "onboarding.lookalike", "/products"),
    N2_CLOSE_SHIFT("N2", "onboarding.close_shift", "/shifts"),
    N4_WEB_ORDER("N4", "onboarding.web_order", "/storefront/web-orders");

    private final String key;
    private final String notificationType;
    private final String actionUrl;

    MerchantOnboardingStep(String key, String notificationType, String actionUrl) {
        this.key = key;
        this.notificationType = notificationType;
        this.actionUrl = actionUrl;
    }

    public String key() {
        return key;
    }

    public String notificationType() {
        return notificationType;
    }

    public String actionUrl() {
        return actionUrl;
    }

    public static MerchantOnboardingStep fromKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (MerchantOnboardingStep step : values()) {
            if (step.key.equalsIgnoreCase(raw.trim())) {
                return step;
            }
        }
        return null;
    }
}
