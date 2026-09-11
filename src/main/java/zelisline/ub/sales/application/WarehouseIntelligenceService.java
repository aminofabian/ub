package zelisline.ub.sales.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
import zelisline.ub.catalog.application.ProductDisplayName;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.api.dto.CompanionSkuRow;
import zelisline.ub.sales.api.dto.CustomerItemRhythmResponse;
import zelisline.ub.sales.api.dto.CustomerItemRhythmRow;
import zelisline.ub.sales.api.dto.CustomerProductSegmentRow;
import zelisline.ub.sales.api.dto.ItemMonthBucket;
import zelisline.ub.sales.api.dto.ItemSeasonalityResponse;
import zelisline.ub.sales.api.dto.SimilarBuyersResponse;

@Service
@RequiredArgsConstructor
public class WarehouseIntelligenceService {

    private static final BigDecimal QTY_ZERO = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    private static final BigDecimal MONEY_ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final int RHYTHM_LIMIT = 10;
    private static final int COMPANION_LIMIT = 8;
    private static final int SIMILAR_DEFAULT_LIMIT = 200;
    private static final int SIMILAR_MAX_LIMIT = 500;

    private static final String PHONE_SUB = """
            (SELECT p.phone
               FROM customer_phones p
              WHERE p.customer_id = c.id
                AND p.business_id = c.business_id
                AND p.phone IS NOT NULL
                AND p.phone <> ''
              ORDER BY p.is_primary DESC, p.created_at ASC
              LIMIT 1)
            """;

    private static final String Q_RHYTHM_DAYS = """
            SELECT sil.item_id,
                   MAX(i.name) AS item_name,
                   MAX(i.sku) AS item_sku,
                   CAST(s.sold_at AS DATE) AS sold_day,
                   COUNT(DISTINCT s.id) AS sale_count,
                   MAX(s.sold_at) AS last_at
              FROM sales s
              JOIN sale_items sil ON sil.sale_id = s.id
              JOIN items i ON i.id = sil.item_id AND i.business_id = s.business_id AND i.deleted_at IS NULL
             WHERE s.business_id = ?
               AND s.customer_id = ?
               AND s.status IN (?, ?)
               AND sil.item_id IS NOT NULL
               AND sil.item_id <> ''
               AND (? IS NULL OR sil.item_id = ?)
             GROUP BY sil.item_id, CAST(s.sold_at AS DATE)
            """;

    private static final String Q_LINKED_SALES_CUSTOMER = """
            SELECT COUNT(*) FROM sales s
             WHERE s.business_id = ?
               AND s.customer_id = ?
               AND s.status IN (?, ?)
            """;

    private static final String Q_MONTHLY_QTY = """
            SELECT EXTRACT(YEAR FROM CAST(s.sold_at AS DATE)) AS yr,
                   EXTRACT(MONTH FROM CAST(s.sold_at AS DATE)) AS mo,
                   COALESCE(SUM(si.quantity), 0) AS qty
              FROM sales s
              JOIN sale_items si ON si.sale_id = s.id
             WHERE s.business_id = ?
               AND si.item_id = ?
               AND s.status = ?
               AND CAST(s.sold_at AS DATE) BETWEEN ? AND ?
               AND (? IS NULL OR s.branch_id = ?)
             GROUP BY EXTRACT(YEAR FROM CAST(s.sold_at AS DATE)),
                      EXTRACT(MONTH FROM CAST(s.sold_at AS DATE))
            """;

    private static final String Q_LINKED_SALES_ITEM = """
            SELECT COUNT(DISTINCT s.id)
              FROM sales s
              JOIN sale_items si ON si.sale_id = s.id
             WHERE s.business_id = ?
               AND si.item_id = ?
               AND s.status = ?
               AND s.customer_id IS NOT NULL
               AND (? IS NULL OR s.branch_id = ?)
            """;

    private static final String Q_COMPANIONS = """
            SELECT i.id AS item_id,
                   i.name AS item_name,
                   i.sku AS item_sku,
                   COUNT(DISTINCT s.id) AS together_count
              FROM sale_items seed
              JOIN sales s ON s.id = seed.sale_id
              JOIN sale_items other ON other.sale_id = seed.sale_id AND other.item_id <> seed.item_id
              JOIN items i ON i.id = other.item_id AND i.business_id = s.business_id AND i.deleted_at IS NULL
             WHERE seed.item_id = ?
               AND s.business_id = ?
               AND s.status = ?
               AND s.customer_id IS NOT NULL
               AND (? IS NULL OR s.branch_id = ?)
             GROUP BY i.id, i.name, i.sku
             ORDER BY together_count DESC
             LIMIT ?
            """;

    private static final String Q_SIMILAR_CATEGORY = similarSql("i.category_id = ?");
    private static final String Q_SIMILAR_AISLE = similarSql("i.aisle_id = ?");
    private static final String Q_SIMILAR_BRAND = similarSql("LOWER(TRIM(i.brand)) = ?");

    private static String similarSql(String matchPredicate) {
        return """
                SELECT c.id AS customer_id,
                       c.customer_no,
                       c.name,
                       """ + PHONE_SUB + """
                       AS primary_phone,
                       COUNT(DISTINCT s.id) AS purchase_count,
                       COALESCE(SUM(sil.line_total), 0) AS spend_on_item,
                       MAX(s.sold_at) AS last_purchase_at
                  FROM sales s
                  JOIN sale_items sil ON sil.sale_id = s.id
                  JOIN items i ON i.id = sil.item_id AND i.business_id = s.business_id AND i.deleted_at IS NULL
                  JOIN customers c ON c.id = s.customer_id AND c.business_id = s.business_id
                 WHERE s.business_id = ?
                   AND """ + matchPredicate + """
                   AND s.status IN (?, ?)
                   AND CAST(s.sold_at AS DATE) BETWEEN ? AND ?
                   AND s.customer_id IS NOT NULL
                   AND c.deleted_at IS NULL
                   AND c.anonymised_at IS NULL
                   AND (? IS NULL OR s.branch_id = ?)
                 GROUP BY c.id, c.customer_no, c.name
                 ORDER BY last_purchase_at DESC
                 LIMIT ?
                """;
    }

    private final JdbcTemplate jdbc;
    private final ItemRepository itemRepository;

    @Transactional(readOnly = true)
    public CustomerItemRhythmResponse customerItemRhythm(
            String businessId,
            String customerId,
            String itemId
    ) {
        if (customerId == null || customerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "customerId is required");
        }
        String customer = customerId.trim();
        String itemFilter = blankToNull(itemId);
        Long linked = jdbc.queryForObject(
                Q_LINKED_SALES_CUSTOMER,
                Long.class,
                businessId,
                customer,
                SalesConstants.SALE_STATUS_COMPLETED,
                SalesConstants.SALE_STATUS_REFUNDED);
        long linkedSaleCount = linked == null ? 0L : linked;

        Map<String, Acc> byItem = new HashMap<>();
        jdbc.query(
                Q_RHYTHM_DAYS,
                rs -> {
                    String id = rs.getString("item_id");
                    Acc acc = byItem.get(id);
                    if (acc == null) {
                        acc = new Acc(rs.getString("item_name"), rs.getString("item_sku"));
                        byItem.put(id, acc);
                    }
                    Date day = rs.getDate("sold_day");
                    if (day != null) {
                        acc.days.add(day.toLocalDate());
                    }
                    acc.purchaseCount += rs.getLong("sale_count");
                    java.sql.Timestamp ts = rs.getTimestamp("last_at");
                    if (ts != null) {
                        Instant at = ts.toInstant();
                        if (acc.lastAt == null || at.isAfter(acc.lastAt)) {
                            acc.lastAt = at;
                        }
                    }
                },
                businessId,
                customer,
                SalesConstants.SALE_STATUS_COMPLETED,
                SalesConstants.SALE_STATUS_REFUNDED,
                itemFilter,
                itemFilter);

        LocalDate asOf = LocalDate.now(ZoneOffset.UTC);
        List<CustomerItemRhythmRow> rows = new ArrayList<>();
        for (Map.Entry<String, Acc> e : byItem.entrySet()) {
            Acc acc = e.getValue();
            if (acc.purchaseCount < CustomerItemRhythmMath.MIN_PURCHASES) {
                continue;
            }
            Integer median = CustomerItemRhythmMath.medianGapDays(acc.days);
            LocalDate lastDay = acc.lastAt == null
                    ? null
                    : acc.lastAt.atZone(ZoneOffset.UTC).toLocalDate();
            int daysSince = lastDay == null ? 0 : (int) Math.max(0, ChronoUnit.DAYS.between(lastDay, asOf));
            boolean due = CustomerItemRhythmMath.dueish(daysSince, median);
            rows.add(new CustomerItemRhythmRow(
                    e.getKey(),
                    acc.name == null || acc.name.isBlank() ? "Item" : acc.name.trim(),
                    acc.sku,
                    acc.purchaseCount,
                    median,
                    acc.lastAt,
                    daysSince,
                    due,
                    CustomerItemRhythmMath.describe(median, daysSince, due)));
        }
        rows.sort(Comparator
                .comparing(CustomerItemRhythmRow::dueish).reversed()
                .thenComparing(Comparator.comparingLong(CustomerItemRhythmRow::purchaseCount).reversed()));
        if (rows.size() > RHYTHM_LIMIT) {
            rows = new ArrayList<>(rows.subList(0, RHYTHM_LIMIT));
        }
        return new CustomerItemRhythmResponse(customer, linkedSaleCount, List.copyOf(rows));
    }

    @Transactional(readOnly = true)
    public ItemSeasonalityResponse itemSeasonality(
            String businessId,
            String itemId,
            String branchId,
            String view
    ) {
        Item item = requireItem(businessId, itemId);
        String branchFilter = blankToNull(branchId);
        String resolvedView = normalizeView(view);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        YearMonth cursor;
        int bucketCount;
        if ("thisYear".equals(resolvedView)) {
            cursor = YearMonth.of(today.getYear(), 1);
            bucketCount = 12;
        } else if ("lastYear".equals(resolvedView)) {
            cursor = YearMonth.of(today.getYear() - 1, 1);
            bucketCount = 12;
        } else {
            cursor = YearMonth.from(today).minusMonths(11);
            bucketCount = 12;
        }
        LocalDate from = cursor.atDay(1);
        LocalDate to = cursor.plusMonths(bucketCount - 1).atEndOfMonth();
        if (to.isAfter(today)) {
            to = today;
        }

        Map<Long, BigDecimal> qtyByYm = new HashMap<>();
        jdbc.query(
                Q_MONTHLY_QTY,
                rs -> {
                    int year = (int) rs.getDouble("yr");
                    int month = (int) rs.getDouble("mo");
                    BigDecimal qty = rs.getBigDecimal("qty");
                    qtyByYm.put(
                            ymKey(year, month),
                            qty == null ? QTY_ZERO : qty.setScale(4, RoundingMode.HALF_UP));
                },
                businessId,
                item.getId(),
                SalesConstants.SALE_STATUS_COMPLETED,
                Date.valueOf(from),
                Date.valueOf(to),
                branchFilter,
                branchFilter);

        List<BigDecimal> qtyForPeaks = new ArrayList<>(bucketCount);
        List<ItemMonthBucket> months = new ArrayList<>(bucketCount);
        YearMonth walk = cursor;
        for (int i = 0; i < bucketCount; i++) {
            BigDecimal qty = qtyByYm.getOrDefault(ymKey(walk.getYear(), walk.getMonthValue()), QTY_ZERO);
            qtyForPeaks.add(qty);
            walk = walk.plusMonths(1);
        }
        List<Integer> peaksByIndex = ItemSeasonalityMath.peakMonths(qtyForPeaks);
        walk = cursor;
        for (int i = 0; i < bucketCount; i++) {
            int month = walk.getMonthValue();
            boolean peak = peaksByIndex.contains(i + 1);
            months.add(new ItemMonthBucket(
                    walk.getYear(),
                    month,
                    ItemSeasonalityMath.monthLabel(month),
                    qtyForPeaks.get(i),
                    peak));
            walk = walk.plusMonths(1);
        }
        List<Integer> calendarPeaks = months.stream()
                .filter(ItemMonthBucket::peak)
                .map(ItemMonthBucket::month)
                .distinct()
                .toList();
        String peakLabel = calendarPeaks.isEmpty()
                ? null
                : calendarPeaks.stream()
                        .map(ItemSeasonalityMath::monthLabel)
                        .reduce((a, b) -> a + ", " + b)
                        .orElse(null);

        Long linked = jdbc.queryForObject(
                Q_LINKED_SALES_ITEM,
                Long.class,
                businessId,
                item.getId(),
                SalesConstants.SALE_STATUS_COMPLETED,
                branchFilter,
                branchFilter);
        long linkedSaleCount = linked == null ? 0L : linked;

        List<CompanionSkuRow> companions = new ArrayList<>();
        jdbc.query(
                Q_COMPANIONS,
                rs -> {
                    companions.add(new CompanionSkuRow(
                            rs.getString("item_id"),
                            rs.getString("item_name"),
                            rs.getString("item_sku"),
                            rs.getLong("together_count")));
                },
                item.getId(),
                businessId,
                SalesConstants.SALE_STATUS_COMPLETED,
                branchFilter,
                branchFilter,
                COMPANION_LIMIT);

        return new ItemSeasonalityResponse(
                item.getId(),
                composedName(item),
                item.getSku(),
                resolvedView,
                List.copyOf(months),
                calendarPeaks,
                peakLabel,
                linkedSaleCount,
                List.copyOf(companions));
    }

    @Transactional(readOnly = true)
    public SimilarBuyersResponse similarBuyers(
            String businessId,
            String itemId,
            LocalDate fromInclusive,
            LocalDate toInclusive,
            String branchId,
            Integer limit
    ) {
        Item item = requireItem(businessId, itemId);
        LocalDate toEx = toInclusive != null ? toInclusive : LocalDate.now(ZoneOffset.UTC);
        LocalDate fromEx = fromInclusive != null ? fromInclusive : toEx.minusDays(90);
        if (fromEx.isAfter(toEx)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date range");
        }
        String branchFilter = blankToNull(branchId);
        int rowLimit = limit == null ? SIMILAR_DEFAULT_LIMIT : Math.max(1, Math.min(limit, SIMILAR_MAX_LIMIT));

        String basis;
        String matchValue;
        String matchLabel;
        String sql;
        if (notBlank(item.getCategoryId())) {
            basis = "category";
            matchValue = item.getCategoryId().trim();
            matchLabel = lookupName("SELECT name FROM categories WHERE id = ? AND business_id = ?", matchValue, businessId);
            sql = Q_SIMILAR_CATEGORY;
        } else if (notBlank(item.getAisleId())) {
            basis = "aisle";
            matchValue = item.getAisleId().trim();
            matchLabel = lookupName("SELECT name FROM aisles WHERE id = ? AND business_id = ?", matchValue, businessId);
            sql = Q_SIMILAR_AISLE;
        } else if (notBlank(item.getBrand())) {
            basis = "brand";
            matchValue = item.getBrand().trim().toLowerCase();
            matchLabel = item.getBrand().trim();
            sql = Q_SIMILAR_BRAND;
        } else {
            return new SimilarBuyersResponse(
                    item.getId(),
                    "none",
                    null,
                    "Set a category on this product to find likely buyers.",
                    List.of());
        }

        List<CustomerProductSegmentRow> rows = new ArrayList<>();
        jdbc.query(
                sql,
                rs -> {
                    long customerNo = rs.getLong("customer_no");
                    Long customerNoOrNull = rs.wasNull() ? null : customerNo;
                    BigDecimal spend = rs.getBigDecimal("spend_on_item");
                    java.sql.Timestamp ts = rs.getTimestamp("last_purchase_at");
                    rows.add(new CustomerProductSegmentRow(
                            rs.getString("customer_id"),
                            customerNoOrNull,
                            rs.getString("name"),
                            rs.getString("primary_phone"),
                            rs.getLong("purchase_count"),
                            spend == null ? MONEY_ZERO : spend.setScale(2, RoundingMode.HALF_UP),
                            ts == null ? Instant.EPOCH : ts.toInstant()));
                },
                businessId,
                matchValue,
                SalesConstants.SALE_STATUS_COMPLETED,
                SalesConstants.SALE_STATUS_REFUNDED,
                Date.valueOf(fromEx),
                Date.valueOf(toEx),
                branchFilter,
                branchFilter,
                rowLimit);

        String hint = rows.isEmpty()
                ? "No named shoppers bought this " + basis + " in the window."
                : "People who already buy this " + basis + (matchLabel != null ? " (" + matchLabel + ")" : "") + ".";
        return new SimilarBuyersResponse(item.getId(), basis, matchLabel, hint, List.copyOf(rows));
    }

    private Item requireItem(String businessId, String itemId) {
        if (itemId == null || itemId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "itemId is required");
        }
        return itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId.trim(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
    }

    private String composedName(Item item) {
        String parentName = null;
        String parentId = item.getVariantOfItemId();
        if (parentId != null && !parentId.isBlank()) {
            parentName = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(parentId, item.getBusinessId())
                    .map(Item::getName)
                    .orElse(null);
        }
        String composed = ProductDisplayName.forVariant(item, parentName);
        if (composed == null || composed.isBlank()) {
            return item.getName() != null ? item.getName() : "Item";
        }
        return composed;
    }

    private String lookupName(String sql, String id, String businessId) {
        List<String> names = jdbc.query(sql, (rs, i) -> rs.getString(1), id, businessId);
        if (names.isEmpty() || names.get(0) == null || names.get(0).isBlank()) {
            return null;
        }
        return names.get(0).trim();
    }

    private static String normalizeView(String view) {
        if (view == null || view.isBlank()) {
            return "rolling12";
        }
        String v = view.trim();
        if ("thisYear".equals(v) || "lastYear".equals(v) || "rolling12".equals(v)) {
            return v;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "view must be rolling12, thisYear, or lastYear");
    }

    private static long ymKey(int year, int month) {
        return year * 12L + month;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String blankToNull(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    private static final class Acc {
        final String name;
        final String sku;
        final List<LocalDate> days = new ArrayList<>();
        long purchaseCount;
        Instant lastAt;

        Acc(String name, String sku) {
            this.name = name;
            this.sku = sku;
        }
    }
}
