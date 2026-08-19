package zelisline.ub.platform.email.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitutes {@code {{tag}}} placeholders. Unknown tags are left in place and listed.
 */
public final class PlatformEmailMerge {

    public static final String TAG_NAME = "name";
    public static final String TAG_EMAIL = "email";
    public static final String TAG_BUSINESS_NAME = "businessName";
    public static final String TAG_SHOP_URL = "shopUrl";
    public static final String TAG_CONTINUE_URL = "continueUrl";

    static final Set<String> KNOWN_TAGS = Set.of(
            TAG_NAME, TAG_EMAIL, TAG_BUSINESS_NAME, TAG_SHOP_URL, TAG_CONTINUE_URL);

    private static final Pattern TAG = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*\\}\\}");

    private PlatformEmailMerge() {
    }

    public record Context(
            String name,
            String email,
            String businessName,
            String shopUrl,
            String continueUrl
    ) {
    }

    public record Result(String subject, String body, List<String> unknownTags) {
    }

    public static Result apply(String subject, String body, Context ctx) {
        Set<String> unknown = new LinkedHashSet<>();
        return new Result(replace(subject, ctx, unknown), replace(body, ctx, unknown), List.copyOf(unknown));
    }

    static String replace(String template, Context ctx, Set<String> unknown) {
        if (template == null || template.isEmpty()) {
            return template == null ? "" : template;
        }
        Map<String, String> values = Map.of(
                TAG_NAME, nullToEmpty(ctx.name()),
                TAG_EMAIL, nullToEmpty(ctx.email()),
                TAG_BUSINESS_NAME, nullToEmpty(ctx.businessName()),
                TAG_SHOP_URL, nullToEmpty(ctx.shopUrl()),
                TAG_CONTINUE_URL, nullToEmpty(ctx.continueUrl())
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

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
