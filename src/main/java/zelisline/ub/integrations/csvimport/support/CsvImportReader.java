package zelisline.ub.integrations.csvimport.support;

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

/** Parsed CSV rows with RFC4180-aware quoting ({@code commons-csv}). */
public final class CsvImportReader {

    private CsvImportReader() {
    }

    public record SourceRow(int lineNumber, Map<String, String> columns) {
    }

    /**
     * First row is treated as header; keys are normalized to lowercase snake_case (spaces → underscores).
     */
    public static List<SourceRow> readRows(InputStream in) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .build();
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
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

    private static String normalizeHeader(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private static String blankToEmpty(String v) {
        return v == null ? "" : v.trim();
    }
}
