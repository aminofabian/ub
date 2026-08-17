package zelisline.ub.sales.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

class CustomerSpendMathTest {

    @Test
    void titleCasesShoutedMpesaNames() {
        assertThat(CustomerSpendMath.displayName("JOHN", "DOE", "JOHN DOE")).isEqualTo("John Doe");
        assertThat(CustomerSpendMath.displayName("Wanjiku", "Kamau", "Wanjiku Kamau"))
                .isEqualTo("Wanjiku Kamau");
        assertThat(CustomerSpendMath.displayName(null, null, "Amina")).isEqualTo("Amina");
    }

    @Test
    void weekStreakCountsConsecutiveWeeksAndBreaksAfterASkip() {
        LocalDate asOf = LocalDate.of(2026, 8, 17); // Monday
        List<LocalDate> visits = List.of(
                LocalDate.of(2026, 8, 17),
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 3),
                LocalDate.of(2026, 7, 27));

        CustomerSpendMath.Rhythm rhythm = CustomerSpendMath.rhythm(visits, asOf);

        assertThat(rhythm.visitDays()).isEqualTo(4);
        assertThat(rhythm.weekStreak()).isEqualTo(4);
        assertThat(rhythm.longestWeekStreak()).isEqualTo(4);
        assertThat(rhythm.favoriteWeekday()).isEqualTo("Monday");
        assertThat(rhythm.cadence()).isEqualTo("Weekly");
    }

    @Test
    void currentStreakIsZeroWhenLastVisitIsTwoWeeksAgo() {
        LocalDate asOf = LocalDate.of(2026, 8, 17);
        List<LocalDate> visits = List.of(
                LocalDate.of(2026, 8, 3),
                LocalDate.of(2026, 7, 27),
                LocalDate.of(2026, 7, 20));

        CustomerSpendMath.Rhythm rhythm = CustomerSpendMath.rhythm(visits, asOf);

        assertThat(rhythm.weekStreak()).isZero();
        assertThat(rhythm.longestWeekStreak()).isEqualTo(3);
    }

    @Test
    void lastWeekStillCountsAsALiveStreak() {
        LocalDate asOf = LocalDate.of(2026, 8, 17);
        TreeSet<Long> weeks = new TreeSet<>(CustomerSpendMath.weeksOf(List.of(
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 3))));
        assertThat(CustomerSpendMath.currentWeekStreak(
                weeks,
                CustomerSpendMath.weekIndex(LocalDate.of(2026, 8, 10)),
                CustomerSpendMath.weekIndex(asOf))).isEqualTo(2);
    }

    @Test
    void cohortMarksNewDormantAndAtRisk() {
        LocalDate asOf = LocalDate.of(2026, 8, 17);
        assertThat(CustomerSpendMath.cohort(
                1, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 10), asOf))
                .isEqualTo("new_face");
        assertThat(CustomerSpendMath.cohort(
                5, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 1), asOf))
                .isEqualTo("dormant");
        assertThat(CustomerSpendMath.cohort(
                4, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 28), asOf))
                .isEqualTo("at_risk");
        assertThat(CustomerSpendMath.cohort(
                6, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 15), asOf))
                .isEqualTo("regular");
    }

    @Test
    void championsAreTheRecentTopFifth() {
        LocalDate asOf = LocalDate.of(2026, 8, 17);
        List<CustomerSpendMath.SpendRank> ranks = List.of(
                new CustomerSpendMath.SpendRank("a", new BigDecimal("5000.00"), 5, asOf),
                new CustomerSpendMath.SpendRank("b", new BigDecimal("400.00"), 5, asOf),
                new CustomerSpendMath.SpendRank("c", new BigDecimal("300.00"), 5, asOf),
                new CustomerSpendMath.SpendRank("d", new BigDecimal("200.00"), 5, asOf),
                new CustomerSpendMath.SpendRank("e", new BigDecimal("100.00"), 5, asOf));

        Set<String> champions = CustomerSpendMath.championIds(ranks, asOf);

        assertThat(champions).containsExactly("a");
        assertThat(CustomerSpendMath.sharePct(new BigDecimal("5000.00"), new BigDecimal("6000.00")))
                .isEqualByComparingTo("83.3");
    }
}
