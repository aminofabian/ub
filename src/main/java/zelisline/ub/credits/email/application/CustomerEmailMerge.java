package zelisline.ub.credits.email.application;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitutes {@code {{tag}}} placeholders in customer email campaigns.
 * Unknown tags are left in place and listed.
 */
public final class CustomerEmailMerge {

    public static final String TAG_NAME = "name";
    public static final String TAG_FIRST_NAME = "firstName";
    public static final String TAG_EMAIL = "email";
    public static final String TAG_PHONE = "phone";
    public static final String TAG_SHOP = "shop";
    public static final String TAG_BUSINESS_NAME = "businessName";
    public static final String TAG_SHOP_URL = "shopUrl";
    public static final String TAG_LOYALTY_POINTS = "loyaltyPoints";
    public static final String TAG_WALLET_BALANCE = "walletBalance";
    public static final String TAG_TAB_BALANCE = "tabBalance";

    public static final Set<String> KNOWN_TAGS = Set.of(
            TAG_NAME,
            TAG_FIRST_NAME,
            TAG_EMAIL,
            TAG_PHONE,
            TAG_SHOP,
            TAG_BUSINESS_NAME,
            TAG_SHOP_URL,
            TAG_LOYALTY_POINTS,
            TAG_WALLET_BALANCE,
            TAG_TAB_BALANCE);

    private static final Pattern TAG = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*\\}\\}");

    private CustomerEmailMerge() {
    }

    public record Context(
            String name,
            String firstName,
            String email,
            String phone,
            String shop,
            String shopUrl,
            BigDecimal walletBalance,
            BigDecimal tabBalance,
            int loyaltyPoints
    ) {
    }

    public record Result(String subject, String body, List<String> unknownTags) {
    }

    public static Result apply(String subject, String body, Context ctx) {
        Set<String> unknown = new LinkedHashSet<>();
        return new Result(replace(subject, ctx, unknown), replace(body, ctx, unknown), List.copyOf(unknown));
    }

    public static List<String> findUnknown(String subject, String body) {
        Set<String> unknown = new LinkedHashSet<>();
        collectUnknown(subject, unknown);
        collectUnknown(body, unknown);
        return List.copyOf(unknown);
    }

    private static void collectUnknown(String template, Set<String> unknown) {
        if (template == null || template.isEmpty()) {
            return;
        }
        Matcher matcher = TAG.matcher(template);
        while (matcher.find()) {
            String key = matcher.group(1);
            if (!KNOWN_TAGS.contains(key)) {
                unknown.add(key);
            }
        }
    }

    static String replace(String template, Context ctx, Set<String> unknown) {
        if (template == null || template.isEmpty()) {
            return template == null ? "" : template;
        }
        Map<String, String> values = Map.ofEntries(
                Map.entry(TAG_NAME, nullToEmpty(ctx.name())),
                Map.entry(TAG_FIRST_NAME, nullToEmpty(ctx.firstName())),
                Map.entry(TAG_EMAIL, nullToEmpty(ctx.email())),
                Map.entry(TAG_PHONE, nullToEmpty(ctx.phone())),
                Map.entry(TAG_SHOP, nullToEmpty(ctx.shop())),
                Map.entry(TAG_BUSINESS_NAME, nullToEmpty(ctx.shop())),
                Map.entry(TAG_SHOP_URL, nullToEmpty(ctx.shopUrl())),
                Map.entry(TAG_LOYALTY_POINTS, String.valueOf(ctx.loyaltyPoints())),
                Map.entry(TAG_WALLET_BALANCE, money(ctx.walletBalance())),
                Map.entry(TAG_TAB_BALANCE, money(ctx.tabBalance()))
        );
        Matcher matcher = TAG.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement;
            if (values.containsKey(key)) {
                replacement = Matcher.quoteReplacement(values.get(key));
            } else {
                unknown.add(key);
                replacement = Matcher.quoteReplacement(matcher.group(0));
            }
            matcher.appendReplacement(out, replacement);
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String money(BigDecimal value) {
        if (value == null) {
            return "0.00";
        }
        return value.stripTrailingZeros().scale() <= 0
                ? value.toPlainString()
                : value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
