package zelisline.ub.integrations.whatsapp.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Kenya-first normalization used when matching a typed number against the platform WABA. */
class MetaWhatsAppNumberResolverTest {

    @Test
    void normalizesLocalAndInternationalKenyanNumbersToTheSameDigits() {
        assertThat(MetaWhatsAppNumberResolver.normalize("+254 712 345 678")).isEqualTo("254712345678");
        assertThat(MetaWhatsAppNumberResolver.normalize("254712345678")).isEqualTo("254712345678");
        assertThat(MetaWhatsAppNumberResolver.normalize("0712345678")).isEqualTo("254712345678");
        assertThat(MetaWhatsAppNumberResolver.normalize("712345678")).isEqualTo("254712345678");
        assertThat(MetaWhatsAppNumberResolver.normalize("+254-712-345-678")).isEqualTo("254712345678");
    }

    @Test
    void leavesNonKenyanNumbersAsPlainDigits() {
        assertThat(MetaWhatsAppNumberResolver.normalize("1 (555) 123-4567")).isEqualTo("15551234567");
    }

    @Test
    void rejectsBlankInput() {
        assertThat(MetaWhatsAppNumberResolver.normalize(null)).isNull();
        assertThat(MetaWhatsAppNumberResolver.normalize("   ")).isNull();
        assertThat(MetaWhatsAppNumberResolver.normalize("+")).isNull();
    }
}
