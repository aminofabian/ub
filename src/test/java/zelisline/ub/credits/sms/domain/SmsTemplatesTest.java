package zelisline.ub.credits.sms.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SmsTemplatesTest {

    @Test
    void fillsKnownVariables() {
        String text = SmsTemplates.render(
                "Hi {first_name}, thanks for visiting {shop}.",
                Map.of("first_name", "Wanjiku", "shop", "Palmart"));
        assertThat(text).isEqualTo("Hi Wanjiku, thanks for visiting Palmart.");
    }

    @Test
    void leavesUnknownVariablesVisibleRatherThanBlank() {
        assertThat(SmsTemplates.render("Code {promo}", Map.of())).isEqualTo("Code {promo}");
    }

    @Test
    void valuesContainingDollarOrBackslashAreNotTreatedAsPatterns() {
        assertThat(SmsTemplates.render("{shop}", Map.of("shop", "A$1 \\ B"))).isEqualTo("A$1 \\ B");
    }

    @Test
    void reportsUnknownVariablesOnce() {
        assertThat(SmsTemplates.unknownVariables("{name} {code} {code} {shop}", Set.of("name", "shop")))
                .containsExactly("code");
    }

    @Test
    void phoneNumberStoredAsANameIsNotUsedAsAGreeting() {
        assertThat(SmsTemplates.usableName("254712345678")).isNull();
        assertThat(SmsTemplates.usableName("+254 712 345 678")).isNull();
        assertThat(SmsTemplates.firstName("0712345678")).isEqualTo("there");
        assertThat(SmsTemplates.usableName(" Wanjiku ")).isEqualTo("Wanjiku");
    }

    @Test
    void firstNameFallsBackToThere() {
        assertThat(SmsTemplates.firstName("  Achieng Otieno ")).isEqualTo("Achieng");
        assertThat(SmsTemplates.firstName(" ")).isEqualTo("there");
        assertThat(SmsTemplates.firstName(null)).isEqualTo("there");
    }
}
