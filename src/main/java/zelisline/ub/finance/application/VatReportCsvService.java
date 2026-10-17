package zelisline.ub.finance.application;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.finance.VatBucket;
import zelisline.ub.finance.api.dto.VatBucketResponse;
import zelisline.ub.finance.api.dto.VatReportResponse;

/**
 * Renders the VAT report as CSV for the shop's KRA return. Reuses
 * {@link FinanceReportsService} so the download and the on-screen report can never
 * disagree — same ledger source, same period, same bucket granularity.
 */
@Service
@RequiredArgsConstructor
public class VatReportCsvService {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final FinanceReportsService financeReportsService;

    @Transactional(readOnly = true)
    public byte[] vatReportCsv(String businessId, LocalDate from, LocalDate to, VatBucket bucket, String branchId) {
        VatReportResponse summary = financeReportsService.vatReport(businessId, from, to, branchId);
        // Reuse the resolved period so an omitted `to` means the same thing in both blocks.
        List<VatBucketResponse> buckets = financeReportsService.vatReportBuckets(
                businessId, summary.from(), summary.to(), bucket, branchId);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            out.write(UTF8_BOM);
            try (Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
                    CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder().build())) {
                printer.printRecord("VAT report");
                printer.printRecord("From", summary.from());
                printer.printRecord("To", summary.to());
                printer.printRecord(
                        "Branch", summary.branchId() == null ? "All branches" : summary.branchId());
                printer.printRecord();

                printer.printRecord("Summary");
                printer.printRecord("VAT collected", money(summary.vatCollected()));
                printer.printRecord("VAT reversed", money(summary.vatReversed()));
                printer.printRecord("VAT payable", money(summary.vatPayable()));
                printer.printRecord("Net revenue", money(summary.netRevenue()));
                printer.printRecord();

                printer.printRecord("By tax code");
                printer.printRecord("Tax code", "Taxable amount", "VAT", "Lines");
                for (VatReportResponse.TaxCodeRow row : summary.byTaxCode()) {
                    printer.printRecord(
                            row.taxCode() == null || row.taxCode().isBlank() ? "not taxed" : row.taxCode(),
                            money(row.taxableAmount()),
                            money(row.taxAmount()),
                            row.lineCount());
                }
                printer.printRecord();

                printer.printRecord(bucket == VatBucket.MONTH ? "Breakdown by month" : "Breakdown by day");
                printer.printRecord(
                        "Period start", "Period end", "VAT collected", "VAT reversed", "VAT payable", "Net revenue");
                for (VatBucketResponse b : buckets) {
                    printer.printRecord(
                            b.periodStart(),
                            b.periodEnd(),
                            money(b.vatCollected()),
                            money(b.vatReversed()),
                            money(b.vatPayable()),
                            money(b.netRevenue()));
                }
                printer.flush();
            }
            return out.toByteArray();
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not build VAT report CSV");
        }
    }

    private static String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value)
                .setScale(2, RoundingMode.HALF_UP)
                .toPlainString();
    }
}
