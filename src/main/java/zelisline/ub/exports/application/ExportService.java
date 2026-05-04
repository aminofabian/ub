package zelisline.ub.exports.application;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
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
import zelisline.ub.exports.api.dto.ExportCreateRequest;
import zelisline.ub.exports.api.dto.ExportJobResponse;
import zelisline.ub.exports.domain.ExportJob;
import zelisline.ub.exports.repository.ExportJobRepository;
import zelisline.ub.identity.application.TokenHasher;
import zelisline.ub.reporting.api.dto.SalesRegisterResponse;
import zelisline.ub.reporting.application.SalesReportsService;

@Service
@RequiredArgsConstructor
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);

    public static final String REPORT_SALES_REGISTER = "sales_register";

    private static final String STATUS_PROCESSING = "processing";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_FAILED = "failed";

    private static final Duration DOWNLOAD_TTL = Duration.ofHours(1);

    private final ExportJobRepository exportJobRepository;
    private final SalesReportsService salesReportsService;
    private final ObjectMapper objectMapper;

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

        ExportJobParams params = new ExportJobParams(req.from(), req.to(), blankToNull(req.branchId()));
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
        MediaType mt = "xlsx".equals(job.getFormat())
                ? MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                : MediaType.parseMediaType("text/csv");
        String filename = "export-" + jobId + "." + job.getFormat();
        return new ExportDownload(new FileSystemResource(path.toFile()), mt, filename);
    }

    private void run(ExportJob job, ExportJobParams params) throws IOException {
        if (REPORT_SALES_REGISTER.equals(job.getReportKey())) {
            runSalesRegister(job, params);
            return;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported report");
    }

    private void runSalesRegister(ExportJob job, ExportJobParams params) throws IOException {
        SalesRegisterResponse resp = salesReportsService.salesRegister(
                job.getBusinessId(), params.from(), params.to(), params.branchId());

        Path dir = Paths.get(System.getProperty("java.io.tmpdir"), "ub-exports", job.getBusinessId());
        Files.createDirectories(dir);
        Path file = dir.resolve(job.getId() + "." + job.getFormat());

        if ("csv".equals(job.getFormat())) {
            writeCsv(file, resp);
        } else if ("xlsx".equals(job.getFormat())) {
            writeXlsx(file, resp);
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported format");
        }

        job.setStoragePath(file.toAbsolutePath().toString());
        job.setDownloadToken(UUID.randomUUID().toString());
        job.setExpiresAt(Instant.now().plus(DOWNLOAD_TTL));
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

    private static void validateWindow(java.time.LocalDate from, java.time.LocalDate to) {
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "to must be on or after from");
        }
    }

    private static void validateReportKey(String reportKey) {
        if (!REPORT_SALES_REGISTER.equals(reportKey)) {
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
