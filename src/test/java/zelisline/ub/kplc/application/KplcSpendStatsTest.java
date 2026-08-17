package zelisline.ub.kplc.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import zelisline.ub.kplc.api.dto.PublicKplcSpendStatsResponse;
import zelisline.ub.kplc.api.dto.PublicKplcTokenResponse;

class KplcSpendStatsTest {

    @Test
    void groupsByNairobiMonthAndPutsNewestFirst() {
        Instant august = LocalDateTime.of(2026, 8, 16, 23, 26).atZone(KplcSpendStats.NAIROBI).toInstant();
        Instant july = LocalDateTime.of(2026, 7, 7, 12, 10).atZone(KplcSpendStats.NAIROBI).toInstant();
        Instant now = LocalDateTime.of(2026, 8, 17, 14, 0).atZone(KplcSpendStats.NAIROBI).toInstant();

        PublicKplcSpendStatsResponse stats = KplcSpendStats.from(List.of(
                token(august, "300", "10.7", "1111"),
                token(august, "500", "17.7", "2222"),
                token(july, "500", "17.8", "3333")
        ), now);

        assertEquals(2, stats.months().size());
        assertEquals("2026-08", stats.months().get(0).yearMonth());
        assertEquals("August 2026", stats.months().get(0).label());
        assertThat(stats.months().get(0).amount()).isEqualByComparingTo("800");
        assertEquals(2, stats.months().get(0).tokenCount());
        assertEquals("2026-07", stats.months().get(1).yearMonth());
        assertThat(stats.thisMonthAmount()).isEqualByComparingTo("800");
        assertEquals(2, stats.thisMonthCount());
        assertThat(stats.allTimeAmount()).isEqualByComparingTo("1300");
        assertEquals(3, stats.allTimeCount());
    }

    @Test
    void identity_stripsSpacesAndTruncatesToSeconds() {
        assertEquals("37456605149858621649", KplcTokenIdentity.normalizeTokenNo("3745 6605 1498 5862 1649"));
        assertNull(KplcTokenIdentity.normalizeTokenNo(""));
        Instant exact = Instant.parse("2026-08-16T20:26:25.122Z");
        assertEquals(Instant.parse("2026-08-16T20:26:25Z"), KplcTokenIdentity.matchInstant(exact));
    }

    private static PublicKplcTokenResponse token(Instant at, String amount, String units, String tokenNo) {
        return new PublicKplcTokenResponse(
                at,
                new BigDecimal(amount),
                new BigDecimal(units),
                tokenNo,
                null,
                "Cash",
                List.of());
    }
}
