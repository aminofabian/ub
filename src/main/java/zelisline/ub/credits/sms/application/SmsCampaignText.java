package zelisline.ub.credits.sms.application;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import zelisline.ub.credits.sms.domain.SmsTemplates;

/**
 * Builds the final text of a promotional SMS: the shop's wording with variables filled
 * in, followed by the opt-out line every promotion must carry. The opt-out wording is
 * a platform setting because it depends on how the SMS provider receives replies
 * (a short code or two-way number); confirm it with the provider.
 */
@Component
public class SmsCampaignText {

    private final String optOutFooter;

    public SmsCampaignText(@Value("${app.sms-hub.opt-out-footer:Reply STOP to opt out}") String optOutFooter) {
        this.optOutFooter = optOutFooter == null ? "" : optOutFooter.trim();
    }

    public String optOutFooter() {
        return optOutFooter;
    }

    public List<String> unknownVariables(String body) {
        return SmsTemplates.unknownVariables(body, SmsHubSettingsService.CUSTOMER_VARIABLES);
    }

    public String render(String body, String customerName, String shopName) {
        String usable = SmsTemplates.usableName(customerName);
        String name = usable == null ? "there" : usable;
        String text = SmsTemplates.render(body, Map.of(
                "name", name,
                "first_name", SmsTemplates.firstName(customerName),
                "shop", shopName == null ? "" : shopName));
        return withFooter(text);
    }

    private String withFooter(String text) {
        if (optOutFooter.isEmpty() || text.toUpperCase(Locale.ROOT).contains("STOP")) {
            return text;
        }
        String separator = text.endsWith(".") || text.endsWith("!") || text.endsWith("?") ? " " : ". ";
        return text + separator + optOutFooter;
    }
}
