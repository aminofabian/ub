package zelisline.ub.wholesale.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import zelisline.ub.wholesale.api.dto.SupplierReadinessResponse.Step;

class SupplierOverviewServiceTest {

    @AfterEach
    void clearExtraHolidays() {
        KenyaPublicHolidays.setExtra(Set.of());
    }

    @Test
    void waitingOrdersComeBeforeEverythingElse() {
        var next = SupplierOverviewService.nextThing(3, 2, List.of(new Step("payout", "Say how you are paid", false, "/x")));

        assertThat(next.text()).isEqualTo("3 orders are waiting for your answer");
        assertThat(next.url()).isEqualTo("/trade");
    }

    @Test
    void oneWaitingOrderIsSaidInTheSingular() {
        assertThat(SupplierOverviewService.nextThing(1, 0, List.of()).text())
                .isEqualTo("1 order is waiting for your answer");
    }

    @Test
    void thenOrdersToDispatchThenTheFirstStepNotDone() {
        assertThat(SupplierOverviewService.nextThing(0, 2, List.of()).text())
                .isEqualTo("2 accepted orders are ready to dispatch");

        var next = SupplierOverviewService.nextThing(0, 0, List.of(
                new Step("claim_page", "Claim your page", true, "/a"),
                new Step("payout", "Say how you are paid", false, "/b")));
        assertThat(next.text()).isEqualTo("Say how you are paid");
        assertThat(next.url()).isEqualTo("/b");
    }

    @Test
    void nothingWaitingAndEveryStepDoneMeansNoNextThing() {
        assertThat(SupplierOverviewService.nextThing(0, 0, List.of(new Step("payout", "Say how you are paid", true, "/b"))))
                .isNull();
    }

    @Test
    void aHolidayAddedInConfigurationStopsTheClock() {
        LocalDate day = LocalDate.of(2027, 3, 10);
        assertThat(KenyaPublicHolidays.between(day, day)).doesNotContain(day);

        KenyaPublicHolidays.setExtra(Set.of(day));

        assertThat(KenyaPublicHolidays.between(day, day)).contains(day);
    }
}
