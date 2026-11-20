package zelisline.ub.pharmacy.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class PharmacyLicenceReminderServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);

    @Test
    void nothingIsDueWhenTheLicenceIsFarOffOrHasNoDate() {
        assertNull(PharmacyLicenceReminderService.stage(null, TODAY));
        assertNull(PharmacyLicenceReminderService.stage(TODAY.plusDays(61), TODAY));
    }

    @Test
    void theFirstReminderCoversSixtyDaysDownToFifteen() {
        assertEquals(PharmacyLicenceReminderService.STAGE_60, PharmacyLicenceReminderService.stage(TODAY.plusDays(60), TODAY));
        assertEquals(PharmacyLicenceReminderService.STAGE_60, PharmacyLicenceReminderService.stage(TODAY.plusDays(15), TODAY));
    }

    @Test
    void theSecondReminderCoversTheLastFortnightIncludingTheDayItself() {
        assertEquals(PharmacyLicenceReminderService.STAGE_14, PharmacyLicenceReminderService.stage(TODAY.plusDays(14), TODAY));
        assertEquals(PharmacyLicenceReminderService.STAGE_14, PharmacyLicenceReminderService.stage(TODAY, TODAY));
    }

    @Test
    void aPassedDateIsFlaggedForAMonthThenLeftToTheOverview() {
        assertEquals(PharmacyLicenceReminderService.STAGE_EXPIRED, PharmacyLicenceReminderService.stage(TODAY.minusDays(1), TODAY));
        assertEquals(PharmacyLicenceReminderService.STAGE_EXPIRED, PharmacyLicenceReminderService.stage(TODAY.minusDays(30), TODAY));
        assertNull(PharmacyLicenceReminderService.stage(TODAY.minusDays(31), TODAY));
    }
}
