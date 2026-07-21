package zelisline.ub.credits;

public final class CreditClaimSources {

    public static final String PUBLIC = "public";
    public static final String CASHIER = "cashier";
    /** Customer reported an off-band payment from the public tab portal ({@code /0712345678}). */
    public static final String TAB_PORTAL = "tab_portal";

    private CreditClaimSources() {
    }
}
