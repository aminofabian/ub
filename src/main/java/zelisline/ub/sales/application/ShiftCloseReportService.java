package zelisline.ub.sales.application;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.application.MailAttachment;
import zelisline.ub.identity.application.NotificationService;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.platform.realtime.RealtimeBridge.ShiftClosedEvent;
import zelisline.ub.pricing.application.PricingService;
import zelisline.ub.sales.application.ShiftCloseReportEmail.Letter;
import zelisline.ub.sales.application.ShiftCloseReportEmail.StockRow;
import zelisline.ub.sales.application.ShiftCloseReportEmail.Tender;
import zelisline.ub.sales.application.ShiftCloseSheetPdf.SaleLine;
import zelisline.ub.sales.application.ShiftCloseSheetPdf.Slip;
import zelisline.ub.tenancy.application.FeatureFlagService;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * After a shift commits, email the owner the branch stock sheet and today's sales.
 * Opt-out is the feature flag {@code shifts.email_close_reports} set to false.
 */
@Service
@RequiredArgsConstructor
public class ShiftCloseReportService {

    private static final Logger log = LoggerFactory.getLogger(ShiftCloseReportService.class);
    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final String OWNER = "owner";

    private static final int SLIP_LINE_CAP = 400;
    private static final String PDF = "application/pdf";

    private static final String STOCK_SQL = """
            SELECT i.id AS id,
                   i.name AS name,
                   c.name AS category,
                   i.buying_price AS buy_price,
                   i.bundle_price AS bundle_price,
                   COALESCE(SUM(b.quantity_remaining), 0) AS stock
              FROM items i
              LEFT JOIN categories c
                ON c.id = i.category_id
               AND c.business_id = i.business_id
              LEFT JOIN inventory_batches b
                ON b.item_id = i.id
               AND b.business_id = i.business_id
               AND b.branch_id = ?
               AND b.status = 'active'
             WHERE i.business_id = ?
               AND i.deleted_at IS NULL
               AND i.active = 1
               AND (i.is_sellable = 1 OR i.is_stocked = 1)
             GROUP BY i.id, i.name, c.name, i.buying_price, i.bundle_price
             ORDER BY c.name, i.name
             LIMIT 501
            """;

    private static final String SLIP_SQL = """
            SELECT s.id AS sale_id,
                   s.receipt_no AS receipt_no,
                   s.sold_at AS sold_at,
                   s.grand_total AS sale_total,
                   COALESCE(NULLIF(TRIM(si.display_name), ''), NULLIF(TRIM(si.line_label), ''), 'Item') AS item_name,
                   si.quantity AS qty,
                   si.line_total AS line_total
              FROM sales s
              JOIN sale_items si ON si.sale_id = s.id
             WHERE s.business_id = ?
               AND s.branch_id = ?
               AND s.status = 'completed'
               AND s.voided_at IS NULL
               AND s.sold_at >= ?
               AND s.sold_at < ?
             ORDER BY s.sold_at, s.receipt_no, si.line_index
             LIMIT 401
            """;

    private static final String SLIP_TENDER_SQL = """
            SELECT sp.sale_id AS sale_id,
                   sp.method AS method,
                   COALESCE(SUM(sp.amount), 0) AS amount
              FROM sale_payments sp
              JOIN sales s ON s.id = sp.sale_id
             WHERE s.business_id = ?
               AND s.branch_id = ?
               AND s.status = 'completed'
               AND s.voided_at IS NULL
               AND s.sold_at >= ?
               AND s.sold_at < ?
             GROUP BY sp.sale_id, sp.method
             ORDER BY sp.sale_id, amount DESC
            """;

    private static final String TOTAL_SQL = """
            SELECT COUNT(*), COALESCE(SUM(s.grand_total), 0)
              FROM sales s
             WHERE s.business_id = ?
               AND s.branch_id = ?
               AND s.status = 'completed'
               AND s.voided_at IS NULL
               AND s.sold_at >= ?
               AND s.sold_at < ?
            """;

    private static final String TENDER_SQL = """
            SELECT sp.method AS method, COALESCE(SUM(sp.amount), 0) AS amount
              FROM sale_payments sp
              JOIN sales s ON s.id = sp.sale_id
             WHERE s.business_id = ?
               AND s.branch_id = ?
               AND s.status = 'completed'
               AND s.voided_at IS NULL
               AND s.sold_at >= ?
               AND s.sold_at < ?
             GROUP BY sp.method
             ORDER BY amount DESC
            """;

    private final FeatureFlagService featureFlagService;
    private final NotificationService notificationService;
    private final BusinessRepository businessRepository;
    private final BranchRepository branchRepository;
    private final UserRepository userRepository;
    private final PricingService pricingService;
    private final JdbcTemplate jdbcTemplate;

    public void send(ShiftClosedEvent event) {
        if (!featureFlagService.isEnabledDefaultTrue(
                event.businessId(), FeatureFlagService.FLAG_SHIFTS_EMAIL_CLOSE_REPORTS)) {
            return;
        }
        if (!notificationService.canDeliverEmail()) {
            log.info("Shift close report skipped; no mail provider shift={}", event.shiftId());
            return;
        }
        Business business = businessRepository.findById(event.businessId()).orElse(null);
        if (business == null) {
            return;
        }
        List<String> recipients = recipients(event);
        if (recipients.isEmpty()) {
            log.info("Shift close report skipped; no owner email shift={}", event.shiftId());
            return;
        }
        dispatch(event, business, recipients);
    }

    private void dispatch(ShiftClosedEvent event, Business business, List<String> recipients) {
        String shop = business.getName() == null || business.getName().isBlank() ? "Shop" : business.getName().trim();
        String branch = branchName(event.businessId(), event.branchId());
        ZoneId zone = zone(business.getTimezone());
        LocalDate today = LocalDate.now(zone);
        String when = DAY.format(today);
        StockPack stock = stockPack(event);
        SalesDay sales = salesDay(event, today, zone);
        Letter cover = ShiftCloseReportEmail.cover(
                shop, branch, when, sales.count(), sales.total(), sales.tenders());
        List<MailAttachment> files = attachments(event.shiftId(), shop, branch, when, stock, sales);
        for (String email : recipients) {
            deliver(email, cover, shop, files, event.shiftId());
        }
    }

    private void deliver(String email, Letter letter, String shop, List<MailAttachment> files, String shiftId) {
        try {
            notificationService.sendNotificationEmail(
                    email, letter.subject(), letter.text(), letter.html(), shop, files);
        } catch (RuntimeException ex) {
            log.warn("Shift close report failed shift={} to={}", shiftId, email, ex);
        }
    }

    private List<MailAttachment> attachments(
            String shiftId, String shop, String branch, String when, StockPack stock, SalesDay sales) {
        List<MailAttachment> files = new ArrayList<>();
        addPdf(files, shiftId, shop, "stock-buy-sell", when, () -> ShiftCloseSheetPdf.stock(
                shop, branch, when, stock.rows(), stock.truncated()));
        addPdf(files, shiftId, shop, "sales", when, () -> ShiftCloseSheetPdf.sales(
                shop, branch, when, sales.count(), sales.total(), sales.tenders(),
                sales.slips(), sales.truncated()));
        return files;
    }

    private void addPdf(
            List<MailAttachment> files, String shiftId, String shop, String stem, String when, PdfSource source) {
        try {
            files.add(new MailAttachment(fileName(shop, stem, when), source.bytes(), PDF));
        } catch (RuntimeException ex) {
            log.warn("Shift close PDF failed stem={} shift={}", stem, shiftId, ex);
        }
    }

    @FunctionalInterface
    private interface PdfSource {
        byte[] bytes();
    }

    private StockPack stockPack(ShiftClosedEvent event) {
        List<RawStock> raw = loadStock(event);
        int shown = Math.min(raw.size(), ShiftCloseReportEmail.STOCK_CAP);
        return new StockPack(priced(event, raw.subList(0, shown)), raw.size() > shown);
    }

    private List<StockRow> priced(ShiftClosedEvent event, List<RawStock> page) {
        Map<String, BigDecimal> selling = sellingPrices(event, page);
        List<StockRow> rows = new ArrayList<>();
        for (RawStock item : page) {
            BigDecimal sell = selling.get(item.id());
            rows.add(new StockRow(
                    item.name(),
                    item.stock(),
                    item.buyPrice(),
                    sell != null ? sell : item.bundlePrice(),
                    item.category()));
        }
        return rows;
    }

    private List<RawStock> loadStock(ShiftClosedEvent event) {
        return jdbcTemplate.query(
                STOCK_SQL,
                (rs, row) -> new RawStock(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("category"),
                        rs.getBigDecimal("stock"),
                        rs.getBigDecimal("buy_price"),
                        rs.getBigDecimal("bundle_price")),
                event.branchId(),
                event.businessId());
    }

    private Map<String, BigDecimal> sellingPrices(ShiftClosedEvent event, List<RawStock> rows) {
        List<String> ids = rows.stream().map(RawStock::id).toList();
        try {
            return pricingService.getCurrentOpenSellingPricesForItems(event.businessId(), event.branchId(), ids);
        } catch (RuntimeException ex) {
            log.warn("Shift close stock prices fell back to shelf price shift={}", event.shiftId(), ex);
            return Map.of();
        }
    }

    private SalesDay salesDay(ShiftClosedEvent event, LocalDate today, ZoneId zone) {
        Instant from = today.atStartOfDay(zone).toInstant();
        Instant until = today.plusDays(1).atStartOfDay(zone).toInstant();
        DayTotal totals = dayTotal(event, from, until);
        SlipPack slips = slips(event, from, until, zone);
        return new SalesDay(totals.count(), totals.total(), tenders(event, from, until), slips.slips(), slips.truncated());
    }

    private SlipPack slips(ShiftClosedEvent event, Instant from, Instant until, ZoneId zone) {
        List<RawLine> lines = slipLines(event, from, until);
        boolean truncated = lines.size() > SLIP_LINE_CAP;
        List<RawLine> shown = truncated ? lines.subList(0, SLIP_LINE_CAP) : lines;
        List<Slip> grouped = groupSlips(shown, slipTenders(event, from, until), zone);
        if (truncated && grouped.size() > 1) {
            grouped.remove(grouped.size() - 1);
        }
        return new SlipPack(grouped, truncated);
    }

    private List<RawLine> slipLines(ShiftClosedEvent event, Instant from, Instant until) {
        return jdbcTemplate.query(
                SLIP_SQL,
                (rs, row) -> new RawLine(
                        rs.getString("sale_id"),
                        rs.getObject("receipt_no") == null ? null : rs.getLong("receipt_no"),
                        rs.getTimestamp("sold_at").toInstant(),
                        rs.getBigDecimal("sale_total"),
                        rs.getString("item_name"),
                        rs.getBigDecimal("qty"),
                        rs.getBigDecimal("line_total")),
                event.businessId(),
                event.branchId(),
                Timestamp.from(from),
                Timestamp.from(until));
    }

    private Map<String, String> slipTenders(ShiftClosedEvent event, Instant from, Instant until) {
        Map<String, StringBuilder> bySale = new LinkedHashMap<>();
        jdbcTemplate.query(SLIP_TENDER_SQL, rs -> {
            String saleId = rs.getString("sale_id");
            String piece = ShiftCloseReportEmail.tenderLabel(rs.getString("method"))
                    + " " + ShiftCloseReportEmail.money(rs.getBigDecimal("amount"));
            bySale.computeIfAbsent(saleId, ignored -> new StringBuilder());
            if (bySale.get(saleId).length() > 0) {
                bySale.get(saleId).append("  -  ");
            }
            bySale.get(saleId).append(piece);
        }, event.businessId(), event.branchId(), Timestamp.from(from), Timestamp.from(until));
        Map<String, String> labels = new LinkedHashMap<>();
        bySale.forEach((saleId, label) -> labels.put(saleId, label.toString()));
        return labels;
    }

    private static List<Slip> groupSlips(List<RawLine> lines, Map<String, String> tenders, ZoneId zone) {
        Map<String, List<RawLine>> grouped = new LinkedHashMap<>();
        for (RawLine line : lines) {
            grouped.computeIfAbsent(line.saleId(), ignored -> new ArrayList<>()).add(line);
        }
        List<Slip> slips = new ArrayList<>();
        for (List<RawLine> sale : grouped.values()) {
            RawLine first = sale.get(0);
            slips.add(new Slip(slipHeading(first, tenders.get(first.saleId()), zone), first.saleTotal(), slipLines(sale)));
        }
        return slips;
    }

    private static List<SaleLine> slipLines(List<RawLine> sale) {
        List<SaleLine> lines = new ArrayList<>();
        for (RawLine line : sale) {
            lines.add(new SaleLine(line.item(), line.qty(), line.lineTotal()));
        }
        return lines;
    }

    private static String slipHeading(RawLine line, String tender, ZoneId zone) {
        String clock = CLOCK.withZone(zone).format(line.soldAt());
        String receipt = line.receiptNo() == null ? "Sale" : "Receipt " + line.receiptNo();
        if (tender == null || tender.isBlank()) {
            return clock + "    " + receipt;
        }
        return clock + "    " + receipt + "    " + tender;
    }

    private DayTotal dayTotal(ShiftClosedEvent event, Instant from, Instant until) {
        return jdbcTemplate.query(TOTAL_SQL, rs -> {
            if (!rs.next()) {
                return new DayTotal(0, BigDecimal.ZERO);
            }
            BigDecimal total = rs.getBigDecimal(2);
            return new DayTotal(rs.getInt(1), total == null ? BigDecimal.ZERO : total);
        }, event.businessId(), event.branchId(), Timestamp.from(from), Timestamp.from(until));
    }

    private List<Tender> tenders(ShiftClosedEvent event, Instant from, Instant until) {
        return jdbcTemplate.query(
                TENDER_SQL,
                (rs, row) -> new Tender(rs.getString("method"), rs.getBigDecimal("amount")),
                event.businessId(),
                event.branchId(),
                Timestamp.from(from),
                Timestamp.from(until));
    }

    private List<String> recipients(ShiftClosedEvent event) {
        Set<String> emails = new LinkedHashSet<>();
        for (User owner : userRepository.findActiveByRoleKeyOrderByCreatedAtAsc(event.businessId(), OWNER)) {
            addEmail(emails, owner.getEmail());
        }
        if (emails.isEmpty() && event.closedBy() != null) {
            userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(event.closedBy(), event.businessId())
                    .ifPresent(user -> addEmail(emails, user.getEmail()));
        }
        return List.copyOf(emails);
    }

    private static void addEmail(Set<String> emails, String raw) {
        if (raw != null && !raw.isBlank()) {
            emails.add(raw.trim());
        }
    }

    private String branchName(String businessId, String branchId) {
        return branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(branchId, businessId)
                .map(Branch::getName)
                .filter(name -> name != null && !name.isBlank())
                .orElse("Branch");
    }

    private static ZoneId zone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return NAIROBI;
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (RuntimeException ex) {
            return NAIROBI;
        }
    }

    private static String fileName(String shop, String stem, String when) {
        String slug = shop.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        String day = when.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (slug.isEmpty()) {
            slug = "shop";
        }
        return slug + "-" + stem + "-" + day + ".pdf";
    }

    private record RawStock(
            String id, String name, String category, BigDecimal stock, BigDecimal buyPrice, BigDecimal bundlePrice) {
    }

    private record RawLine(
            String saleId,
            Long receiptNo,
            Instant soldAt,
            BigDecimal saleTotal,
            String item,
            BigDecimal qty,
            BigDecimal lineTotal) {
    }

    private record DayTotal(int count, BigDecimal total) {
    }

    private record StockPack(List<StockRow> rows, boolean truncated) {
    }

    private record SalesDay(
            int count, BigDecimal total, List<Tender> tenders, List<Slip> slips, boolean truncated) {
    }

    private record SlipPack(List<Slip> slips, boolean truncated) {
    }
}
