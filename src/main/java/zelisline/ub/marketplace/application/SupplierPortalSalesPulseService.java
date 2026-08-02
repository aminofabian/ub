package zelisline.ub.marketplace.application;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.SupplierPortalSalesPulseResponse;
import zelisline.ub.marketplace.api.dto.SupplierPortalSalesPulseResponse.SupplierPortalSalesPulseEvent;
import zelisline.ub.marketplace.api.dto.SupplierPortalSalesPulseResponse.SupplierPortalSalesPulseProduct;
import zelisline.ub.marketplace.api.dto.SupplierPortalSalesPulseResponse.SupplierPortalSalesPulseSummary;
import zelisline.ub.marketplace.domain.BusinessSupplierConnection;
import zelisline.ub.marketplace.domain.BusinessSupplierConnectionStatuses;
import zelisline.ub.marketplace.repository.BusinessSupplierConnectionRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Product movement pulse for the authenticated supplier hub: sell-in (supplies)
 * always, till sell-through only when {@code can_view_sales_velocity} is on.
 */
@Service
@RequiredArgsConstructor
public class SupplierPortalSalesPulseService {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final int EVENT_LIMIT = 36;
    private static final int PRODUCT_LIMIT = 18;

    private final BusinessSupplierConnectionRepository connectionRepository;
    private final BusinessRepository businessRepository;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public SupplierPortalSalesPulseResponse pulse(String marketplaceSupplierId) {
        List<BusinessSupplierConnection> links = connectionRepository
                .findByMarketplaceSupplierIdAndStatus(
                        marketplaceSupplierId, BusinessSupplierConnectionStatuses.ACTIVE);
        if (links.isEmpty()) {
            return empty("KES");
        }

        Map<String, Business> businesses = loadBusinesses(links);
        String currency = resolveCurrency(businesses);
        LocalDate today = LocalDate.now(NAIROBI);
        LocalDate since7 = today.minusDays(6);
        Instant since7Instant = since7.atStartOfDay(NAIROBI).toInstant();
        Instant todayStart = today.atStartOfDay(NAIROBI).toInstant();

        List<SupplierPortalSalesPulseEvent> events = new ArrayList<>();
        Map<String, Agg> byProduct = new HashMap<>();

        BigDecimal supplyQtyToday = BigDecimal.ZERO;
        BigDecimal supplyAmountToday = BigDecimal.ZERO;
        BigDecimal supplyQty7d = BigDecimal.ZERO;
        BigDecimal tillQtyToday = BigDecimal.ZERO;
        BigDecimal tillQty7d = BigDecimal.ZERO;
        int velocityShopCount = 0;

        for (BusinessSupplierConnection link : links) {
            Business business = businesses.get(link.getBusinessId());
            String shopName = business != null && business.getName() != null
                    ? business.getName().trim()
                    : "Shop";

            List<SupplyRow> supplies = loadSupplies(link, since7);
            for (SupplyRow row : supplies) {
                boolean isToday = !row.invoiceDate().isBefore(today);
                Agg agg = byProduct.computeIfAbsent(
                        key("supply", link.getBusinessId(), row.productKey()),
                        ignored -> new Agg(row.productName(), shopName, "supply"));
                agg.qty7d = agg.qty7d.add(row.qty());
                agg.amount7d = agg.amount7d.add(row.amount());
                supplyQty7d = supplyQty7d.add(row.qty());
                if (isToday) {
                    agg.qtyToday = agg.qtyToday.add(row.qty());
                    agg.amountToday = agg.amountToday.add(row.amount());
                    supplyQtyToday = supplyQtyToday.add(row.qty());
                    supplyAmountToday = supplyAmountToday.add(row.amount());
                }
            }
            events.addAll(loadSupplyEvents(link, shopName, EVENT_LIMIT));

            if (!link.isCanViewSalesVelocity()) {
                continue;
            }
            velocityShopCount++;
            List<TillRow> tills = loadTill(link, since7Instant, todayStart);
            for (TillRow row : tills) {
                Agg agg = byProduct.computeIfAbsent(
                        key("till", link.getBusinessId(), row.itemId()),
                        ignored -> new Agg(row.productName(), shopName, "till"));
                agg.qty7d = agg.qty7d.add(row.qty7d());
                agg.qtyToday = agg.qtyToday.add(row.qtyToday());
                tillQty7d = tillQty7d.add(row.qty7d());
                tillQtyToday = tillQtyToday.add(row.qtyToday());
            }
            events.addAll(loadTillEvents(link, shopName, todayStart, EVENT_LIMIT));
        }

        events.sort(Comparator.comparing(SupplierPortalSalesPulseEvent::at).reversed());
        if (events.size() > EVENT_LIMIT) {
            events = new ArrayList<>(events.subList(0, EVENT_LIMIT));
        }

        List<SupplierPortalSalesPulseProduct> products = byProduct.entrySet().stream()
                .map(e -> {
                    Agg a = e.getValue();
                    return new SupplierPortalSalesPulseProduct(
                            e.getKey(),
                            a.productName,
                            a.shopName,
                            a.channel,
                            a.qtyToday,
                            a.qty7d,
                            a.amountToday,
                            a.amount7d);
                })
                .sorted(Comparator
                        .comparing(SupplierPortalSalesPulseProduct::qtyToday).reversed()
                        .thenComparing(Comparator.comparing(SupplierPortalSalesPulseProduct::qty7d).reversed())
                        .thenComparing(SupplierPortalSalesPulseProduct::productName,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .limit(PRODUCT_LIMIT)
                .toList();

        return new SupplierPortalSalesPulseResponse(
                Instant.now(),
                currency,
                new SupplierPortalSalesPulseSummary(
                        supplyQtyToday,
                        supplyAmountToday,
                        tillQtyToday,
                        supplyQty7d,
                        tillQty7d,
                        events.size()),
                products,
                events,
                velocityShopCount,
                links.size());
    }

    private SupplierPortalSalesPulseResponse empty(String currency) {
        return new SupplierPortalSalesPulseResponse(
                Instant.now(),
                currency,
                new SupplierPortalSalesPulseSummary(
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        0),
                List.of(),
                List.of(),
                0,
                0);
    }

    private Map<String, Business> loadBusinesses(List<BusinessSupplierConnection> links) {
        List<String> ids = links.stream().map(BusinessSupplierConnection::getBusinessId).distinct().toList();
        Map<String, Business> out = new HashMap<>();
        for (Business b : businessRepository.findAllById(ids)) {
            out.put(b.getId(), b);
        }
        return out;
    }

    private static String resolveCurrency(Map<String, Business> businesses) {
        for (Business b : businesses.values()) {
            if (b.getCurrency() != null && !b.getCurrency().isBlank()) {
                return b.getCurrency().trim();
            }
        }
        return "KES";
    }

    private List<SupplyRow> loadSupplies(BusinessSupplierConnection link, LocalDate since) {
        return jdbc.query(
                """
                        SELECT COALESCE(sil.item_id, CONCAT('desc:', LOWER(TRIM(sil.description)))) AS product_key,
                               COALESCE(NULLIF(TRIM(i.name), ''), NULLIF(TRIM(sil.description), ''), 'Product') AS product_name,
                               sil.qty AS qty,
                               sil.line_total AS amount,
                               si.invoice_date AS invoice_date
                          FROM supplier_invoice_lines sil
                          JOIN supplier_invoices si ON si.id = sil.invoice_id
                     LEFT JOIN items i ON i.id = sil.item_id AND i.business_id = si.business_id AND i.deleted_at IS NULL
                         WHERE si.business_id = ?
                           AND si.supplier_id = ?
                           AND si.status = 'posted'
                           AND si.invoice_date >= ?
                        """,
                (rs, rowNum) -> new SupplyRow(
                        rs.getString("product_key"),
                        rs.getString("product_name"),
                        rs.getBigDecimal("qty") != null ? rs.getBigDecimal("qty") : BigDecimal.ZERO,
                        rs.getBigDecimal("amount") != null ? rs.getBigDecimal("amount") : BigDecimal.ZERO,
                        rs.getDate("invoice_date").toLocalDate()),
                link.getBusinessId(),
                link.getLocalSupplierId(),
                java.sql.Date.valueOf(since));
    }

    private List<SupplierPortalSalesPulseEvent> loadSupplyEvents(
            BusinessSupplierConnection link,
            String shopName,
            int limit
    ) {
        return jdbc.query(
                """
                        SELECT sil.id AS id,
                               COALESCE(si.created_at, CAST(si.invoice_date AS DATETIME)) AS at_ts,
                               COALESCE(NULLIF(TRIM(i.name), ''), NULLIF(TRIM(sil.description), ''), 'Product') AS product_name,
                               sil.qty AS qty,
                               sil.line_total AS amount
                          FROM supplier_invoice_lines sil
                          JOIN supplier_invoices si ON si.id = sil.invoice_id
                     LEFT JOIN items i ON i.id = sil.item_id AND i.business_id = si.business_id AND i.deleted_at IS NULL
                         WHERE si.business_id = ?
                           AND si.supplier_id = ?
                           AND si.status = 'posted'
                      ORDER BY at_ts DESC
                         LIMIT ?
                        """,
                (rs, rowNum) -> new SupplierPortalSalesPulseEvent(
                        "supply:" + rs.getString("id"),
                        toInstant(rs.getTimestamp("at_ts")),
                        "supply",
                        rs.getString("product_name"),
                        shopName,
                        rs.getBigDecimal("qty") != null ? rs.getBigDecimal("qty") : BigDecimal.ZERO,
                        rs.getBigDecimal("amount") != null ? rs.getBigDecimal("amount") : BigDecimal.ZERO),
                link.getBusinessId(),
                link.getLocalSupplierId(),
                limit);
    }

    private List<TillRow> loadTill(
            BusinessSupplierConnection link,
            Instant since7,
            Instant todayStart
    ) {
        return jdbc.query(
                """
                        SELECT si.item_id AS item_id,
                               COALESCE(NULLIF(TRIM(i.name), ''), 'Product') AS product_name,
                               COALESCE(SUM(si.quantity), 0) AS qty_7d,
                               COALESCE(SUM(CASE WHEN s.sold_at >= ? THEN si.quantity ELSE 0 END), 0) AS qty_today
                          FROM sale_items si
                          JOIN sales s ON s.id = si.sale_id
                          JOIN items i ON i.id = si.item_id AND i.business_id = s.business_id AND i.deleted_at IS NULL
                         WHERE s.business_id = ?
                           AND s.status = 'completed'
                           AND s.voided_at IS NULL
                           AND s.sold_at >= ?
                           AND si.item_id IN (
                                 SELECT sp.item_id
                                   FROM supplier_products sp
                                  WHERE sp.supplier_id = ?
                                    AND sp.active = TRUE
                                    AND sp.deleted_at IS NULL
                           )
                      GROUP BY si.item_id, COALESCE(NULLIF(TRIM(i.name), ''), 'Product')
                        """,
                (rs, rowNum) -> new TillRow(
                        rs.getString("item_id"),
                        rs.getString("product_name"),
                        rs.getBigDecimal("qty_today") != null ? rs.getBigDecimal("qty_today") : BigDecimal.ZERO,
                        rs.getBigDecimal("qty_7d") != null ? rs.getBigDecimal("qty_7d") : BigDecimal.ZERO),
                Timestamp.from(todayStart),
                link.getBusinessId(),
                Timestamp.from(since7),
                link.getLocalSupplierId());
    }

    private List<SupplierPortalSalesPulseEvent> loadTillEvents(
            BusinessSupplierConnection link,
            String shopName,
            Instant since,
            int limit
    ) {
        return jdbc.query(
                """
                        SELECT si.id AS id,
                               s.sold_at AS at_ts,
                               COALESCE(NULLIF(TRIM(i.name), ''), 'Product') AS product_name,
                               si.quantity AS qty
                          FROM sale_items si
                          JOIN sales s ON s.id = si.sale_id
                          JOIN items i ON i.id = si.item_id AND i.business_id = s.business_id AND i.deleted_at IS NULL
                         WHERE s.business_id = ?
                           AND s.status = 'completed'
                           AND s.voided_at IS NULL
                           AND s.sold_at >= ?
                           AND si.item_id IN (
                                 SELECT sp.item_id
                                   FROM supplier_products sp
                                  WHERE sp.supplier_id = ?
                                    AND sp.active = TRUE
                                    AND sp.deleted_at IS NULL
                           )
                      ORDER BY s.sold_at DESC
                         LIMIT ?
                        """,
                (rs, rowNum) -> new SupplierPortalSalesPulseEvent(
                        "till:" + rs.getString("id"),
                        toInstant(rs.getTimestamp("at_ts")),
                        "till",
                        rs.getString("product_name"),
                        shopName,
                        rs.getBigDecimal("qty") != null ? rs.getBigDecimal("qty") : BigDecimal.ZERO,
                        null),
                link.getBusinessId(),
                Timestamp.from(since),
                link.getLocalSupplierId(),
                limit);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : Instant.EPOCH;
    }

    private static String key(String channel, String businessId, String productKey) {
        return channel + ":" + businessId + ":" + productKey;
    }

    private static final class Agg {
        private final String productName;
        private final String shopName;
        private final String channel;
        private BigDecimal qtyToday = BigDecimal.ZERO;
        private BigDecimal qty7d = BigDecimal.ZERO;
        private BigDecimal amountToday = BigDecimal.ZERO;
        private BigDecimal amount7d = BigDecimal.ZERO;

        private Agg(String productName, String shopName, String channel) {
            this.productName = productName;
            this.shopName = shopName;
            this.channel = channel;
        }
    }

    private record SupplyRow(
            String productKey,
            String productName,
            BigDecimal qty,
            BigDecimal amount,
            LocalDate invoiceDate
    ) {
    }

    private record TillRow(
            String itemId,
            String productName,
            BigDecimal qtyToday,
            BigDecimal qty7d
    ) {
    }
}
