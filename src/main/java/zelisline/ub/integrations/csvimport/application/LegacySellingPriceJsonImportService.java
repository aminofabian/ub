package zelisline.ub.integrations.csvimport.application;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.integrations.csvimport.api.dto.CsvImportLineError;
import zelisline.ub.integrations.csvimport.api.dto.CsvImportResponse;
import zelisline.ub.pricing.api.dto.PostSellingPriceRequest;
import zelisline.ub.pricing.application.PricingService;
import zelisline.ub.tenancy.repository.BranchRepository;

/**
 * Legacy selling-price JSON: {@code price} per item, optional {@code branch_id}, {@code effective_from} unix time.
 * {@code id}, {@code supplier_id}, {@code set_by}, {@code created_at} in export are ignored (branch only when
 * {@code branch_id} is set).
 */
@Service
@RequiredArgsConstructor
public class LegacySellingPriceJsonImportService {

    private static final Pattern UUID_REGEX = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final BigDecimal MIN_PRICE = new BigDecimal("0.01");
    private static final BigDecimal MAX_PRICE_14_2 = new BigDecimal("999999999999.99");

    private final ObjectMapper objectMapper;
    private final ItemRepository itemRepository;
    private final BranchRepository branchRepository;
    private final PricingService pricingService;

    public CsvImportResponse dryRun(String businessId, byte[] jsonBytes) {
        ParseResult parsed = parseJson(jsonBytes);
        if (!parsed.globalErrors().isEmpty()) {
            return new CsvImportResponse(true, 0, parsed.globalErrors(), null);
        }
        List<CsvImportLineError> errors = validateRows(businessId, parsed.rows());
        return new CsvImportResponse(true, parsed.rows().size(), errors, null);
    }

    @Transactional
    public CsvImportResponse commit(String businessId, byte[] jsonBytes, String actorUserId) {
        ParseResult parsed = parseJson(jsonBytes);
        if (!parsed.globalErrors().isEmpty()) {
            return new CsvImportResponse(false, 0, parsed.globalErrors(), null);
        }
        List<CsvImportLineError> errors = validateRows(businessId, parsed.rows());
        if (!errors.isEmpty()) {
            return new CsvImportResponse(false, parsed.rows().size(), errors, null);
        }
        int n = 0;
        for (SellingRow r : parsed.rows()) {
            pricingService.setSellingPrice(
                    businessId,
                    new PostSellingPriceRequest(
                            r.itemId().trim(),
                            r.branchId(),
                            r.price(),
                            r.effectiveFrom(),
                            r.notes()),
                    actorUserId);
            n++;
        }
        return new CsvImportResponse(false, parsed.rows().size(), List.of(), n);
    }

    private List<CsvImportLineError> validateRows(String businessId, List<SellingRow> rows) {
        List<CsvImportLineError> errors = new ArrayList<>();
        for (SellingRow r : rows) {
            int line = r.line();
            String itemId = r.itemId() == null ? "" : r.itemId().trim();
            if (!isUuid(itemId)) {
                errors.add(new CsvImportLineError(line, "item_id must be a UUID"));
                continue;
            }
            if (itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId).isEmpty()) {
                errors.add(new CsvImportLineError(line, "item not found: " + itemId));
            }
            String bid = r.branchId();
            if (bid != null && !bid.isBlank()) {
                if (!isUuid(bid.trim())) {
                    errors.add(new CsvImportLineError(line, "branch_id must be a UUID when set"));
                } else if (branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(bid.trim(), businessId).isEmpty()) {
                    errors.add(new CsvImportLineError(line, "branch not found: " + bid.trim()));
                }
            }
            if (r.effectiveFrom() == null) {
                errors.add(new CsvImportLineError(line, "effective_from is required (unix time)"));
            }
            if (r.price() == null || r.price().compareTo(MIN_PRICE) < 0) {
                errors.add(new CsvImportLineError(line, "price must be >= 0.01 when set"));
            }
        }
        return errors;
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
            if (root.has("selling_prices") && root.get("selling_prices").isArray()) {
                array = root.get("selling_prices");
            } else if (root.has("sellingPrices") && root.get("sellingPrices").isArray()) {
                array = root.get("sellingPrices");
            } else if (root.has("sell_prices") && root.get("sell_prices").isArray()) {
                array = root.get("sell_prices");
            }
        }
        if (array == null || !array.isArray()) {
            globalErrors.add(new CsvImportLineError(
                    0,
                    "Expected a JSON array or an object with \"selling_prices\", \"sellingPrices\", or \"sell_prices\""));
            return new ParseResult(List.of(), globalErrors);
        }
        List<SellingRow> rows = new ArrayList<>();
        int i = 0;
        for (JsonNode n : array) {
            i++;
            if (n == null || !n.isObject()) {
                globalErrors.add(new CsvImportLineError(i, "Each entry must be a JSON object"));
                continue;
            }
            rows.add(SellingRow.fromJson(i, n));
        }
        if (!globalErrors.isEmpty()) {
            return new ParseResult(List.of(), globalErrors);
        }
        return new ParseResult(rows, List.of());
    }

    private record ParseResult(List<SellingRow> rows, List<CsvImportLineError> globalErrors) {}

    private record SellingRow(
            int line,
            String itemId,
            String branchId,
            BigDecimal price,
            LocalDate effectiveFrom,
            String notes
    ) {
        static SellingRow fromJson(int line, JsonNode n) {
            String bid = textAny(n, "branch_id", "branchId");
            return new SellingRow(
                    line,
                    textAny(n, "item_id", "itemId"),
                    nullIfBlank(bid),
                    sanitizePrice(decimalField(n, "price")),
                    effectiveFromUnix(n),
                    nullIfBlank(clip(text(n, "notes"), 2000)));
        }
    }

    private static BigDecimal decimalField(JsonNode n, String field) {
        if (n == null || !n.has(field) || n.get(field).isNull()) {
            return null;
        }
        JsonNode v = n.get(field);
        try {
            if (v.isNumber()) {
                return BigDecimal.valueOf(v.doubleValue());
            }
            if (v.isTextual()) {
                return new BigDecimal(v.asText().trim());
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
        return null;
    }

    private static BigDecimal sanitizePrice(BigDecimal v) {
        if (v == null) {
            return null;
        }
        BigDecimal x = v;
        if (x.signum() < 0) {
            x = BigDecimal.ZERO;
        }
        if (x.compareTo(MAX_PRICE_14_2) > 0) {
            x = MAX_PRICE_14_2;
        }
        return x.setScale(2, RoundingMode.HALF_UP);
    }

    private static LocalDate effectiveFromUnix(JsonNode n) {
        JsonNode v = null;
        if (n != null && n.has("effective_from") && !n.get("effective_from").isNull()) {
            v = n.get("effective_from");
        } else if (n != null && n.has("effectiveFrom") && !n.get("effectiveFrom").isNull()) {
            v = n.get("effectiveFrom");
        }
        if (v == null) {
            return null;
        }
        long epochSec;
        try {
            if (v.isNumber()) {
                double d = v.doubleValue();
                epochSec = d > 1e12 ? (long) (d / 1000.0) : (long) d;
            } else if (v.isTextual()) {
                String s = v.asText().trim();
                if (s.isEmpty()) {
                    return null;
                }
                long raw = Long.parseLong(s);
                epochSec = raw > 1_000_000_000_000L ? raw / 1000 : raw;
            } else {
                return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return LocalDate.ofInstant(Instant.ofEpochSecond(epochSec), ZoneOffset.UTC);
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

    private static String text(JsonNode n, String field) {
        if (n == null || !n.has(field) || n.get(field).isNull()) {
            return null;
        }
        JsonNode v = n.get(field);
        if (v.isTextual()) {
            String s = v.asText();
            return s == null || s.isBlank() ? null : s.trim();
        }
        if (v.isNumber()) {
            return v.asText();
        }
        return null;
    }

    private static String nullIfBlank(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
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

    private static boolean isUuid(String s) {
        return s != null && UUID_REGEX.matcher(s.trim()).matches();
    }
}
