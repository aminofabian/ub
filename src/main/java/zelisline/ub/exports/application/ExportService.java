package zelisline.ub.exports.application;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.UUID;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Category;
import zelisline.ub.catalog.repository.CategoryRepository;
import zelisline.ub.exports.api.dto.ExportCreateRequest;
import zelisline.ub.exports.api.dto.ExportJobResponse;
import zelisline.ub.exports.application.ReportTable.Align;
import zelisline.ub.exports.application.ReportTable.Column;
import zelisline.ub.exports.domain.ExportJob;
import zelisline.ub.exports.repository.ExportJobRepository;
import zelisline.ub.identity.application.TokenHasher;
import zelisline.ub.notifications.application.NotificationService;
import zelisline.ub.reporting.api.dto.InventoryValuationResponse;
import zelisline.ub.reporting.api.dto.SalesRegisterResponse;
import zelisline.ub.reporting.api.dto.SupplierMonthlySpendResponse;
import zelisline.ub.reporting.application.InventoryReportsService;
import zelisline.ub.reporting.application.SalesReportsService;
import zelisline.ub.reporting.application.SupplierReportsService;
import zelisline.ub.sales.api.dto.RevenueByCategoryRow;
import zelisline.ub.sales.application.SalesIntelligenceService;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Builds downloadable reports (CSV / XLSX / PDF) and serves them through a tokened,
 * short-lived link. The legacy {@code sales_register} report keeps its own writers;
 * every other report is produced as a neutral {@link ReportTable} and rendered by the
 * shared {@link ReportTableWriters}, so adding a report is a matter of one builder.
 *
 * <p>All data is scoped by the caller's {@code businessId} (resolved from the request
 * host/headers, never the body). The export permission ({@code reports.export}) gates
 * every report; it is an owner/admin-tier permission.</p>
 */
@Service
@RequiredArgsConstructor
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);

    public static final String REPORT_SALES_REGISTER = "sales_register";
    public static final String REPORT_SALES_BY_CATEGORY = "sales_by_category";
    public static final String REPORT_SUPPLIER_SPEND = "supplier_spend";
    public static final String REPORT_INVENTORY_VALUATION = "inventory_valuation";

    private static final String STATUS_PROCESSING = "processing";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_FAILED = "failed";

    private static final Duration DOWNLOAD_TTL = Duration.ofHours(1);
    private static final String DEFAULT_CURRENCY = "KES";
    private static final String ZONE = "Africa/Nairobi";

    private final ExportJobRepository exportJobRepository;
    private final SalesReportsService salesReportsService;
    private final SalesIntelligenceService salesIntelligenceService;
    private final SupplierReportsService supplierReportsService;
    private final InventoryReportsService inventoryReportsService;
    private final BranchRepository branchRepository;
    private final BusinessRepository businessRepository;
    private final CategoryRepository categoryRepository;
    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;

    @Transactional
    public ExportJobResponse create(String businessId, String userId, ExportCreateRequest req, String idempotencyKeyRaw) {
        validateWindow(req.from(), req.to());
        String reportKey = req.reportKey().trim().toLowerCase(Locale.ROOT);
        String format = req.format().trim().toLowerCase(Locale.ROOT);
        validateReportKey(reportKey);

        String idemHash = null;
        if (idempotencyKeyRaw != null && !idempotencyKeyRaw.isBlank()) {
            idemHash = TokenHasher.sha256Hex(idempotencyKeyRaw.trim());
            Optional<ExportJob> cached = exportJobRepository.findByBusinessIdAndIdempotencyKeyHash(businessId, idemHash);
            if (cached.isPresent()) {
                return toResponse(cached.get());
            }
        }

        ExportJobParams params = new ExportJobParams(
                req.from(), req.to(), blankToNull(req.branchId()),
                blankToNull(req.categoryId()), blankToNull(req.itemTypeId()));
        ExportJob job = new ExportJob();
        job.setBusinessId(businessId);
        job.setReportKey(reportKey);
        job.setFormat(format);
        job.setStatus(STATUS_PROCESSING);
        try {
            job.setParamsJson(objectMapper.writeValueAsString(params));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        job.setCreatedBy(userId);
        job.setIdempotencyKeyHash(idemHash);
        exportJobRepository.save(job);

        try {
            run(job, params);
            job.setStatus(STATUS_COMPLETED);
            try {
                var payload = new LinkedHashMap<String, Object>();
                payload.put("exportId", job.getId());
                payload.put("reportKey", job.getReportKey());
                String json = objectMapper.writeValueAsString(payload);
                notificationService.tryInsertDedupe(
                        businessId, "export.completed", "export:" + job.getId(), json);
            } catch (Exception e) {
                log.warn("Failed to create export notification for job {}: {}", job.getId(), e.getMessage());
            }
        } catch (Exception ex) {
            log.warn("Export failed jobId={} businessId={}", job.getId(), businessId, ex);
            job.setStatus(STATUS_FAILED);
            job.setErrorMessage(truncateMessage(ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
        }
        exportJobRepository.save(job);
        return toResponse(job);
    }

    @Transactional(readOnly = true)
    public ExportJobResponse getMetadata(String businessId, String jobId) {
        ExportJob job = exportJobRepository.findByIdAndBusinessId(jobId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Export not found"));
        return toResponse(job);
    }

    @Transactional(readOnly = true)
    public ExportDownload openDownload(String businessId, String jobId, String token) {
        ExportJob job = exportJobRepository.findByIdAndBusinessId(jobId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Export not found"));
        if (!STATUS_COMPLETED.equals(job.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Export is not ready");
        }
        if (job.getDownloadToken() == null || !job.getDownloadToken().equals(token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid download token");
        }
        if (job.getExpiresAt() == null || Instant.now().isAfter(job.getExpiresAt())) {
            throw new ResponseStatusException(HttpStatus.GONE, "Download link expired");
        }
        Path path = Paths.get(job.getStoragePath());
        if (!Files.isRegularFile(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Export file missing");
        }
        MediaType mt = switch (job.getFormat()) {
            case "xlsx" -> MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            case "pdf" -> MediaType.APPLICATION_PDF;
            default -> MediaType.parseMediaType("text/csv");
        };
        String filename = downloadFilename(job);
        return new ExportDownload(new FileSystemResource(path.toFile()), mt, filename);
    }

    // ---- report dispatch -------------------------------------------------

    private void run(ExportJob job, ExportJobParams params) throws IOException {
        if (REPORT_SALES_REGISTER.equals(job.getReportKey())) {
            runSalesRegister(job, params);
            return;
        }
        writeTable(job, buildReportTable(job.getBusinessId(), job.getReportKey(), params));
    }

    /** Builds the neutral table for a tabular report (every report except legacy sales_register). */
    public ReportTable buildReportTable(String businessId, String reportKey, ExportJobParams params) {
        return switch (reportKey) {
            case REPORT_SALES_BY_CATEGORY -> salesByCategoryTable(businessId, params);
            case REPORT_SUPPLIER_SPEND -> supplierSpendTable(businessId, params);
            case REPORT_INVENTORY_VALUATION -> inventoryValuationTable(businessId, params);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Report has no table form");
        };
    }

    private void writeTable(ExportJob job, ReportTable table) throws IOException {
        Path file = newExportFile(job);
        switch (job.getFormat()) {
            case "csv" -> ReportTableWriters.writeCsv(file, table);
            case "xlsx" -> ReportTableWriters.writeXlsx(file, table);
            case "pdf" -> ReportTableWriters.writePdf(file, table);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported format");
        }
        finalizeDownload(job, file);
    }

    private Path newExportFile(ExportJob job) throws IOException {
        Path dir = Paths.get(System.getProperty("java.io.tmpdir"), "ub-exports", job.getBusinessId());
        Files.createDirectories(dir);
        return dir.resolve(job.getId() + "." + job.getFormat());
    }

    private void finalizeDownload(ExportJob job, Path file) {
        job.setStoragePath(file.toAbsolutePath().toString());
        job.setDownloadToken(UUID.randomUUID().toString());
        job.setExpiresAt(Instant.now().plus(DOWNLOAD_TTL));
    }

    // ---- report builders -------------------------------------------------

    private ReportTable salesByCategoryTable(String businessId, ExportJobParams params) {
        Meta meta = resolveMeta(businessId);
        List<RevenueByCategoryRow> rows = salesIntelligenceService.netRevenueByCategory(
                businessId, params.from(), params.to(),
                params.categoryId(), params.branchId(), params.itemTypeId());

        List<List<ReportTable.Cell>> body = new ArrayList<>();
        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalProfit = BigDecimal.ZERO;
        for (RevenueByCategoryRow row : rows) {
            BigDecimal rev = nz(row.netRevenue());
            BigDecimal profit = nz(row.netProfit());
            totalRevenue = totalRevenue.add(rev);
            totalProfit = totalProfit.add(profit);
            body.add(List.of(
                    text(categoryName(row.categoryName())),
                    money(row.netRevenue(), meta.currency()),
                    money(row.netProfit(), meta.currency()),
                    pct(row.netRevenue(), row.netProfit())));
        }
        List<Column> columns = List.of(
                new Column("Category", Align.LEFT, 3.4f),
                new Column("Net revenue", Align.RIGHT, 1.5f),
                new Column("Net profit", Align.RIGHT, 1.5f),
                new Column("Margin", Align.RIGHT, 1.0f));
        List<ReportTable.Cell> totals = List.of(
                text("Total"), money(totalRevenue, meta.currency()),
                money(totalProfit, meta.currency()), pct(totalRevenue, totalProfit));
        List<String> metaLines = List.of(
                periodDays(params.from(), params.to()),
                branchLabel(businessId, params.branchId()) + "  ·  "
                        + categoryLabel(businessId, params.categoryId()));
        return new ReportTable("Sales by category", meta.businessName(), metaLines,
                generatedAt(), meta.currency(), columns, body, totals);
    }

    private ReportTable supplierSpendTable(String businessId, ExportJobParams params) {
        Meta meta = resolveMeta(businessId);
        SupplierMonthlySpendResponse resp = supplierReportsService.monthlySpend(
                businessId, params.from(), params.to());

        List<List<ReportTable.Cell>> body = new ArrayList<>();
        for (SupplierMonthlySpendResponse.Row row : resp.rows()) {
            body.add(List.of(
                    text(row.supplierName() == null || row.supplierName().isBlank()
                            ? "Unknown supplier" : row.supplierName()),
                    text(row.calendarMonth() == null ? "" : YearMonth.from(row.calendarMonth()).toString()),
                    money(row.spend(), meta.currency()),
                    num(row.qty()),
                    num(BigDecimal.valueOf(row.invoiceCount()))));
        }
        List<Column> columns = List.of(
                new Column("Supplier", Align.LEFT, 2.8f),
                new Column("Month", Align.LEFT, 1.1f),
                new Column("Spend", Align.RIGHT, 1.6f),
                new Column("Qty", Align.RIGHT, 1.0f),
                new Column("Invoices", Align.RIGHT, 1.0f));
        List<ReportTable.Cell> totals = List.of(
                text("Total"), blank(), money(resp.totalSpend(), meta.currency()),
                num(resp.totalQty()), blank());
        List<String> metaLines = List.of(periodMonths(params.from(), params.to()), "All suppliers");
        return new ReportTable("Supplier spend", meta.businessName(), metaLines,
                generatedAt(), meta.currency(), columns, body, totals);
    }

    private ReportTable inventoryValuationTable(String businessId, ExportJobParams params) {
        Meta meta = resolveMeta(businessId);
        InventoryValuationResponse resp = inventoryReportsService.valuation(businessId, params.branchId());

        List<List<ReportTable.Cell>> body = new ArrayList<>();
        for (InventoryValuationResponse.Row row : resp.rows()) {
            body.add(List.of(
                    text(row.itemName() == null || row.itemName().isBlank() ? "Item" : row.itemName()),
                    num(row.qtyOnHand()),
                    money(row.fifoValue(), meta.currency()),
                    text(row.earliestExpiry() == null ? "—" : row.earliestExpiry().toString())));
        }
        List<Column> columns = List.of(
                new Column("Item", Align.LEFT, 3.2f),
                new Column("Qty on hand", Align.RIGHT, 1.3f),
                new Column("FIFO value", Align.RIGHT, 1.6f),
                new Column("Earliest expiry", Align.RIGHT, 1.3f));
        List<ReportTable.Cell> totals = List.of(
                text("Total"), num(resp.totalQty()), money(resp.totalFifoValue(), meta.currency()), blank());
        String asOf = "As of " + generatedAt();
        List<String> metaLines = List.of(branchLabel(businessId, params.branchId()), asOf);
        return new ReportTable("Inventory valuation", meta.businessName(), metaLines,
                generatedAt(), meta.currency(), columns, body, totals);
    }

    // ---- meta + label resolution ----------------------------------------

    private record Meta(String businessName, String currency) {
    }

    private Meta resolveMeta(String businessId) {
        Business business = businessRepository.findByIdAndDeletedAtIsNull(businessId).orElse(null);
        if (business == null) {
            return new Meta(null, DEFAULT_CURRENCY);
        }
        String currency = business.getCurrency() != null && !business.getCurrency().isBlank()
                ? business.getCurrency().trim() : DEFAULT_CURRENCY;
        return new Meta(business.getName(), currency);
    }

    private String branchLabel(String businessId, String branchId) {
        if (branchId == null) {
            return "All branches";
        }
        return branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(branchId, businessId)
                .map(Branch::getName)
                .orElse("Branch");
    }

    private String categoryLabel(String businessId, String categoryId) {
        if (categoryId == null) {
            return "All categories";
        }
        return categoryRepository.findByIdAndBusinessId(categoryId, businessId)
                .map(Category::getName)
                .orElse("Category");
    }

    private static String generatedAt() {
        return DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)
                .format(Instant.now().atZone(ZoneId.of(ZONE))) + " EAT";
    }

    private static String periodDays(LocalDate from, LocalDate to) {
        DateTimeFormatter f = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
        return f.format(from) + " to " + f.format(to);
    }

    private static String periodMonths(LocalDate from, LocalDate to) {
        DateTimeFormatter f = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
        return f.format(from) + " to " + f.format(to);
    }

    // ---- cell factories --------------------------------------------------

    private static ReportTable.Cell text(String value) {
        return new ReportTable.Cell.Text(value);
    }

    private static ReportTable.Cell blank() {
        return new ReportTable.Cell.Blank();
    }

    private static ReportTable.Cell money(BigDecimal amount, String currency) {
        if (amount == null) {
            return new ReportTable.Cell.Money(null, "—");
        }
        return new ReportTable.Cell.Money(amount,
                currency + " " + moneyFormat().format(amount.setScale(2, RoundingMode.HALF_UP)));
    }

    private static ReportTable.Cell num(BigDecimal value) {
        if (value == null) {
            return new ReportTable.Cell.Num(null, "—");
        }
        return new ReportTable.Cell.Num(value, value.stripTrailingZeros().toPlainString());
    }

    private static ReportTable.Cell pct(BigDecimal revenue, BigDecimal profit) {
        if (revenue == null || revenue.signum() == 0 || profit == null) {
            return new ReportTable.Cell.Blank();
        }
        BigDecimal p = profit.multiply(BigDecimal.valueOf(100)).divide(revenue, 1, RoundingMode.HALF_UP);
        return new ReportTable.Cell.Num(p, p.toPlainString() + "%");
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static DecimalFormat moneyFormat() {
        DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format;
    }

    private static String categoryName(String name) {
        return name == null || name.isBlank() ? "Uncategorised" : name;
    }

    // ---- legacy sales_register (unchanged output contract) ---------------

    private void runSalesRegister(ExportJob job, ExportJobParams params) throws IOException {
        SalesRegisterResponse resp = salesReportsService.salesRegister(
                job.getBusinessId(), params.from(), params.to(), params.branchId());

        Path file = newExportFile(job);
        if ("csv".equals(job.getFormat())) {
            writeCsv(file, resp);
        } else if ("xlsx".equals(job.getFormat())) {
            writeXlsx(file, resp);
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported format");
        }
        finalizeDownload(job, file);
    }

    private static void writeCsv(Path file, SalesRegisterResponse resp) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write("day,branch_id,qty,revenue,cost,profit\n");
            for (SalesRegisterResponse.Day d : resp.days()) {
                w.write(csvCell(d.day().toString()));
                w.write(',');
                w.write(csvCell(d.branchId()));
                w.write(',');
                w.write(csvCell(qtyPlain(d.qty())));
                w.write(',');
                w.write(csvCell(moneyPlain(d.revenue())));
                w.write(',');
                w.write(csvCell(moneyPlain(d.cost())));
                w.write(',');
                w.write(csvCell(moneyPlain(d.profit())));
                w.write('\n');
            }
            w.write("TOTAL,,");
            w.write(csvCell(qtyPlain(resp.totalQty())));
            w.write(',');
            w.write(csvCell(moneyPlain(resp.totalRevenue())));
            w.write(',');
            w.write(csvCell(moneyPlain(resp.totalCost())));
            w.write(',');
            w.write(csvCell(moneyPlain(resp.totalProfit())));
            w.write('\n');
        }
    }

    private static void writeXlsx(Path file, SalesRegisterResponse resp) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("sales_register");
            Row header = sh.createRow(0);
            header.createCell(0).setCellValue("day");
            header.createCell(1).setCellValue("branch_id");
            header.createCell(2).setCellValue("qty");
            header.createCell(3).setCellValue("revenue");
            header.createCell(4).setCellValue("cost");
            header.createCell(5).setCellValue("profit");

            int r = 1;
            for (SalesRegisterResponse.Day d : resp.days()) {
                Row row = sh.createRow(r++);
                row.createCell(0).setCellValue(d.day().toString());
                Cell b = row.createCell(1);
                if (d.branchId() != null) {
                    b.setCellValue(d.branchId());
                }
                row.createCell(2).setCellValue(qtyPlain(d.qty()));
                row.createCell(3).setCellValue(moneyPlain(d.revenue()));
                row.createCell(4).setCellValue(moneyPlain(d.cost()));
                row.createCell(5).setCellValue(moneyPlain(d.profit()));
            }
            Row totals = sh.createRow(r);
            totals.createCell(0).setCellValue("TOTAL");
            totals.createCell(2).setCellValue(qtyPlain(resp.totalQty()));
            totals.createCell(3).setCellValue(moneyPlain(resp.totalRevenue()));
            totals.createCell(4).setCellValue(moneyPlain(resp.totalCost()));
            totals.createCell(5).setCellValue(moneyPlain(resp.totalProfit()));

            Files.createDirectories(file.getParent());
            try (var out = Files.newOutputStream(file)) {
                wb.write(out);
            }
        }
    }

    private static String csvCell(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static String moneyPlain(BigDecimal v) {
        return v == null ? "0.00" : v.toPlainString();
    }

    private static String qtyPlain(BigDecimal v) {
        return v == null ? "0.0000" : v.toPlainString();
    }

    // ---- shared helpers --------------------------------------------------

    // ---- summary (for display + WhatsApp/SMS) ----------------------------

    /** A short, display-and-send summary: headline tiles, top rows, ASCII-safe body. */
    public static ReportSummary summarize(ReportTable table) {
        List<ReportSummary.Highlight> highlights = new ArrayList<>();
        List<Column> cols = table.columns();
        List<ReportTable.Cell> totals = table.totals();
        if (totals != null) {
            for (int i = 0; i < cols.size() && i < totals.size(); i++) {
                Column col = cols.get(i);
                if (col.align() != Align.RIGHT) {
                    continue;
                }
                String v = totals.get(i) == null ? "" : totals.get(i).display();
                if (v == null || v.isBlank() || "\u2014".equals(v)) {
                    continue;
                }
                highlights.add(new ReportSummary.Highlight(col.header(), v));
                if (highlights.size() >= 4) {
                    break;
                }
            }
        }
        List<String[]> topPairs = new ArrayList<>();
        List<List<ReportTable.Cell>> rows = table.rows() == null ? List.of() : table.rows();
        int limit = Math.min(3, rows.size());
        for (int r = 0; r < limit; r++) {
            List<ReportTable.Cell> row = rows.get(r);
            topPairs.add(new String[] {row.isEmpty() ? "" : row.get(0).display(), firstSummaryValue(row)});
        }
        List<String> topLines = new ArrayList<>();
        for (String[] pair : topPairs) {
            topLines.add(pair[1].isEmpty() ? pair[0] : pair[0] + " \u2014 " + pair[1]);
        }

        StringBuilder sb = new StringBuilder();
        String name = table.businessName() == null || table.businessName().isBlank()
                ? "Your shop" : table.businessName();
        sb.append(asciiForMessage(name)).append(" - ")
                .append(asciiForMessage(table.title() == null ? "" : table.title())).append("\n");
        if (table.metaLines() != null && !table.metaLines().isEmpty()) {
            List<String> metas = new ArrayList<>();
            for (String meta : table.metaLines()) {
                metas.add(asciiForMessage(meta));
            }
            sb.append(String.join(" | ", metas)).append("\n");
        }
        sb.append("\n");
        for (ReportSummary.Highlight h : highlights) {
            sb.append(asciiForMessage(h.label())).append(": ").append(asciiForMessage(h.value())).append("\n");
        }
        if (!topPairs.isEmpty()) {
            sb.append("\nTop:\n");
            for (String[] pair : topPairs) {
                sb.append("- ").append(asciiForMessage(pair[0]));
                if (!pair[1].isEmpty()) {
                    sb.append(": ").append(asciiForMessage(pair[1]));
                }
                sb.append("\n");
            }
        }
        if (table.generatedAtLabel() != null && !table.generatedAtLabel().isBlank()) {
            sb.append("\nKiosk.ke - ").append(asciiForMessage(table.generatedAtLabel()));
        }
        return new ReportSummary(table.title() == null ? "" : table.title(), table.businessName(),
                table.metaLines(), highlights, topLines, sb.toString().trim());
    }

    private static String firstSummaryValue(List<ReportTable.Cell> row) {
        for (int i = 1; i < row.size(); i++) {
            if (row.get(i) instanceof ReportTable.Cell.Money m && m.amount() != null) {
                return m.display();
            }
        }
        for (int i = 1; i < row.size(); i++) {
            if (row.get(i) instanceof ReportTable.Cell.Num n && n.value() != null) {
                return n.display();
            }
        }
        return "";
    }

    /** ASCII-safe for SMS GSM-7: map typographic glyphs, drop other non-ASCII. */
    private static String asciiForMessage(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\u2018', '\u2019', '\u201A', '\u2032' -> out.append('\'');
                case '\u201C', '\u201D', '\u201E', '\u2033' -> out.append('"');
                case '\u2013', '\u2014', '\u2022', '\u00b7' -> out.append('-');
                case '\u2026' -> out.append("...");
                case '\u00A0', '\u202F', '\u2007' -> out.append(' ');
                default -> {
                    if (c == '\n' || (c >= 32 && c <= 126)) {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private ExportJobResponse toResponse(ExportJob job) {
        String url = null;
        if (STATUS_COMPLETED.equals(job.getStatus()) && job.getDownloadToken() != null) {
            url = "/api/v1/reports/exports/" + job.getId() + "/download?token=" + job.getDownloadToken();
        }
        return new ExportJobResponse(
                job.getId(),
                job.getStatus(),
                job.getReportKey(),
                job.getFormat(),
                url,
                job.getExpiresAt(),
                job.getErrorMessage());
    }

    private String downloadFilename(ExportJob job) {
        String base = job.getReportKey().replace('_', '-');
        String period = "";
        try {
            ExportJobParams p = objectMapper.readValue(job.getParamsJson(), ExportJobParams.class);
            if (p.from() != null && p.to() != null) {
                period = "-" + p.from() + "_" + p.to();
            }
        } catch (Exception ignored) {
            // fall back to the bare report name
        }
        return base + period + "." + job.getFormat();
    }

    private static void validateWindow(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "to must be on or after from");
        }
    }

    private static void validateReportKey(String reportKey) {
        boolean known = REPORT_SALES_REGISTER.equals(reportKey)
                || REPORT_SALES_BY_CATEGORY.equals(reportKey)
                || REPORT_SUPPLIER_SPEND.equals(reportKey)
                || REPORT_INVENTORY_VALUATION.equals(reportKey);
        if (!known) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown reportKey");
        }
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    private static String truncateMessage(String raw) {
        if (raw.length() <= 2000) {
            return raw;
        }
        return raw.substring(0, 1997) + "...";
    }
}
