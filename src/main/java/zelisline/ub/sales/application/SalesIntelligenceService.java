package zelisline.ub.sales.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.api.dto.RevenueByCategoryRow;

@Service
@RequiredArgsConstructor
public class SalesIntelligenceService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final JdbcTemplate jdbc;

    private static final String Q_GROSS = """
            SELECT COALESCE(i.category_id, '_none') AS category_id,
                   COALESCE(c.name, 'Uncategorised') AS category_name,
                   COALESCE(SUM(sil.line_total), 0) AS amt
              FROM sale_items sil
              JOIN sales s ON s.id = sil.sale_id
              JOIN items i ON i.id = sil.item_id AND i.business_id = s.business_id AND i.deleted_at IS NULL
         LEFT JOIN categories c ON c.id = i.category_id AND c.business_id = s.business_id
             WHERE s.business_id = ?
               AND s.status IN (?, ?)
               AND CAST(s.sold_at AS DATE) BETWEEN ? AND ?
          GROUP BY COALESCE(i.category_id, '_none'), COALESCE(c.name, 'Uncategorised')
            """;

    private static final String Q_REFUNDS = """
            SELECT COALESCE(i.category_id, '_none') AS category_id,
                   COALESCE(c.name, 'Uncategorised') AS category_name,
                   COALESCE(SUM(rl.amount), 0) AS amt
              FROM refund_lines rl
              JOIN refunds r ON r.id = rl.refund_id
              JOIN sale_items sil ON sil.id = rl.sale_item_id
              JOIN sales s ON s.id = sil.sale_id
              JOIN items i ON i.id = sil.item_id AND i.business_id = r.business_id AND i.deleted_at IS NULL
         LEFT JOIN categories c ON c.id = i.category_id AND c.business_id = r.business_id
             WHERE r.business_id = ?
               AND r.status = ?
               AND CAST(r.refunded_at AS DATE) BETWEEN ? AND ?
          GROUP BY COALESCE(i.category_id, '_none'), COALESCE(c.name, 'Uncategorised')
            """;

    @Transactional(readOnly = true)
    public List<RevenueByCategoryRow> netRevenueByCategory(
            String businessId,
            LocalDate fromInclusive,
            LocalDate toInclusive
    ) {
        LocalDate[] w = resolveWindow(fromInclusive, toInclusive);
        Date from = Date.valueOf(w[0]);
        Date to = Date.valueOf(w[1]);

        Map<String, Agg> byCat = new HashMap<>();

        jdbc.query(
                Q_GROSS,
                rs -> {
                    String id = rs.getString("category_id");
                    String name = rs.getString("category_name");
                    BigDecimal amt = rs.getBigDecimal("amt").setScale(2, RoundingMode.HALF_UP);
                    byCat.merge(id, new Agg(name, amt, ZERO), SalesIntelligenceService::combineAgg);
                },
                businessId,
                SalesConstants.SALE_STATUS_COMPLETED,
                SalesConstants.SALE_STATUS_REFUNDED,
                from,
                to);

        jdbc.query(
                Q_REFUNDS,
                rs -> {
                    String id = rs.getString("category_id");
                    String name = rs.getString("category_name");
                    BigDecimal amt = rs.getBigDecimal("amt").setScale(2, RoundingMode.HALF_UP);
                    byCat.merge(id, new Agg(name, ZERO, amt), SalesIntelligenceService::combineAgg);
                },
                businessId,
                SalesConstants.REFUND_STATUS_COMPLETED,
                from,
                to);

        List<RevenueByCategoryRow> out = new ArrayList<>();
        for (Map.Entry<String, Agg> e : byCat.entrySet()) {
            Agg a = e.getValue();
            BigDecimal net = a.gross.subtract(a.refunds).setScale(2, RoundingMode.HALF_UP);
            if (net.signum() == 0) {
                continue;
            }
            out.add(new RevenueByCategoryRow(e.getKey(), a.name, net));
        }
        out.sort(Comparator.comparing(RevenueByCategoryRow::netRevenue).reversed());
        return out;
    }

    private static LocalDate[] resolveWindow(LocalDate fromInclusive, LocalDate toInclusive) {
        LocalDate toEx = toInclusive != null ? toInclusive : LocalDate.now(ZoneOffset.UTC);
        LocalDate fromEx = fromInclusive != null ? fromInclusive : toEx.minusDays(90);
        if (fromEx.isAfter(toEx)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date range");
        }
        return new LocalDate[] {fromEx, toEx};
    }

    private static Agg combineAgg(Agg a, Agg b) {
        return new Agg(a.name, a.gross.add(b.gross), a.refunds.add(b.refunds));
    }

    private static final class Agg {
        final String name;
        final BigDecimal gross;
        final BigDecimal refunds;

        Agg(String name, BigDecimal gross, BigDecimal refunds) {
            this.name = name;
            this.gross = gross;
            this.refunds = refunds;
        }
    }
}
