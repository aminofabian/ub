package zelisline.ub.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class SalesDayBandsTest {

    @Test
    void aDayUnderFiveThousandSaysNothing() {
        assertTrue(SalesDayBands.crossed(new BigDecimal("4999.99"), new BigDecimal("100")).isEmpty());
    }

    @Test
    void crossingOneStepAnnouncesThatStep() {
        assertEquals(List.of(new BigDecimal("5000")), SalesDayBands.crossed(new BigDecimal("6400"), new BigDecimal("2000")));
    }

    @Test
    void aSaleThatCrossesTwoStepsAnnouncesBoth() {
        assertEquals(
                List.of(new BigDecimal("5000"), new BigDecimal("10000")),
                SalesDayBands.crossed(new BigDecimal("11000"), new BigDecimal("7000")));
    }

    @Test
    void aLeapPastFourStepsAnnouncesOnlyWhereItLanded() {
        assertEquals(List.of(new BigDecimal("30000")), SalesDayBands.crossed(new BigDecimal("31000"), new BigDecimal("31000")));
    }

    @Test
    void aLaterSaleDoesNotRepeatAStepAlreadyPassed() {
        assertTrue(SalesDayBands.crossed(new BigDecimal("12000"), new BigDecimal("500")).isEmpty());
    }
}
