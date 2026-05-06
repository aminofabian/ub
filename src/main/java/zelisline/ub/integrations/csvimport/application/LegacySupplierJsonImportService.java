package zelisline.ub.integrations.csvimport.application;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.integrations.csvimport.api.dto.CsvImportLineError;
import zelisline.ub.integrations.csvimport.api.dto.CsvImportResponse;
import zelisline.ub.suppliers.api.dto.CreateSupplierRequest;
import zelisline.ub.suppliers.application.SupplierService;
import zelisline.ub.suppliers.repository.SupplierRepository;

/**
 * Imports a legacy JSON export of suppliers: a top-level array, or an object with a
 * {@code suppliers} / {@code vendors} array.
 */
@Service
@RequiredArgsConstructor
public class LegacySupplierJsonImportService {

    private static final Pattern UUID_REGEX = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final BigDecimal MAX_MONEY_14_2 = new BigDecimal("999999999999.99");

    private final ObjectMapper objectMapper;
    private final SupplierRepository supplierRepository;
    private final SupplierService supplierService;

    public CsvImportResponse dryRun(String businessId, byte[] jsonBytes) {
        ParseResult parsed = parseJson(jsonBytes);
        if (!parsed.globalErrors().isEmpty()) {
            return new CsvImportResponse(true, 0, parsed.globalErrors(), null);
        }
        List<SupplierRow> rows = prepareSupplierRowsForImport(parsed.rows());
        List<CsvImportLineError> errors = validateRows(businessId, rows);
        return new CsvImportResponse(true, rows.size(), errors, null);
    }

    @Transactional
    public CsvImportResponse commit(String businessId, byte[] jsonBytes) {
        ParseResult parsed = parseJson(jsonBytes);
        if (!parsed.globalErrors().isEmpty()) {
            return new CsvImportResponse(false, 0, parsed.globalErrors(), null);
        }
        List<SupplierRow> rows = prepareSupplierRowsForImport(parsed.rows());
        List<CsvImportLineError> errors = validateRows(businessId, rows);
        if (!errors.isEmpty()) {
            return new CsvImportResponse(false, rows.size(), errors, null);
        }
        int n = 0;
        for (SupplierRow r : rows) {
            var created = supplierService.createSupplier(businessId, toCreateRequest(r));
            if (r.legacyImportSourceId() != null) {
                supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull(created.id(), businessId).ifPresent(s -> {
                    s.setLegacyImportSourceId(r.legacyImportSourceId());
                    supplierRepository.save(s);
                });
            }
            n++;
        }
        return new CsvImportResponse(false, rows.size(), List.of(), n);
    }

    private CreateSupplierRequest toCreateRequest(SupplierRow r) {
        return new CreateSupplierRequest(
                r.name().trim(),
                r.code(),
                r.supplierType(),
                r.vatPin(),
                r.taxExempt(),
                r.creditTermsDays(),
                r.creditLimit(),
                r.status(),
                r.notes(),
                r.paymentMethodPreferred(),
                r.paymentDetails());
    }

    private List<CsvImportLineError> validateRows(String businessId, List<SupplierRow> rows) {
        List<CsvImportLineError> errors = new ArrayList<>();
        Set<String> seenCode = new HashSet<>();
        for (SupplierRow r : rows) {
            int line = r.line();
            String name = r.name() == null ? "" : r.name().trim();
            if (name.isEmpty()) {
                errors.add(new CsvImportLineError(line, "name is required"));
                continue;
            }
            if (supplierRepository.existsDuplicateName(businessId, name, null)) {
                errors.add(new CsvImportLineError(line, "supplier name already exists: " + name));
            }
            String code = r.code();
            if (code != null && !code.isEmpty()) {
                String ck = code.toLowerCase(Locale.ROOT);
                if (!seenCode.add(ck)) {
                    errors.add(new CsvImportLineError(line, "duplicate supplier code in file: " + code));
                }
                supplierRepository.findByBusinessIdAndCodeAndDeletedAtIsNull(businessId, code).ifPresent(s ->
                        errors.add(new CsvImportLineError(line, "supplier code already exists: " + code)));
            }
        }
        return errors;
    }

    /**
     * Drops duplicate legacy ids (keeps first). Then makes duplicate display names unique within the file so each
     * legacy supplier can be created and mapped from buying-price JSON (name-only dedupe would drop alternate ids).
     */
    private static List<SupplierRow> prepareSupplierRowsForImport(List<SupplierRow> rows) {
        return disambiguateSupplierNamesWhenDuplicateInFile(dedupeSuppliersByLegacyIdKeepFirst(rows));
    }

    private static List<SupplierRow> dedupeSuppliersByLegacyIdKeepFirst(List<SupplierRow> rows) {
        Set<String> seenLegacy = new HashSet<>();
        List<SupplierRow> out = new ArrayList<>(rows.size());
        for (SupplierRow r : rows) {
            String lid = r.legacyImportSourceId();
            if (lid != null) {
                if (!seenLegacy.add(lid)) {
                    continue;
                }
            }
            out.add(r);
        }
        return out;
    }

    private static List<SupplierRow> disambiguateSupplierNamesWhenDuplicateInFile(List<SupplierRow> rows) {
        Map<String, Integer> countByNorm = new HashMap<>();
        List<SupplierRow> out = new ArrayList<>(rows.size());
        for (SupplierRow r : rows) {
            String name = r.name() == null ? "" : r.name().trim();
            if (name.isEmpty()) {
                out.add(r);
                continue;
            }
            String norm = name.toLowerCase(Locale.ROOT);
            int n = countByNorm.merge(norm, 1, Integer::sum);
            if (n == 1) {
                out.add(r);
                continue;
            }
            String suffix;
            if (r.legacyImportSourceId() != null && r.legacyImportSourceId().length() >= 8) {
                suffix = r.legacyImportSourceId().substring(0, 8);
            } else {
                suffix = "L" + r.line();
            }
            out.add(withSupplierName(r, clip(name + " ·" + suffix, 255)));
        }
        return out;
    }

    private static SupplierRow withSupplierName(SupplierRow r, String newName) {
        return new SupplierRow(
                r.line(),
                r.legacyImportSourceId(),
                newName,
                r.code(),
                r.supplierType(),
                r.vatPin(),
                r.taxExempt(),
                r.creditTermsDays(),
                r.creditLimit(),
                r.status(),
                r.notes(),
                r.paymentMethodPreferred(),
                r.paymentDetails());
    }

    private ParseResult parseJson(byte[] jsonBytes) {
        List<CsvImportLineError> globalErrors = new ArrayList<>();
        JsonNode root;
        try {
            root = objectMapper.readTree(jsonBytes);
        } catch (IOException e) {
            globalErrors.add(new CsvImportLineError(0, "Invalid JSON: " + e.getMessage()));
            return new ParseResult(List.of(), globalErrors);
        }
        JsonNode array = null;
        if (root != null && root.isArray()) {
            array = root;
        } else if (root != null && root.isObject()) {
            if (root.has("suppliers") && root.get("suppliers").isArray()) {
                array = root.get("suppliers");
            } else if (root.has("vendors") && root.get("vendors").isArray()) {
                array = root.get("vendors");
            } else if (root.has("vendor") && root.get("vendor").isArray()) {
                array = root.get("vendor");
            } else if (root.has("data") && root.get("data").isArray()) {
                array = root.get("data");
            } else if (root.has("results") && root.get("results").isArray()) {
                array = root.get("results");
            } else if (root.has("records") && root.get("records").isArray()) {
                array = root.get("records");
            }
        }
        if (array == null || !array.isArray()) {
            globalErrors.add(new CsvImportLineError(
                    0,
                    "Expected a JSON array or an object with a \"suppliers\", \"vendors\", \"data\", \"results\", or \"records\" array"));
            return new ParseResult(List.of(), globalErrors);
        }
        List<SupplierRow> rows = new ArrayList<>();
        int i = 0;
        for (JsonNode n : array) {
            i++;
            if (n == null || !n.isObject()) {
                globalErrors.add(new CsvImportLineError(i, "Each entry must be a JSON object"));
                continue;
            }
            rows.add(SupplierRow.fromJson(i, n));
        }
        if (!globalErrors.isEmpty()) {
            return new ParseResult(List.of(), globalErrors);
        }
        return new ParseResult(rows, List.of());
    }

    private record ParseResult(List<SupplierRow> rows, List<CsvImportLineError> globalErrors) {}

    private record SupplierRow(
            int line,
            String legacyImportSourceId,
            String name,
            String code,
            String supplierType,
            String vatPin,
            Boolean taxExempt,
            Integer creditTermsDays,
            BigDecimal creditLimit,
            String status,
            String notes,
            String paymentMethodPreferred,
            String paymentDetails
    ) {
        static SupplierRow fromJson(int line, JsonNode n) {
            String exportId = legacySupplierIdFromExport(n, 0);
            String displayName = clip(supplierNameFromExport(n, 0), 255);
            if (displayName == null || displayName.isBlank()) {
                displayName = nullIfBlank(clip(textAny(n, "code", "supplier_code", "supplierCode"), 255));
            }
            return new SupplierRow(
                    line,
                    exportId,
                    displayName,
                    nullIfBlank(clip(textAny(n, "code"), 64)),
                    nullIfBlank(clip(textAny(n, "supplier_type", "supplierType"), 32)),
                    nullIfBlank(clip(textAny(n, "vat_pin", "vatPin"), 64)),
                    triBoolAny(n, "tax_exempt", "is_tax_exempt"),
                    intOrNull(n, "credit_terms_days", "creditTermsDays"),
                    money14_2(n, "credit_limit", "creditLimit"),
                    nullIfBlank(clip(textAny(n, "status"), 16)),
                    nullIfBlank(clip(text(n, "notes"), 5000)),
                    nullIfBlank(clip(textAny(n, "payment_method_preferred", "paymentMethodPreferred"), 32)),
                    nullIfBlank(clip(textAny(n, "payment_details", "paymentDetails"), 2000)));
        }
    }

    private static String legacySupplierIdFromExport(JsonNode n, int depth) {
        if (n == null || depth > 4) {
            return null;
        }
        String flat = textAny(n, "id", "supplier_id", "supplierId", "uuid", "external_id", "externalId");
        if (flat != null) {
            String t = flat.trim();
            if (UUID_REGEX.matcher(t).matches()) {
                return t;
            }
        }
        for (String nest : new String[] {"supplier", "vendor", "data", "record", "attributes", "fields"}) {
            if (n.has(nest) && n.get(nest).isObject()) {
                String inner = legacySupplierIdFromExport(n.get(nest), depth + 1);
                if (inner != null) {
                    return inner;
                }
            }
        }
        return null;
    }

    private static String supplierNameFromExport(JsonNode n, int depth) {
        if (n == null || depth > 4) {
            return null;
        }
        String v = textAny(
                n,
                "name",
                "supplier_name",
                "supplierName",
                "company_name",
                "companyName",
                "vendor_name",
                "vendorName",
                "business_name",
                "businessName",
                "legal_name",
                "legalName",
                "display_name",
                "displayName",
                "organization_name",
                "organizationName",
                "trading_name",
                "tradingName",
                "title");
        if (v != null) {
            return v;
        }
        for (String nest : new String[] {"supplier", "vendor", "data", "record", "attributes", "fields", "contact"}) {
            if (n.has(nest) && n.get(nest).isObject()) {
                String inner = supplierNameFromExport(n.get(nest), depth + 1);
                if (inner != null) {
                    return inner;
                }
            }
        }
        return null;
    }

    private static String textAny(JsonNode n, String... fields) {
        for (String f : fields) {
            String v = text(n, f);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static Boolean triBoolAny(JsonNode n, String... fields) {
        for (String f : fields) {
            if (f == null) {
                continue;
            }
            Boolean b = triBool(n, f);
            if (b != null) {
                return b;
            }
        }
        return null;
    }

    private static Integer intOrNull(JsonNode n, String... fields) {
        for (String f : fields) {
            if (n == null || !n.has(f) || n.get(f).isNull()) {
                continue;
            }
            JsonNode v = n.get(f);
            try {
                if (v.isInt() || v.isLong()) {
                    return sanitizeCreditTerms(v.intValue());
                }
                if (v.isNumber()) {
                    return sanitizeCreditTerms((int) v.doubleValue());
                }
                if (v.isTextual()) {
                    String s = v.asText().trim();
                    if (s.isEmpty()) {
                        return null;
                    }
                    return sanitizeCreditTerms(Integer.parseInt(s));
                }
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Integer sanitizeCreditTerms(int v) {
        if (v < 0) {
            return 0;
        }
        if (v > 36500) {
            return 36500;
        }
        return v;
    }

    private static BigDecimal money14_2(JsonNode n, String... fields) {
        for (String f : fields) {
            if (n == null || !n.has(f) || n.get(f).isNull()) {
                continue;
            }
            JsonNode v = n.get(f);
            try {
                BigDecimal d;
                if (v.isNumber()) {
                    d = BigDecimal.valueOf(v.doubleValue());
                } else if (v.isTextual()) {
                    d = new BigDecimal(v.asText().trim());
                } else {
                    continue;
                }
                if (d.signum() < 0) {
                    d = BigDecimal.ZERO;
                }
                if (d.compareTo(MAX_MONEY_14_2) > 0) {
                    d = MAX_MONEY_14_2;
                }
                return d.setScale(2, RoundingMode.HALF_UP);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }

    private static String text(JsonNode n, String field) {
        if (n == null || !n.has(field) || n.get(field).isNull()) {
            return null;
        }
        JsonNode v = n.get(field);
        if (v.isTextual()) {
            String s = v.asText();
            return s == null || s.isBlank() ? null : s;
        }
        if (v.isNumber()) {
            return v.asText();
        }
        return null;
    }

    private static Boolean triBool(JsonNode n, String field) {
        if (n == null || !n.has(field) || n.get(field).isNull()) {
            return null;
        }
        JsonNode v = n.get(field);
        if (v.isBoolean()) {
            return v.booleanValue();
        }
        if (v.isInt() || v.isLong()) {
            return v.intValue() != 0;
        }
        if (v.isTextual()) {
            String s = v.asText().trim().toLowerCase(Locale.ROOT);
            return switch (s) {
                case "true", "1", "yes", "y" -> Boolean.TRUE;
                case "false", "0", "no", "n" -> Boolean.FALSE;
                default -> null;
            };
        }
        return null;
    }

    private static String nullIfBlank(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }
}
