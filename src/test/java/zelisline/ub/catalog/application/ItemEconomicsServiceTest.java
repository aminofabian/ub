package zelisline.ub.catalog.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import zelisline.ub.catalog.api.dto.ItemEconomicsDayPoint;

class ItemEconomicsServiceTest {

    @Test
    void daySeriesFillsEmptyDaysAndBucketsNairobiCalendar() {
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 3);
        var points = List.of(
                new Object[] {
                        LocalDate.of(2026, 9, 1).atTime(23, 30).atZone(ItemEconomicsService.SHOP_ZONE).toInstant(),
                        new BigDecimal("2"),
                        new BigDecimal("100")
                },
                new Object[] {
                        LocalDate.of(2026, 9, 3).atStartOfDay(ItemEconomicsService.SHOP_ZONE).toInstant(),
                        new BigDecimal("1"),
                        new BigDecimal("40")
                },
                new Object[] {
                        LocalDate.of(2026, 9, 3).atTime(18, 0).atZone(ItemEconomicsService.SHOP_ZONE).toInstant(),
                        new BigDecimal("3"),
                        new BigDecimal("120")
                }
        );

        List<ItemEconomicsDayPoint> series = ItemEconomicsService.buildDaySeries(points, from, to);

        assertEquals(3, series.size());
        assertEquals(from, series.get(0).date());
        assertEquals(new BigDecimal("2"), series.get(0).unitsSold());
        assertEquals(BigDecimal.ZERO, series.get(1).unitsSold());
        assertEquals(new BigDecimal("4"), series.get(2).unitsSold());
        assertEquals(new BigDecimal("160"), series.get(2).revenue());
    }
}
