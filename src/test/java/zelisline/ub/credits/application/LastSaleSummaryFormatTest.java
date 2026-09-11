package zelisline.ub.credits.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

class LastSaleSummaryFormatTest {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    @Test
    void hintIsOneLine() {
        Instant soldAt = LocalDateTime.of(2026, 8, 3, 14, 0).atZone(NAIROBI).toInstant();
        assertThat(LastSaleSummaryFormat.hint(List.of("Super Loaf", "Milk"), soldAt, NAIROBI))
                .isEqualTo("Usually Super Loaf · last 3 Aug");
    }

    @Test
    void missingBasketHasNoHint() {
        assertThat(LastSaleSummaryFormat.hint(List.of(), Instant.now(), NAIROBI)).isNull();
        assertThat(LastSaleSummaryFormat.hint(List.of("Bread"), null, NAIROBI)).isNull();
    }
}
