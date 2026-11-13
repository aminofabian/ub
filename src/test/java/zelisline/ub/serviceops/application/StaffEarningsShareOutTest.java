package zelisline.ub.serviceops.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Product commission is rounded once per sale and then shared across the sale's product rows. */
class StaffEarningsShareOutTest {

    @Test
    void smallRowsStillAddUpToTheSaleTotal() {
        // 2 % of three 20-shilling rows is 1.2 in total: one shilling, not three zeros.
        BigDecimal twenty = new BigDecimal("20.00");
        List<BigDecimal> shares = StaffEarningsService.shareOut(
                new BigDecimal("1.00"), List.of(twenty, twenty, twenty), new BigDecimal("60.00"));
        assertThat(shares.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("1.00");
        assertThat(shares).allMatch(s -> s.signum() >= 0);
    }

    @Test
    void leftoverShillingsGoToTheRowsThatLostMost() {
        // 5 across bases 100 and 200: 1.67 and 3.33 become 2 and 3.
        List<BigDecimal> shares = StaffEarningsService.shareOut(
                new BigDecimal("5.00"), List.of(new BigDecimal("100"), new BigDecimal("200")), new BigDecimal("300"));
        assertThat(shares.get(0)).isEqualByComparingTo("2.00");
        assertThat(shares.get(1)).isEqualByComparingTo("3.00");
    }

    @Test
    void nothingToShareGivesZeros() {
        BigDecimal twenty = new BigDecimal("20.00");
        assertThat(StaffEarningsService.shareOut(BigDecimal.ZERO, List.of(twenty), twenty).get(0))
                .isEqualByComparingTo("0");
        assertThat(StaffEarningsService.shareOut(new BigDecimal("5"), List.of(BigDecimal.ZERO), BigDecimal.ZERO).get(0))
                .isEqualByComparingTo("0");
    }

    @Test
    void partsAlwaysSumToTheTotal() {
        List<BigDecimal> bases = List.of(new BigDecimal("33.33"), new BigDecimal("17.10"), new BigDecimal("249.57"),
                new BigDecimal("0.00"), new BigDecimal("5.00"));
        BigDecimal baseTotal = bases.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        for (int total = 0; total <= 40; total++) {
            List<BigDecimal> shares = StaffEarningsService.shareOut(new BigDecimal(total), bases, baseTotal);
            assertThat(shares.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(new BigDecimal(total));
            assertThat(shares.get(3)).isEqualByComparingTo("0");
        }
    }
}
