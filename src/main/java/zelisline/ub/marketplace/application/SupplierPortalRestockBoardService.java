package zelisline.ub.marketplace.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.SupplierPortalRestockBoardResponse;
import zelisline.ub.marketplace.api.dto.SupplierPortalRestockBoardResponse.SupplierPortalRestockBoardSummary;
import zelisline.ub.marketplace.api.dto.SupplierPortalRestockBoardResponse.SupplierPortalRestockDayBucket;
import zelisline.ub.marketplace.api.dto.SupplierPortalRestockBoardResponse.SupplierPortalRestockRow;
import zelisline.ub.marketplace.domain.BusinessSupplierConnection;
import zelisline.ub.marketplace.domain.BusinessSupplierConnectionStatuses;
import zelisline.ub.marketplace.repository.BusinessSupplierConnectionRepository;
import zelisline.ub.tenancy.application.BranchResolutionService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Restock intelligence for suppliers: supplied / till / damages / on-hand /
 * suggested qty across day · week · month windows.
 */
@Service
@RequiredArgsConstructor
public class SupplierPortalRestockBoardService {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int SCALE = 4;

    private final BusinessSupplierConnectionRepository connectionRepository;
    private final BusinessRepository businessRepository;
    private final BranchResolutionService branchResolutionService;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public SupplierPortalRestockBoardResponse board(
            String marketplaceSupplierId,
            String windowRaw,
            String localSupplierIdFilter
    ) {
        String window = normalizeWindow(windowRaw);
        List<BusinessSupplierConnection> links = connectionRepository
                .findByMarketplaceSupplierIdAndStatus(
                        marketplaceSupplierId, BusinessSupplierConnectionStatuses.ACTIVE);
        if (localSupplierIdFilter != null && !localSupplierIdFilter.isBlank()) {
            String filter = localSupplierIdFilter.trim();
            links = links.stream()
                    .filter(l -> filter.equals(l.getLocalSupplierId()))
                    .toList();
            if (links.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop link not found");
            }
        }
        if (links.isEmpty()) {
            LocalDate today = LocalDate.now(NAIROBI);
            return empty(window, today, today, 1, "KES");
        }

        Map<String, Business> businesses = loadBusinesses(links);
        String currency = resolveCurrency(businesses);

        LocalDate today = LocalDate.now(NAIROBI);
        int windowDays = windowDays(window);
        LocalDate windowStart = today.minusDays(windowDays - 1L);
        Instant windowStartInstant = windowStart.atStartOfDay(NAIROBI).toInstant();
        // Always show a 7-day daily tape for cadence, even on month view.
        LocalDate dailyStart = today.minusDays(6);
        Instant dailyStartInstant = dailyStart.atStartOfDay(NAIROBI).toInstant();
        LocalDate historyStart = windowStart.isBefore(dailyStart) ? windowStart : dailyStart;
        Instant historyStartInstant = historyStart.atStartOfDay(NAIROBI).toInstant();

        Map<LocalDate, DayAgg> dailyMap = seedDaily(dailyStart, today);
        Map<String, RowAgg> rows = new LinkedHashMap<>();

        int stockShopCount = 0;
        int velocityShopCount = 0;

        for (BusinessSupplierConnection link : links) {
            Business business = businesses.get(link.getBusinessId());
            String shopName = business != null && business.getName() != null
                    ? business.getName().trim()
                    : "Shop";
            boolean stockVisible = link.isCanViewStockLevels();
            boolean velocityVisible = link.isCanViewSalesVelocity();
            // Supply-based plans are always ok; stock/till refine when shared.
            boolean suggestOk = true;
            if (stockVisible) {
                stockShopCount++;
            }
            if (velocityVisible) {
                velocityShopCount++;
            }

            String branchId = branchResolutionService.resolveDefaultBranch(link.getBusinessId());

            for (SupplyHit hit : loadSupplies(link, historyStart)) {
                bumpDaily(dailyMap, hit.date(), hit.qty(), ZERO, ZERO);
                if (!hit.date().isBefore(windowStart)) {
                    RowAgg row = rows.computeIfAbsent(
                            key(link.getLocalSupplierId(), hit.itemKey()),
                            ignored -> new RowAgg(
                                    link.getLocalSupplierId(),
                                    shopName,
                                    hit.itemId(),
                                    hit.productName(),
                                    hit.sku(),
                                    hit.packSize(),
                                    hit.packUnit(),
                                    stockVisible,
                                    velocityVisible,
                                    suggestOk));
                    mergeMeta(row, hit);
                    row.supplied = row.supplied.add(hit.qty());
                }
            }

            for (DamageHit hit : loadDamages(link, historyStartInstant)) {
                LocalDate d = hit.at().atZone(NAIROBI).toLocalDate();
                bumpDaily(dailyMap, d, ZERO, ZERO, hit.qty());
                if (!hit.at().isBefore(windowStartInstant)) {
                    RowAgg row = rows.computeIfAbsent(
                            key(link.getLocalSupplierId(), hit.itemId()),
                            ignored -> new RowAgg(
                                    link.getLocalSupplierId(),
                                    shopName,
                                    hit.itemId(),
                                    hit.productName(),
                                    hit.sku(),
                                    hit.packSize(),
                                    hit.packUnit(),
                                    stockVisible,
                                    velocityVisible,
                                    suggestOk));
                    mergeMeta(row, hit.productName(), hit.sku(), hit.packSize(), hit.packUnit(), hit.itemId());
                    row.damage = row.damage.add(hit.qty());
                }
            }

            if (velocityVisible) {
                for (TillHit hit : loadTill(link, historyStartInstant)) {
                    LocalDate d = hit.at().atZone(NAIROBI).toLocalDate();
                    bumpDaily(dailyMap, d, ZERO, hit.qty(), ZERO);
                    if (!hit.at().isBefore(windowStartInstant)) {
                        RowAgg row = rows.computeIfAbsent(
                                key(link.getLocalSupplierId(), hit.itemId()),
                                ignored -> new RowAgg(
                                        link.getLocalSupplierId(),
                                        shopName,
                                        hit.itemId(),
                                        hit.productName(),
                                        hit.sku(),
                                        hit.packSize(),
                                        hit.packUnit(),
                                        stockVisible,
                                        velocityVisible,
                                        suggestOk));
                        mergeMeta(row, hit.productName(), hit.sku(), hit.packSize(), hit.packUnit(), hit.itemId());
                        row.till = row.till.add(hit.qty());
                    }
                }
            }

            if (stockVisible) {
                for (StockHit hit : loadOnHand(link, branchId)) {
                    RowAgg row = rows.computeIfAbsent(
                            key(link.getLocalSupplierId(), hit.itemId()),
                            ignored -> new RowAgg(
                                    link.getLocalSupplierId(),
                                    shopName,
                                    hit.itemId(),
                                    hit.productName(),
                                    hit.sku(),
                                    hit.packSize(),
                                    hit.packUnit(),
                                    stockVisible,
                                    velocityVisible,
                                    suggestOk));
                    mergeMeta(row, hit.productName(), hit.sku(), hit.packSize(), hit.packUnit(), hit.itemId());
                    row.onHand = hit.onHand();
                    row.hasOnHand = true;
                }
            }
        }

        int targetCover = targetCoverDays(window);
        BigDecimal daysBd = BigDecimal.valueOf(windowDays);

        List<SupplierPortalRestockRow> outRows = new ArrayList<>();
        BigDecimal sumSupplied = ZERO;
        BigDecimal sumTill = ZERO;
        BigDecimal sumDamage = ZERO;
        BigDecimal sumOnHand = ZERO;
        BigDecimal sumSuggested = ZERO;
        int needsRestock = 0;
        int outOfStock = 0;

        for (RowAgg row : rows.values()) {
            BigDecimal demandBase = row.velocityVisible && row.till.signum() > 0
                    ? row.till
                    : row.supplied;
            BigDecimal avgDaily = demandBase.divide(daysBd, SCALE, RoundingMode.HALF_UP);
            BigDecimal onHand = row.hasOnHand ? nullSafe(row.onHand) : null;
            BigDecimal daysOfCover = null;
            BigDecimal suggested = ZERO;

            if (avgDaily.signum() > 0) {
                BigDecimal target = avgDaily.multiply(BigDecimal.valueOf(targetCover));
                if (onHand != null) {
                    daysOfCover = onHand.divide(avgDaily, 1, RoundingMode.HALF_UP);
                    suggested = target.subtract(onHand).max(ZERO);
                } else if (row.suggestOk) {
                    // No stock share — plan a typical run for the cover window.
                    suggested = target;
                }
            }
            // Damages inflate the next run slightly when we have a plan.
            if (suggested.signum() > 0 && row.damage.signum() > 0) {
                suggested = suggested.add(row.damage);
            }
            suggested = roundToPack(suggested, row.packSize);

            String urgency = urgency(onHand, daysOfCover, suggested, row.stockVisible);

            if (suggested.signum() > 0) {
                needsRestock++;
            }
            if (onHand != null && onHand.signum() <= 0) {
                outOfStock++;
            }

            sumSupplied = sumSupplied.add(row.supplied);
            sumTill = sumTill.add(row.till);
            sumDamage = sumDamage.add(row.damage);
            if (onHand != null) {
                sumOnHand = sumOnHand.add(onHand);
            }
            sumSuggested = sumSuggested.add(suggested);

            outRows.add(new SupplierPortalRestockRow(
                    key(row.localSupplierId, row.itemId != null ? row.itemId : row.productName),
                    row.localSupplierId,
                    row.shopName,
                    row.itemId,
                    row.productName,
                    row.sku,
                    row.packSize,
                    row.packUnit,
                    row.supplied,
                    row.velocityVisible ? row.till : null,
                    row.damage,
                    row.stockVisible ? onHand : null,
                    avgDaily,
                    daysOfCover,
                    suggested.signum() > 0 ? suggested : ZERO,
                    row.stockVisible,
                    row.velocityVisible,
                    urgency));
        }

        outRows.sort(Comparator
                .comparing((SupplierPortalRestockRow r) -> urgencyRank(r.urgency()))
                .thenComparing(Comparator.comparing(SupplierPortalRestockRow::suggestedRestock).reversed())
                .thenComparing(SupplierPortalRestockRow::productName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));

        List<SupplierPortalRestockDayBucket> daily = dailyMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new SupplierPortalRestockDayBucket(
                        e.getKey(),
                        e.getValue().supplied,
                        e.getValue().till,
                        e.getValue().damage))
                .toList();

        return new SupplierPortalRestockBoardResponse(
                Instant.now(),
                currency,
                window,
                windowStart,
                today,
                windowDays,
                new SupplierPortalRestockBoardSummary(
                        sumSupplied,
                        sumTill,
                        sumDamage,
                        sumOnHand,
                        sumSuggested,
                        needsRestock,
                        outOfStock),
                daily,
                outRows,
                stockShopCount,
                velocityShopCount,
                links.size());
    }

    public byte[] toPdf(SupplierPortalRestockBoardResponse board) {
        return SupplierPortalRestockBoardPdfRenderer.render(board);
    }

    public String toCsv(SupplierPortalRestockBoardResponse board) {
        StringBuilder sb = new StringBuilder();
        sb.append("product,shop,sku,supplied,till,damage,on_hand,avg_daily,days_of_cover,suggested,urgency,pack_size,pack_unit\n");
        for (SupplierPortalRestockRow row : board.rows()) {
            sb.append(csv(row.productName())).append(',')
                    .append(csv(row.shopName())).append(',')
                    .append(csv(row.sku())).append(',')
                    .append(qty(row.suppliedQty())).append(',')
                    .append(row.tillQty() == null ? "" : qty(row.tillQty())).append(',')
                    .append(qty(row.damageQty())).append(',')
                    .append(row.onHand() == null ? "" : qty(row.onHand())).append(',')
                    .append(qty(row.avgDailyDemand())).append(',')
                    .append(row.daysOfCover() == null ? "" : qty(row.daysOfCover())).append(',')
                    .append(qty(row.suggestedRestock())).append(',')
                    .append(csv(row.urgency())).append(',')
                    .append(row.packSize() == null ? "" : qty(row.packSize())).append(',')
                    .append(csv(row.packUnit()))
                    .append('\n');
        }
        return sb.toString();
    }

    private SupplierPortalRestockBoardResponse empty(
            String window,
            LocalDate start,
            LocalDate end,
            int days,
            String currency
    ) {
        return new SupplierPortalRestockBoardResponse(
                Instant.now(),
                currency,
                window,
                start,
                end,
                days,
                new SupplierPortalRestockBoardSummary(ZERO, ZERO, ZERO, ZERO, ZERO, 0, 0),
                List.of(),
                List.of(),
                0,
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

    private List<SupplyHit> loadSupplies(BusinessSupplierConnection link, LocalDate since) {
        return jdbc.query(
                """
                        SELECT COALESCE(sil.item_id, CONCAT('desc:', LOWER(TRIM(sil.description)))) AS item_key,
                               sil.item_id AS item_id,
                               COALESCE(NULLIF(TRIM(i.name), ''), NULLIF(TRIM(sil.description), ''), 'Product') AS product_name,
                               i.sku AS sku,
                               sp.pack_size AS pack_size,
                               sp.pack_unit AS pack_unit,
                               sil.qty AS qty,
                               si.invoice_date AS invoice_date
                          FROM supplier_invoice_lines sil
                          JOIN supplier_invoices si ON si.id = sil.invoice_id
                     LEFT JOIN items i ON i.id = sil.item_id AND i.business_id = si.business_id AND i.deleted_at IS NULL
                     LEFT JOIN supplier_products sp ON sp.supplier_id = si.supplier_id
                           AND sp.item_id = sil.item_id AND sp.deleted_at IS NULL AND sp.active = TRUE
                         WHERE si.business_id = ?
                           AND si.supplier_id = ?
                           AND si.status = 'posted'
                           AND si.invoice_date >= ?
                        """,
                (rs, rowNum) -> new SupplyHit(
                        rs.getString("item_key"),
                        rs.getString("item_id"),
                        rs.getString("product_name"),
                        rs.getString("sku"),
                        rs.getBigDecimal("pack_size"),
                        rs.getString("pack_unit"),
                        nullSafe(rs.getBigDecimal("qty")),
                        rs.getDate("invoice_date").toLocalDate()),
                link.getBusinessId(),
                link.getLocalSupplierId(),
                Date.valueOf(since));
    }

    private List<DamageHit> loadDamages(BusinessSupplierConnection link, Instant since) {
        return jdbc.query(
                """
                        SELECT sm.item_id AS item_id,
                               COALESCE(NULLIF(TRIM(i.name), ''), 'Product') AS product_name,
                               i.sku AS sku,
                               sp.pack_size AS pack_size,
                               sp.pack_unit AS pack_unit,
                               ABS(sm.quantity_delta) AS qty,
                               sm.created_at AS at_ts
                          FROM stock_movements sm
                          JOIN items i ON i.id = sm.item_id AND i.business_id = sm.business_id AND i.deleted_at IS NULL
                          JOIN supplier_products sp ON sp.item_id = sm.item_id
                           AND sp.supplier_id = ?
                           AND sp.active = TRUE
                           AND sp.deleted_at IS NULL
                         WHERE sm.business_id = ?
                           AND sm.movement_type = 'wastage'
                           AND sm.created_at >= ?
                        """,
                (rs, rowNum) -> new DamageHit(
                        rs.getString("item_id"),
                        rs.getString("product_name"),
                        rs.getString("sku"),
                        rs.getBigDecimal("pack_size"),
                        rs.getString("pack_unit"),
                        nullSafe(rs.getBigDecimal("qty")),
                        rs.getTimestamp("at_ts").toInstant()),
                link.getLocalSupplierId(),
                link.getBusinessId(),
                Timestamp.from(since));
    }

    private List<TillHit> loadTill(BusinessSupplierConnection link, Instant since) {
        return jdbc.query(
                """
                        SELECT si.item_id AS item_id,
                               COALESCE(NULLIF(TRIM(i.name), ''), 'Product') AS product_name,
                               i.sku AS sku,
                               sp.pack_size AS pack_size,
                               sp.pack_unit AS pack_unit,
                               si.quantity AS qty,
                               s.sold_at AS at_ts
                          FROM sale_items si
                          JOIN sales s ON s.id = si.sale_id
                          JOIN items i ON i.id = si.item_id AND i.business_id = s.business_id AND i.deleted_at IS NULL
                          JOIN supplier_products sp ON sp.item_id = si.item_id
                           AND sp.supplier_id = ?
                           AND sp.active = TRUE
                           AND sp.deleted_at IS NULL
                         WHERE s.business_id = ?
                           AND s.status = 'completed'
                           AND s.voided_at IS NULL
                           AND s.sold_at >= ?
                        """,
                (rs, rowNum) -> new TillHit(
                        rs.getString("item_id"),
                        rs.getString("product_name"),
                        rs.getString("sku"),
                        rs.getBigDecimal("pack_size"),
                        rs.getString("pack_unit"),
                        nullSafe(rs.getBigDecimal("qty")),
                        rs.getTimestamp("at_ts").toInstant()),
                link.getLocalSupplierId(),
                link.getBusinessId(),
                Timestamp.from(since));
    }

    private List<StockHit> loadOnHand(BusinessSupplierConnection link, String branchId) {
        if (branchId == null || branchId.isBlank()) {
            return jdbc.query(
                    """
                            SELECT sp.item_id AS item_id,
                                   COALESCE(NULLIF(TRIM(i.name), ''), 'Product') AS product_name,
                                   i.sku AS sku,
                                   sp.pack_size AS pack_size,
                                   sp.pack_unit AS pack_unit,
                                   COALESCE(i.current_stock, 0) AS on_hand
                              FROM supplier_products sp
                              JOIN items i ON i.id = sp.item_id AND i.deleted_at IS NULL
                             WHERE sp.supplier_id = ?
                               AND sp.active = TRUE
                               AND sp.deleted_at IS NULL
                               AND i.business_id = ?
                            """,
                    (rs, rowNum) -> new StockHit(
                            rs.getString("item_id"),
                            rs.getString("product_name"),
                            rs.getString("sku"),
                            rs.getBigDecimal("pack_size"),
                            rs.getString("pack_unit"),
                            nullSafe(rs.getBigDecimal("on_hand"))),
                    link.getLocalSupplierId(),
                    link.getBusinessId());
        }
        return jdbc.query(
                """
                        SELECT sp.item_id AS item_id,
                               COALESCE(NULLIF(TRIM(i.name), ''), 'Product') AS product_name,
                               i.sku AS sku,
                               sp.pack_size AS pack_size,
                               sp.pack_unit AS pack_unit,
                               COALESCE(SUM(b.quantity_remaining), 0) AS on_hand
                          FROM supplier_products sp
                          JOIN items i ON i.id = sp.item_id AND i.deleted_at IS NULL
                     LEFT JOIN inventory_batches b ON b.item_id = sp.item_id
                           AND b.business_id = i.business_id
                           AND b.branch_id = ?
                           AND b.status = 'active'
                         WHERE sp.supplier_id = ?
                           AND sp.active = TRUE
                           AND sp.deleted_at IS NULL
                           AND i.business_id = ?
                      GROUP BY sp.item_id, i.name, i.sku, sp.pack_size, sp.pack_unit
                        """,
                (rs, rowNum) -> new StockHit(
                        rs.getString("item_id"),
                        rs.getString("product_name"),
                        rs.getString("sku"),
                        rs.getBigDecimal("pack_size"),
                        rs.getString("pack_unit"),
                        nullSafe(rs.getBigDecimal("on_hand"))),
                branchId,
                link.getLocalSupplierId(),
                link.getBusinessId());
    }

    private static Map<LocalDate, DayAgg> seedDaily(LocalDate start, LocalDate end) {
        Map<LocalDate, DayAgg> map = new LinkedHashMap<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            map.put(d, new DayAgg());
        }
        return map;
    }

    private static void bumpDaily(
            Map<LocalDate, DayAgg> map,
            LocalDate date,
            BigDecimal supplied,
            BigDecimal till,
            BigDecimal damage
    ) {
        DayAgg agg = map.get(date);
        if (agg == null) {
            return;
        }
        agg.supplied = agg.supplied.add(nullSafe(supplied));
        agg.till = agg.till.add(nullSafe(till));
        agg.damage = agg.damage.add(nullSafe(damage));
    }

    private static void mergeMeta(RowAgg row, SupplyHit hit) {
        mergeMeta(row, hit.productName(), hit.sku(), hit.packSize(), hit.packUnit(), hit.itemId());
    }

    private static void mergeMeta(
            RowAgg row,
            String productName,
            String sku,
            BigDecimal packSize,
            String packUnit,
            String itemId
    ) {
        if (row.itemId == null && itemId != null) {
            row.itemId = itemId;
        }
        if ((row.productName == null || row.productName.isBlank()) && productName != null) {
            row.productName = productName;
        }
        if (row.sku == null && sku != null) {
            row.sku = sku;
        }
        if (row.packSize == null && packSize != null) {
            row.packSize = packSize;
        }
        if (row.packUnit == null && packUnit != null) {
            row.packUnit = packUnit;
        }
    }

    private static String normalizeWindow(String raw) {
        if (raw == null || raw.isBlank()) {
            return "week";
        }
        String w = raw.trim().toLowerCase(Locale.ROOT);
        return switch (w) {
            case "day", "today", "1d" -> "day";
            case "month", "30d" -> "month";
            default -> "week";
        };
    }

    private static int windowDays(String window) {
        return switch (window) {
            case "day" -> 1;
            case "month" -> 30;
            default -> 7;
        };
    }

    private static int targetCoverDays(String window) {
        return switch (window) {
            case "day" -> 3;
            case "month" -> 14;
            default -> 7;
        };
    }

    private static BigDecimal roundToPack(BigDecimal qty, BigDecimal packSize) {
        if (qty == null || qty.signum() <= 0) {
            return ZERO;
        }
        if (packSize == null || packSize.signum() <= 0) {
            return qty.setScale(0, RoundingMode.CEILING);
        }
        BigDecimal packs = qty.divide(packSize, 0, RoundingMode.CEILING);
        return packs.multiply(packSize).setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static String urgency(
            BigDecimal onHand,
            BigDecimal daysOfCover,
            BigDecimal suggested,
            boolean stockVisible
    ) {
        if (stockVisible && onHand != null && onHand.signum() <= 0) {
            return "out";
        }
        if (daysOfCover != null && daysOfCover.compareTo(BigDecimal.valueOf(2)) < 0) {
            return "low";
        }
        if (suggested != null && suggested.signum() > 0) {
            return "plan";
        }
        return "ok";
    }

    private static int urgencyRank(String urgency) {
        if (urgency == null) {
            return 9;
        }
        return switch (urgency) {
            case "out" -> 0;
            case "low" -> 1;
            case "plan" -> 2;
            default -> 3;
        };
    }

    private static String key(String localSupplierId, String itemKey) {
        return localSupplierId + ":" + itemKey;
    }

    private static BigDecimal nullSafe(BigDecimal v) {
        return v != null ? v : ZERO;
    }

    private static String qty(BigDecimal v) {
        if (v == null) {
            return "0";
        }
        return v.stripTrailingZeros().toPlainString();
    }

    private static String csv(String v) {
        if (v == null) {
            return "";
        }
        String s = v.replace("\"", "\"\"");
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s + "\"";
        }
        return s;
    }

    private static final class DayAgg {
        private BigDecimal supplied = ZERO;
        private BigDecimal till = ZERO;
        private BigDecimal damage = ZERO;
    }

    private static final class RowAgg {
        private final String localSupplierId;
        private final String shopName;
        private String itemId;
        private String productName;
        private String sku;
        private BigDecimal packSize;
        private String packUnit;
        private final boolean stockVisible;
        private final boolean velocityVisible;
        private final boolean suggestOk;
        private BigDecimal supplied = ZERO;
        private BigDecimal till = ZERO;
        private BigDecimal damage = ZERO;
        private BigDecimal onHand = ZERO;
        private boolean hasOnHand;

        private RowAgg(
                String localSupplierId,
                String shopName,
                String itemId,
                String productName,
                String sku,
                BigDecimal packSize,
                String packUnit,
                boolean stockVisible,
                boolean velocityVisible,
                boolean suggestOk
        ) {
            this.localSupplierId = localSupplierId;
            this.shopName = shopName;
            this.itemId = itemId;
            this.productName = productName;
            this.sku = sku;
            this.packSize = packSize;
            this.packUnit = packUnit;
            this.stockVisible = stockVisible;
            this.velocityVisible = velocityVisible;
            this.suggestOk = suggestOk;
        }
    }

    private record SupplyHit(
            String itemKey,
            String itemId,
            String productName,
            String sku,
            BigDecimal packSize,
            String packUnit,
            BigDecimal qty,
            LocalDate date
    ) {
    }

    private record DamageHit(
            String itemId,
            String productName,
            String sku,
            BigDecimal packSize,
            String packUnit,
            BigDecimal qty,
            Instant at
    ) {
    }

    private record TillHit(
            String itemId,
            String productName,
            String sku,
            BigDecimal packSize,
            String packUnit,
            BigDecimal qty,
            Instant at
    ) {
    }

    private record StockHit(
            String itemId,
            String productName,
            String sku,
            BigDecimal packSize,
            String packUnit,
            BigDecimal onHand
    ) {
    }
}
