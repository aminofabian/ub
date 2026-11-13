package zelisline.ub.credits.sms.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** {@code {variable}} substitution for SMS wording. Unknown variables are reported, not sent. */
public final class SmsTemplates {

    private static final Pattern VARIABLE = Pattern.compile("\\{([A-Za-z_]{1,32})\\}");

    private SmsTemplates() {
    }

    /** Variables in {@code template} that are not in {@code allowed}, in order of appearance. */
    public static List<String> unknownVariables(String template, Set<String> allowed) {
        List<String> unknown = new ArrayList<>();
        if (template == null) {
            return unknown;
        }
        Matcher m = VARIABLE.matcher(template);
        while (m.find()) {
            String name = m.group(1);
            if (!allowed.contains(name) && !unknown.contains(name)) {
                unknown.add(name);
            }
        }
        return unknown;
    }

    public static String render(String template, Map<String, String> values) {
        if (template == null) {
            return "";
        }
        Matcher m = VARIABLE.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? m.group(0) : value));
        }
        m.appendTail(out);
        return out.toString().trim();
    }

    /**
     * The name to greet a customer by, or null when there is none worth using. A till
     * M-Pesa prompt to a new number creates a customer whose "name" is that phone
     * number, so a name without a single letter is treated as no name: nobody should be
     * texted "Hi 254712345678".
     */
    public static String usableName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String trimmed = name.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isLetter(trimmed.charAt(i))) {
                return trimmed;
            }
        }
        return null;
    }

    /** First word of a name, or "there" when the customer has no usable name. */
    public static String firstName(String name) {
        String usable = usableName(name);
        return usable == null ? "there" : usable.split("\\s+")[0];
    }
}
