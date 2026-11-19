package zelisline.ub.integrations.csvimport.support;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * Parses an import upload into rows. Accepts either CSV (RFC4180-aware, {@code commons-csv}) or an
 * {@code .xlsx} workbook (first sheet, via {@code poi-ooxml} — already a project dependency). The
 * format is detected from the leading bytes: a ZIP local-file-header magic ({@code PK}) means xlsx,
 * anything else is treated as CSV. Both paths yield the same {@link SourceRow} shape, so the rest of
 * the import pipeline (validation, commit, grouping) is format-agnostic.
 */
public final class CsvImportReader {

    private CsvImportReader() {
    }

    public record SourceRow(int lineNumber, Map<String, String> columns) {
    }

    /**
     * First row is treated as header; keys are normalized to lowercase snake_case (spaces → underscores).
     */
    public static List<SourceRow> readRows(InputStream in) throws IOException {
        byte[] data = in.readAllBytes();
        if (isXlsx(data)) {
            return readXlsxRows(data);
        }
        return readCsvRows(data);
    }

    /** ZIP magic {@code PK\003\004} — every {@code .xlsx} (an OOXML zip) starts with it; CSV never does. */
    private static boolean isXlsx(byte[] data) {
        return data.length > 3
                && data[0] == 0x50 && data[1] == 0x4B
                && (data[2] == 0x03 || data[2] == 0x05 || data[2] == 0x07);
    }

    private static List<SourceRow> readCsvRows(byte[] data) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .build();
        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8);
                CSVParser parser = format.parse(reader)) {
            List<String> headerNames = parser.getHeaderNames();
            List<SourceRow> out = new ArrayList<>();
            for (CSVRecord rec : parser) {
                Map<String, String> cols = new LinkedHashMap<>();
                for (String h : headerNames) {
                    String key = normalizeHeader(h);
                    if (key.isEmpty()) {
                        continue;
                    }
                    cols.put(key, blankToEmpty(rec.get(h)));
                }
                out.add(new SourceRow((int) rec.getRecordNumber(), cols));
            }
            return out;
        }
    }

    /**
     * Reads the first sheet of an {@code .xlsx}. The header row is the first row that has any
     * non-blank cell; subsequent rows map positionally onto those headers. A {@link DataFormatter}
     * renders every cell as the text the user sees in Excel (so {@code 460} stays {@code "460"} and
     * a barcode's leading zeros survive), keeping cell handling identical to the CSV path.
     */
    private static List<SourceRow> readXlsxRows(byte[] data) throws IOException {
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(data))) {
            if (workbook.getNumberOfSheets() == 0) {
                return List.of();
            }
            Sheet sheet = workbook.getSheetAt(0);
            List<String> headerKeys = null;
            List<SourceRow> out = new ArrayList<>();
            for (Row row : sheet) {
                if (row == null) {
                    continue;
                }
                if (headerKeys == null) {
                    List<String> candidate = headerKeys(row, formatter);
                    if (candidate.stream().anyMatch(h -> !h.isEmpty())) {
                        headerKeys = candidate;
                    }
                    continue; // header row consumed; data starts next
                }
                Map<String, String> cols = new LinkedHashMap<>();
                boolean anyValue = false;
                for (int c = 0; c < headerKeys.size(); c++) {
                    String key = headerKeys.get(c);
                    if (key.isEmpty()) {
                        continue;
                    }
                    Cell cell = row.getCell(c);
                    String value = cell == null ? "" : formatter.formatCellValue(cell).trim();
                    if (!value.isEmpty()) {
                        anyValue = true;
                    }
                    // Mirror CSV: last non-blank wins for a repeated header; keep first-seen blank.
                    if (!value.isEmpty() || !cols.containsKey(key)) {
                        cols.put(key, value);
                    }
                }
                if (anyValue) { // skip fully blank rows, matching CSV ignoreEmptyLines
                    out.add(new SourceRow(row.getRowNum() + 1, cols));
                }
            }
            return out;
        }
    }

    private static List<String> headerKeys(Row row, DataFormatter formatter) {
        List<String> keys = new ArrayList<>();
        int lastCol = row.getLastCellNum();
        for (int c = 0; c < lastCol; c++) {
            Cell cell = row.getCell(c);
            keys.add(normalizeHeader(cell == null ? "" : formatter.formatCellValue(cell)));
        }
        return keys;
    }

    private static String normalizeHeader(String raw) {
        if (raw == null) {
            return "";
        }
        // Strip UTF-8 BOM (U+FEFF) — Excel often prefixes the first header cell.
        String cleaned = raw.replace("﻿", "").trim();
        return cleaned.toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private static String blankToEmpty(String v) {
        return v == null ? "" : v.trim();
    }
}
