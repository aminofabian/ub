package zelisline.ub.purchasing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.purchasing.api.dto.PriceCompetitivenessRow;
import zelisline.ub.purchasing.api.dto.SingleSourceRiskRow;
import zelisline.ub.purchasing.api.dto.SpendBySupplierCategoryRow;

@Service
@RequiredArgsConstructor
public class SupplierIntelligenceService {

    private final JdbcTemplate jdbc;

    private static final String Q_SPEND = """
            SELECT si.supplier_id AS supplier_id,
                   s.name AS supplier_name,
                   COALESCE(i.category_id, '_none') AS category_id,
                   COALESCE(c.name, 'Uncategorised') AS category_name,
                   COALESCE(SUM(sil.line_total), 0) AS spend_total
              FROM supplier_invoice_lines sil
              JOIN supplier_invoices si ON si.id = sil.invoice_id
              JOIN suppliers s ON s.id = si.supplier_id AND s.business_id = si.business_id
         LEFT JOIN items i ON i.id = sil.item_id AND i.business_id = si.business_id AND i.deleted_at IS NULL
         LEFT JOIN categories c ON c.id = i.category_id AND c.business_id = si.business_id
             WHERE si.business_id = ?
               AND si.status = 'posted'
               AND si.invoice_date >= ?
               AND si.invoice_date <= ?
               AND s.deleted_at IS NULL
          GROUP BY si.supplier_id, s.name, COALESCE(i.category_id, '_none'), COALESCE(c.name, 'Uncategorised')
          ORDER BY s.name, category_name
            """;

    private static final String Q_PRICE = """
            SELECT sil.id AS line_id,
                   si.id AS invoice_id,
                   si.supplier_id AS invoicing_supplier_id,
                   sil.item_id AS item_id,
                   i.sku AS item_sku,
                   sil.unit_cost AS paid_unit_cost,
                   psp.supplier_id AS primary_supplier_id,
                   psp.last_cost_price AS primary_last_cost
              FROM supplier_invoice_lines sil
              JOIN supplier_invoices si ON si.id = sil.invoice_id
              JOIN items i ON i.id = sil.item_id AND i.business_id = si.business_id AND i.deleted_at IS NULL
              JOIN supplier_products psp ON psp.item_id = sil.item_id
               AND psp.is_primary = TRUE AND psp.active = TRUE AND psp.deleted_at IS NULL
             WHERE si.business_id = ?
               AND si.status = 'posted'
               AND si.invoice_date >= ?
               AND si.invoice_date <= ?
               AND sil.item_id IS NOT NULL
             ORDER BY si.invoice_date, sil.id
            """;

    private static final String Q_SINGLE_SOURCE = """
            SELECT i.id AS item_id,
                   i.sku AS sku,
                   i.name AS name,
                   sp.supplier_id AS sole_supplier_id,
                   s.name AS sole_supplier_name
              FROM items i
              JOIN supplier_products sp ON sp.item_id = i.id AND sp.active = TRUE AND sp.deleted_at IS NULL
              JOIN suppliers s ON s.id = sp.supplier_id AND s.business_id = i.business_id AND s.deleted_at IS NULL
             WHERE i.business_id = ?
               AND i.deleted_at IS NULL
               AND i.is_sellable = TRUE
               AND (SELECT COUNT(*) FROM supplier_products sp2
                     WHERE sp2.item_id = i.id AND sp2.active = TRUE AND sp2.deleted_at IS NULL) = 1
             ORDER BY i.sku
            """;

    @Transactional(readOnly = true)
    public List<SpendBySupplierCategoryRow> spendBySupplierAndCategory(
            String businessId,
            LocalDate fromInclusive,
            LocalDate toInclusive
    ) {
        LocalDate[] w = resolveWindow(fromInclusive, toInclusive);
        return jdbc.query(
                Q_SPEND,
                (rs, rowNum) -> new SpendBySupplierCategoryRow(
                        rs.getString("supplier_id"),
                        rs.getString("supplier_name"),
                        rs.getString("category_id"),
                        rs.getString("category_name"),
                        rs.getBigDecimal("spend_total").setScale(2, RoundingMode.HALF_UP)),
                businessId,
                Date.valueOf(w[0]),
                Date.valueOf(w[1]));
    }

    @Transactional(readOnly = true)
    public List<PriceCompetitivenessRow> priceCompetitivenessVsPrimary(
            String businessId,
            LocalDate fromInclusive,
            LocalDate toInclusive
    ) {
        LocalDate[] w = resolveWindow(fromInclusive, toInclusive);
        List<PriceCompetitivenessRow> out = new ArrayList<>();
        jdbc.query(
                Q_PRICE,
                rs -> {
                    String invSupplier = rs.getString("invoicing_supplier_id");
                    String primarySupplier = rs.getString("primary_supplier_id");
                    BigDecimal paid = rs.getBigDecimal("paid_unit_cost");
                    BigDecimal primaryCost = rs.getBigDecimal("primary_last_cost");
                    BigDecimal variance = null;
                    if (primaryCost != null && primaryCost.signum() > 0) {
                        variance = paid.subtract(primaryCost)
                                .divide(primaryCost, 4, RoundingMode.HALF_UP)
                                .multiply(new BigDecimal("100"))
                                .setScale(2, RoundingMode.HALF_UP);
                    }
                    boolean fromPrimary = invSupplier != null && invSupplier.equals(primarySupplier);
                    out.add(new PriceCompetitivenessRow(
                            rs.getString("line_id"),
                            rs.getString("invoice_id"),
                            invSupplier,
                            rs.getString("item_id"),
                            rs.getString("item_sku"),
                            paid,
                            primarySupplier,
                            primaryCost,
                            variance,
                            fromPrimary));
                },
                businessId,
                Date.valueOf(w[0]),
                Date.valueOf(w[1]));
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

    @Transactional(readOnly = true)
    public List<SingleSourceRiskRow> singleSourceRiskItems(String businessId) {
        return jdbc.query(
                Q_SINGLE_SOURCE,
                (rs, rowNum) -> new SingleSourceRiskRow(
                        rs.getString("item_id"),
                        rs.getString("sku"),
                        rs.getString("name"),
                        rs.getString("sole_supplier_id"),
                        rs.getString("sole_supplier_name")),
                businessId);
    }
}
