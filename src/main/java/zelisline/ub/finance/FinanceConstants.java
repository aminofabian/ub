package zelisline.ub.finance;

public final class FinanceConstants {

    public static final String JOURNAL_SOURCE_EXPENSE = "expense";

    public static final String EXPENSE_CATEGORY_FIXED = "fixed";
    public static final String EXPENSE_CATEGORY_VARIABLE = "variable";

    public static final String EXPENSE_PAY_METHOD_CASH = "cash";
    public static final String EXPENSE_PAY_METHOD_MPESA_MANUAL = "mpesa_manual";
    public static final String EXPENSE_PAY_METHOD_BANK = "bank";

    public static final String EXPENSE_FREQUENCY_DAILY = "daily";
    public static final String EXPENSE_FREQUENCY_WEEKLY = "weekly";
    public static final String EXPENSE_FREQUENCY_MONTHLY = "monthly";

    public static final String OCCURRENCE_STATUS_POSTED = "posted";
    public static final String OCCURRENCE_STATUS_FAILED = "failed";
    public static final String OCCURRENCE_STATUS_SKIPPED = "skipped";
    public static final String OCCURRENCE_STATUS_DUE = "due";
    public static final String OCCURRENCE_STATUS_UPCOMING = "upcoming";

    public static final String AUTOMATION_MODE_AUTO_POST = "auto_post";
    public static final String AUTOMATION_MODE_REMIND = "remind";

    private FinanceConstants() {
    }
}

