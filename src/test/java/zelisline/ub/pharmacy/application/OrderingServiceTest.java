package zelisline.ub.pharmacy.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import zelisline.ub.pharmacy.api.dto.PharmacyNetworkDtos.BranchNeed;
import zelisline.ub.pharmacy.application.OrderingService.Lot;

class OrderingServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);

    private static BranchNeed need(String sold, List<Lot> lots, String onOrder, boolean medicine) {
        return OrderingService.need(
                "branch-1", new BigDecimal(sold), lots, new BigDecimal(onOrder), medicine, TODAY, 28, 7, 30);
    }

    private static Lot lot(Integer daysToExpiry, String quantity) {
        return new Lot(daysToExpiry == null ? null : TODAY.plusDays(daysToExpiry), new BigDecimal(quantity));
    }

    private static void assertQty(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    @Test
    void itSuggestsEnoughForLeadTimePlusCoverLessWhatIsHereAndOnOrder() {
        // 56 sold in 28 days is 2 a day. 37 days wanted = 74. 20 on the shelf, 10 on order.
        BranchNeed n = need("56", List.of(lot(400, "20")), "10", false);
        assertQty("2", n.dailyRate());
        assertQty("10", n.daysOfCover());
        assertQty("0", n.expiringFirst());
        assertQty("44", n.suggested());
    }

    @Test
    void stockThatWillExpireBeforeItSellsIsNotCountedAsCover() {
        // 1 a day. 30 units expire in 10 days: 10 will sell, 20 will not.
        BranchNeed n = need("28", List.of(lot(10, "30")), "0", false);
        assertQty("20", n.expiringFirst());
        // 37 wanted, only 10 of the 30 are really available.
        assertQty("27", n.suggested());
    }

    @Test
    void earlierLotsUseUpTheSalesALaterLotCouldHaveHad() {
        // 1 a day. Lot A: 10 expiring in 10 days, all sell. Lot B: 10 expiring in 15 days: only 5 days left for it.
        BranchNeed n = need("28", List.of(lot(15, "10"), lot(10, "10")), "0", false);
        assertQty("5", n.expiringFirst());
    }

    @Test
    void forAMedicineExpiredAndUndatedLotsAreNotStock() {
        List<Lot> lots = List.of(lot(-1, "50"), lot(null, "50"), lot(400, "5"));
        assertQty("5", need("28", lots, "0", true).sellable());
        assertQty("105", need("28", lots, "0", false).sellable());
    }

    @Test
    void nothingSoldMeansNoRateNoCoverFigureAndNothingToOrder() {
        BranchNeed n = need("0", List.of(lot(400, "5")), "0", false);
        assertNull(n.daysOfCover());
        assertQty("0", n.suggested());
    }

    @Test
    void theSuggestionIsRoundedUpToAWholeUnitAndNeverNegative() {
        // 10 in 28 days = 0.357 a day; 37 days = 13.2; nothing in stock.
        assertQty("14", need("10", List.of(), "0", false).suggested());
        assertQty("0", need("10", List.of(lot(400, "500")), "0", false).suggested());
    }
}
