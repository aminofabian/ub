package zelisline.ub.finance;

/**
 * Soft taxonomy for OpEx rows / schedules. Maps to ledger sub-accounts under 6000.
 */
public final class ExpenseCategoryCodes {

    public static final String RENT = "rent";
    public static final String UTILITIES = "utilities";
    public static final String SALARIES = "salaries";
    public static final String TRANSPORT = "transport";
    public static final String MAINTENANCE = "maintenance";
    public static final String PACKAGING = "packaging";
    public static final String OTHER = "other";

    private ExpenseCategoryCodes() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim().toLowerCase();
        return switch (v) {
            case RENT, UTILITIES, SALARIES, TRANSPORT, MAINTENANCE, PACKAGING, OTHER -> v;
            default -> throw new IllegalArgumentException(
                    "categoryCode must be rent, utilities, salaries, transport, maintenance, packaging, or other");
        };
    }

    public static String ledgerCodeFor(String categoryCode) {
        if (categoryCode == null || categoryCode.isBlank()) {
            return LedgerAccountCodes.OPERATING_EXPENSES;
        }
        return switch (categoryCode.trim().toLowerCase()) {
            case RENT -> LedgerAccountCodes.EXPENSE_RENT;
            case UTILITIES -> LedgerAccountCodes.EXPENSE_UTILITIES;
            case SALARIES -> LedgerAccountCodes.EXPENSE_SALARIES;
            case TRANSPORT -> LedgerAccountCodes.EXPENSE_TRANSPORT;
            case MAINTENANCE -> LedgerAccountCodes.EXPENSE_MAINTENANCE;
            case PACKAGING, OTHER -> LedgerAccountCodes.EXPENSE_PACKAGING_MISC;
            default -> LedgerAccountCodes.OPERATING_EXPENSES;
        };
    }

    public static String defaultFixedVariable(String categoryCode) {
        if (categoryCode == null) {
            return FinanceConstants.EXPENSE_CATEGORY_VARIABLE;
        }
        return switch (categoryCode) {
            case RENT, SALARIES -> FinanceConstants.EXPENSE_CATEGORY_FIXED;
            default -> FinanceConstants.EXPENSE_CATEGORY_VARIABLE;
        };
    }
}
