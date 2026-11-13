package zelisline.ub.messaging.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SmsSegmentsTest {

    @Test
    void plainTextFitsOnePartUpTo160Characters() {
        assertThat(SmsSegments.count("a".repeat(160))).isEqualTo(1);
        assertThat(SmsSegments.count("a".repeat(161))).isEqualTo(2);
        assertThat(SmsSegments.count("a".repeat(306))).isEqualTo(2);
        assertThat(SmsSegments.count("a".repeat(307))).isEqualTo(3);
    }

    @Test
    void emptyOrNullStillCostsOnePart() {
        assertThat(SmsSegments.count("")).isEqualTo(1);
        assertThat(SmsSegments.count(null)).isEqualTo(1);
    }

    @Test
    void emojiSwitchesToUnicodeLimits() {
        String emoji = "Sale today 🎉";
        assertThat(SmsSegments.isGsm7(emoji)).isFalse();
        assertThat(SmsSegments.count("é".repeat(70))).isEqualTo(1);
        assertThat(SmsSegments.count("ش".repeat(70))).isEqualTo(1);
        assertThat(SmsSegments.count("ش".repeat(71))).isEqualTo(2);
        assertThat(SmsSegments.count("ش".repeat(134))).isEqualTo(2);
        assertThat(SmsSegments.count("ش".repeat(135))).isEqualTo(3);
    }

    @Test
    void extendedGsmCharactersCountTwice() {
        assertThat(SmsSegments.isGsm7("KES 100 [offer]")).isTrue();
        assertThat(SmsSegments.units("[]")).isEqualTo(4);
        // 80 brackets = 160 units: still one part; one more character tips it over.
        assertThat(SmsSegments.count("[".repeat(80))).isEqualTo(1);
        assertThat(SmsSegments.count("[".repeat(80) + "a")).isEqualTo(2);
    }

    @Test
    void swahiliAndCurrencyTextIsPlainGsm() {
        assertThat(SmsSegments.isGsm7("Habari Wanjiku, karibu tena! Bei mpya KES 1,250.")).isTrue();
    }
}
