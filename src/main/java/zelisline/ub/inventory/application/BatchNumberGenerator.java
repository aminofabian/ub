package zelisline.ub.inventory.application;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import zelisline.ub.inventory.repository.SupplyBatchRepository;

/**
 * Generates human-readable batch numbers: PREFIX-DAY-SEQUENCE.
 *
 * <p>Format: SW-TUE-001
 *   - Prefix: abbreviation of supplier name
 *     "Sam West" → SW, "Farm Fresh Eggs Ltd" → FFEL, "Sunny" → SUNN
 *   - Day: three-letter weekday (MON, TUE, WED, ...)
 *   - Sequence: per-supplier counter (how many times you've ordered from them)
 *
 * <p>No supplier → SB-DAY-0042 (per-business fallback).
 */
@Service
@RequiredArgsConstructor
public class BatchNumberGenerator {

    private static final Map<String, String> ABBREV_CACHE = new ConcurrentHashMap<>();

    private final SupplyBatchRepository supplyBatchRepository;

    /**
     * @param supplierId   nullable — UUID of the supplier
     * @param supplierName nullable — display name, used only for abbreviation
     * @param receivedAt   the delivery timestamp
     * @param businessId   tenant ID
     */
    public String next(String supplierId, String supplierName, java.time.Instant receivedAt, String businessId) {
        ZonedDateTime zdt = receivedAt.atZone(ZoneOffset.UTC);
        String day = zdt.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toUpperCase();

        if (supplierId == null) {
            long count = supplyBatchRepository.countByBusinessId(businessId);
            return "SB-" + day + "-" + String.format("%04d", count + 1);
        }

        String abbrev = abbreviate(supplierName);
        long count = supplyBatchRepository.countBySupplierIdAndBusinessId(supplierId, businessId);
        return abbrev + "-" + day + "-" + String.format("%04d", count + 1);
    }

    private String abbreviate(String name) {
        if (name == null || name.isBlank()) return "SUP";
        return ABBREV_CACHE.computeIfAbsent(name, n -> {
            String[] words = n.toUpperCase(Locale.ENGLISH).split("[^A-Z]+");
            StringBuilder sb = new StringBuilder();
            if (words.length >= 2) {
                for (int i = 0; i < Math.min(words.length, 4); i++) {
                    if (!words[i].isEmpty()) sb.append(words[i].charAt(0));
                }
            } else {
                String w = words.length > 0 ? words[0] : n;
                sb.append(w.substring(0, Math.min(4, w.length())));
            }
            return sb.toString();
        });
    }
}
