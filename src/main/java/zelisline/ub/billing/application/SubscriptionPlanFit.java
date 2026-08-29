package zelisline.ub.billing.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Picks the cheapest published plan that covers a shop's live catalog and
 * staff seats. Limits come from {@code platform_subscription_plans} (null =
 * unlimited). Anything past every published cap is negotiable.
 */
public final class SubscriptionPlanFit {

    public static final String BUYER_ROLE_KEY = "buyer";
    public static final String ENTERPRISE_TIER = "enterprise";

    private SubscriptionPlanFit() {}

    public record PlanSnapshot(
            String tierCode,
            String displayName,
            Integer productLimit,
            Integer cashierLimit,
            int sortOrder,
            BigDecimal monthlyPriceKes
    ) {
        public PlanSnapshot {
            tierCode = tierCode == null ? "" : tierCode.trim().toLowerCase(Locale.ROOT);
            displayName = displayName == null || displayName.isBlank() ? tierCode : displayName.trim();
            monthlyPriceKes = monthlyPriceKes == null ? BigDecimal.ZERO : monthlyPriceKes;
        }
    }

    public record Usage(long productCount, long userCount) {
        public Usage {
            productCount = Math.max(0, productCount);
            userCount = Math.max(0, userCount);
        }
    }

    public record Result(
            Usage usage,
            PlanSnapshot current,
            PlanSnapshot recommended,
            boolean overProductLimit,
            boolean overUserLimit,
            boolean needsUpgrade,
            boolean negotiable,
            boolean talkToUs,
            List<String> reasons
    ) {}

    public static Result evaluate(Usage usage, PlanSnapshot current, List<PlanSnapshot> activePlans) {
        Usage safeUsage = usage == null ? new Usage(0, 0) : usage;
        List<PlanSnapshot> ordered = activePlans == null
                ? List.of()
                : activePlans.stream()
                        .filter(Objects::nonNull)
                        .sorted(Comparator
                                .comparingInt(PlanSnapshot::sortOrder)
                                .thenComparing(PlanSnapshot::tierCode))
                        .toList();

        PlanSnapshot recommended = null;
        for (PlanSnapshot plan : ordered) {
            if (fits(safeUsage, plan)) {
                recommended = plan;
                break;
            }
        }

        boolean currentFits = current != null && fits(safeUsage, current);
        boolean overProduct = current != null && exceeds(safeUsage.productCount(), current.productLimit());
        boolean overUser = current != null && exceeds(safeUsage.userCount(), current.cashierLimit());
        boolean negotiable = recommended == null;
        boolean needsUpgrade = current == null ? recommended != null : !currentFits;
        boolean talkToUs = negotiable || (recommended != null && isEnterprise(recommended.tierCode()));

        return new Result(
                safeUsage,
                current,
                recommended,
                overProduct,
                overUser,
                needsUpgrade,
                negotiable,
                talkToUs,
                reasons(safeUsage, current, recommended, overProduct, overUser, negotiable));
    }

    public static boolean fits(Usage usage, PlanSnapshot plan) {
        if (plan == null || usage == null) {
            return false;
        }
        if (exceeds(usage.productCount(), plan.productLimit())) {
            return false;
        }
        return !exceeds(usage.userCount(), plan.cashierLimit());
    }

    public static boolean exceeds(long used, Integer limit) {
        return limit != null && used > limit;
    }

    public static boolean isEnterprise(String tierCode) {
        return tierCode != null && ENTERPRISE_TIER.equalsIgnoreCase(tierCode.trim());
    }

    public static String blockProductMessage(Result result) {
        PlanSnapshot current = result.current();
        String currentName = current != null ? current.displayName() : "this plan";
        Integer cap = current != null ? current.productLimit() : null;
        String next = nextPlanName(result);
        if (cap == null) {
            return "This shop is past every published catalog size. Talk to us to add more products.";
        }
        return currentName
                + " allows "
                + formatCount(cap)
                + " products. This shop already has "
                + formatCount(result.usage().productCount())
                + ". "
                + next
                + " to add more.";
    }

    public static String blockUserMessage(Result result) {
        PlanSnapshot current = result.current();
        String currentName = current != null ? current.displayName() : "this plan";
        Integer cap = current != null ? current.cashierLimit() : null;
        String next = nextPlanName(result);
        if (cap == null) {
            return "This shop is past every published team size. Talk to us to add more people.";
        }
        return currentName
                + " allows "
                + formatCount(cap)
                + (cap == 1 ? " person" : " people")
                + ". This shop already has "
                + formatCount(result.usage().userCount())
                + ". "
                + next
                + " to add someone.";
    }

    private static String nextPlanName(Result result) {
        if (result.talkToUs() || result.recommended() == null) {
            return "Talk to us";
        }
        return "Switch to " + result.recommended().displayName();
    }

    private static List<String> reasons(
            Usage usage,
            PlanSnapshot current,
            PlanSnapshot recommended,
            boolean overProduct,
            boolean overUser,
            boolean negotiable
    ) {
        List<String> reasons = new ArrayList<>();
        String currentName = current != null ? current.displayName() : "this plan";
        if (overProduct && current != null && current.productLimit() != null) {
            reasons.add(formatCount(usage.productCount())
                    + " products exceed "
                    + currentName
                    + "'s "
                    + formatCount(current.productLimit())
                    + "-product catalog");
        }
        if (overUser && current != null && current.cashierLimit() != null) {
            reasons.add(formatCount(usage.userCount())
                    + (usage.userCount() == 1 ? " person exceeds " : " people exceed ")
                    + currentName
                    + "'s "
                    + formatCount(current.cashierLimit())
                    + "-person team");
        }
        if (negotiable) {
            reasons.add("This shop is past every published plan. A custom quote is the next step.");
        } else if (recommended != null && current != null && !currentFitsName(current, recommended)) {
            reasons.add(recommended.displayName() + " is the plan that fits this shop today.");
        }
        return List.copyOf(reasons);
    }

    private static boolean currentFitsName(PlanSnapshot current, PlanSnapshot recommended) {
        return current.tierCode().equals(recommended.tierCode());
    }

    static String formatCount(long value) {
        return String.format(Locale.US, "%,d", value);
    }
}
