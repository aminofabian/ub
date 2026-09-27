package zelisline.ub.sales.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import zelisline.ub.sales.api.dto.SalesByHourResponse;
import zelisline.ub.sales.api.dto.SalesByHourResponse.SalesHourRow;
import zelisline.ub.sales.api.dto.SalesByHourResponse.SalesHourSale;

/**
 * Groups completed sales into business-local clock hours and fills the quiet
 * hours between the first and last sale so 7–8, 8–9, … stay a continuous tape.
 */
public final class HourlySalesBuckets {

    /** Receipts listed under one hour. The hour's count and revenue still include the rest. */
    public static final int DETAIL_LIMIT = 80;

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private HourlySalesBuckets() {
    }

    public record Stamp(
            String saleId,
            Long receiptNo,
            Instant soldAt,
            String cashierName,
            String paymentMethod,
            BigDecimal amount
    ) {
    }

    public static SalesByHourResponse build(List<Stamp> sales, ZoneId zone) {
        int[] counts = new int[24];
        BigDecimal[] revenue = new BigDecimal[24];
        List<List<Stamp>> byHour = new ArrayList<>(24);
        for (int i = 0; i < 24; i++) {
            revenue[i] = ZERO;
            byHour.add(new ArrayList<>());
        }

        int first = 24;
        int last = -1;
        BigDecimal totalRevenue = ZERO;
        for (Stamp sale : sales) {
            if (sale.soldAt() == null) {
                continue;
            }
            int hour = sale.soldAt().atZone(zone).getHour();
            counts[hour]++;
            BigDecimal amount = sale.amount() == null ? ZERO : sale.amount().setScale(2, RoundingMode.HALF_UP);
            revenue[hour] = revenue[hour].add(amount);
            totalRevenue = totalRevenue.add(amount);
            byHour.get(hour).add(sale);
            if (hour < first) {
                first = hour;
            }
            if (hour > last) {
                last = hour;
            }
        }

        if (last < 0) {
            return new SalesByHourResponse(zone.getId(), 0, ZERO, List.of());
        }

        List<SalesHourRow> hours = new ArrayList<>();
        int saleCount = 0;
        for (int hour = first; hour <= last; hour++) {
            List<Stamp> stamps = byHour.get(hour);
            stamps.sort(Comparator.comparing(Stamp::soldAt));
            int omitted = Math.max(0, stamps.size() - DETAIL_LIMIT);
            List<SalesHourSale> listed = new ArrayList<>();
            int take = Math.min(stamps.size(), DETAIL_LIMIT);
            for (int i = 0; i < take; i++) {
                Stamp stamp = stamps.get(i);
                listed.add(new SalesHourSale(
                        stamp.saleId(),
                        stamp.receiptNo(),
                        stamp.soldAt(),
                        stamp.cashierName() == null ? "" : stamp.cashierName(),
                        stamp.paymentMethod() == null ? "unknown" : stamp.paymentMethod(),
                        stamp.amount() == null ? ZERO : stamp.amount().setScale(2, RoundingMode.HALF_UP)
                ));
            }
            saleCount += counts[hour];
            hours.add(new SalesHourRow(
                    hour,
                    counts[hour],
                    revenue[hour].setScale(2, RoundingMode.HALF_UP),
                    omitted,
                    List.copyOf(listed)
            ));
        }

        return new SalesByHourResponse(
                zone.getId(),
                saleCount,
                totalRevenue.setScale(2, RoundingMode.HALF_UP),
                List.copyOf(hours)
        );
    }
}
