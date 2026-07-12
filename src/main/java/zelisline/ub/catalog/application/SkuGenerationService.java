package zelisline.ub.catalog.application;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Category;
import zelisline.ub.catalog.repository.CategoryRepository;
import zelisline.ub.catalog.repository.ItemRepository;

@Service
@RequiredArgsConstructor
public class SkuGenerationService {

    private final CategoryRepository categoryRepository;
    private final ItemRepository itemRepository;

    // Known category abbreviations
    private static final Map<String, String> CATEGORY_ABBREVIATIONS = new ConcurrentHashMap<>(Map.of(
            "fresh milk", "FML",
            "long life milk", "UHT",
            "body lotion", "LOT",
            "bar soap", "BSO",
            "washing powder", "WPO",
            "margarine", "MAR",
            "tomato sauce", "TSA",
            "biscuits", "BIS"
    ));

    // Known brand abbreviations
    private static final Map<String, String> BRAND_ABBREVIATIONS = new ConcurrentHashMap<>(Map.of(
            "brookside", "BRK",
            "kcc", "KCC",
            "fresha", "FSH",
            "amara", "AMA",
            "omo", "OMO",
            "blue band", "BLB",
            "dairyland", "DLL"
    ));

    // Known variant abbreviations
    private static final Map<String, String> VARIANT_ABBREVIATIONS = new ConcurrentHashMap<>(Map.of(
            "full cream", "FC",
            "low fat", "LF",
            "long life", "UHT",
            "chocolate", "CHO",
            "vanilla", "VAN"
    ));

    private static final Pattern SIZE_PATTERN = Pattern.compile(
            "^(\\d+(?:\\.\\d+)?)\\s*(ML|L|G|KG|OZ|LB)$",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Generates a structured SKU in the format CATEGORY-BRAND-SIZE[-VARIANT].
     * If brand or size are missing/blank, falls back to the legacy category-prefix sequence.
     */
    public String generateSku(String businessId, String categoryId, String brand, String size, String variantName) {
        String catAbbr = resolveCategoryAbbreviation(businessId, categoryId);
        String brandAbbr = abbreviateBrand(brand);
        String sizeNorm = normalizeSize(size);

        if (catAbbr == null || brandAbbr == null || sizeNorm == null) {
            return null; // signal to fall back to legacy allocation
        }

        StringBuilder sb = new StringBuilder();
        sb.append(catAbbr).append('-').append(brandAbbr).append('-').append(sizeNorm);

        String varAbbr = abbreviateVariant(variantName);
        if (varAbbr != null && !varAbbr.isBlank()) {
            sb.append('-').append(varAbbr);
        }

        String base = sb.toString();
        return resolveUniqueSku(businessId, base);
    }

    /**
     * Generates a variant SKU based on the parent SKU and variant name.
     * Only returns a value when a known abbreviation exists; otherwise returns null
     * so callers can fall back to the legacy full-name segment format.
     */
    public String generateVariantSku(String businessId, String parentSku, String variantName) {
        if (parentSku == null || parentSku.isBlank()) {
            return null;
        }
        if (variantName == null || variantName.isBlank()) {
            return null;
        }
        String key = variantName.toLowerCase(Locale.ROOT).trim();
        String varAbbr = VARIANT_ABBREVIATIONS.get(key);
        if (varAbbr == null || varAbbr.isBlank()) {
            return null;
        }
        String base = parentSku + "-" + varAbbr;
        return resolveUniqueSku(businessId, base);
    }

    private String resolveCategoryAbbreviation(String businessId, String categoryId) {
        if (categoryId == null || categoryId.isBlank()) {
            return null;
        }
        Optional<Category> catOpt = categoryRepository.findByIdAndBusinessId(categoryId, businessId);
        if (catOpt.isEmpty()) {
            return null;
        }
        String name = catOpt.get().getName();
        return abbreviateCategory(name);
    }

    static String abbreviateCategory(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String key = name.toLowerCase(Locale.ROOT).trim();
        String known = CATEGORY_ABBREVIATIONS.get(key);
        if (known != null) {
            return known;
        }
        return fallbackAbbreviation(name);
    }

    static String abbreviateBrand(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String key = name.toLowerCase(Locale.ROOT).trim();
        String known = BRAND_ABBREVIATIONS.get(key);
        if (known != null) {
            return known;
        }
        return fallbackAbbreviation(name);
    }

    static String abbreviateVariant(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String key = name.toLowerCase(Locale.ROOT).trim();
        String known = VARIANT_ABBREVIATIONS.get(key);
        if (known != null) {
            return known;
        }
        return fallbackAbbreviation(name);
    }

    private static String fallbackAbbreviation(String name) {
        String cleaned = name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9 ]", "").trim();
        if (cleaned.isBlank()) {
            return null;
        }
        String[] words = cleaned.split("\\s+");
        if (words.length == 1) {
            String w = words[0];
            if (w.length() >= 3) {
                return w.substring(0, 3);
            }
            return w.length() == 2 ? w + "X" : w + "XX";
        }
        // Multi-word: take first letter of first word, first letter of last word,
        // then second letter of last word (or first available consonant)
        String first = words[0];
        String last = words[words.length - 1];
        char a = first.charAt(0);
        char b = last.charAt(0);
        char c = last.length() > 1 ? last.charAt(1) : 'X';
        return "" + a + b + c;
    }

    static String normalizeSize(String size) {
        if (size == null || size.isBlank()) {
            return null;
        }
        String s = size.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        Matcher m = SIZE_PATTERN.matcher(s);
        if (!m.matches()) {
            return null;
        }
        String number = m.group(1);
        String unit = m.group(2).toUpperCase(Locale.ROOT);
        return switch (unit) {
            case "ML", "G" -> number;
            case "L", "KG" -> number + unit;
            case "OZ", "LB" -> number + unit;
            default -> number;
        };
    }

    private String resolveUniqueSku(String businessId, String base) {
        if (!itemRepository.existsByBusinessIdAndSku(businessId, base)) {
            return base;
        }
        // Soft-deleted rows still occupy uq_items_business_sku — skip those too.
        for (int i = 1; i <= 99; i++) {
            String suffix = String.format("-%02d", i);
            String candidate = base + suffix;
            if (!itemRepository.existsByBusinessIdAndSku(businessId, candidate)) {
                return candidate;
            }
        }
        // Extreme edge case - append timestamp fragment
        return base + "-" + System.currentTimeMillis();
    }

    public void registerCategoryAbbreviation(String categoryName, String abbreviation) {
        if (categoryName != null && abbreviation != null) {
            CATEGORY_ABBREVIATIONS.put(categoryName.toLowerCase(Locale.ROOT).trim(), abbreviation.toUpperCase(Locale.ROOT));
        }
    }

    public void registerBrandAbbreviation(String brandName, String abbreviation) {
        if (brandName != null && abbreviation != null) {
            BRAND_ABBREVIATIONS.put(brandName.toLowerCase(Locale.ROOT).trim(), abbreviation.toUpperCase(Locale.ROOT));
        }
    }

    public void registerVariantAbbreviation(String variantName, String abbreviation) {
        if (variantName != null && abbreviation != null) {
            VARIANT_ABBREVIATIONS.put(variantName.toLowerCase(Locale.ROOT).trim(), abbreviation.toUpperCase(Locale.ROOT));
        }
    }
}
