package zelisline.ub.marketplace.application;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.suppliers.SupplierCodes;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.repository.SupplierRepository;

/**
 * Deterministic public marketplace slugs: {@code product-name--idprefix}.
 * No DB column required — keeps production bootable without a schema migration.
 */
@Service
@RequiredArgsConstructor
public class MarketplaceSlugService {

    static final String ID_MARKER = "--";

    private final SupplierRepository supplierRepository;

    public String supplierSlug(Supplier supplier) {
        if (supplier == null || SupplierCodes.SYSTEM_UNASSIGNED.equals(supplier.getCode())) {
            return null;
        }
        return buildSlug(supplier.getName(), supplier.getId(), "supplier");
    }

    public static String productSlug(String name, String productId) {
        return buildSlug(name, productId, "product");
    }

    @Transactional(readOnly = true)
    public Supplier resolveSupplierBySlug(String slug) {
        String needle = blankToNull(slug);
        if (needle == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found");
        }
        String idPrefix = extractIdPrefix(needle);
        if (idPrefix == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found");
        }
        List<Supplier> candidates = supplierRepository.findPublicActiveByIdPrefix(idPrefix);
        Optional<Supplier> exact = candidates.stream()
                .filter(s -> needle.equalsIgnoreCase(supplierSlug(s)))
                .findFirst();
        if (exact.isPresent()) {
            return exact.get();
        }
        // Fallback: unique id-prefix match (name may have changed after URL was shared).
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found");
    }

    static String buildSlug(String name, String id, String fallbackBase) {
        String base = slugify(name);
        if (base.isBlank()) {
            base = fallbackBase;
        }
        String suffix = idPrefix(id);
        String out = (base + ID_MARKER + suffix).toLowerCase(Locale.ROOT);
        return out.length() > 96 ? out.substring(0, 96) : out;
    }

    static String idPrefix(String id) {
        if (id == null || id.isBlank()) {
            return "x";
        }
        String suffix = id.replace("-", "");
        if (suffix.length() > 8) {
            suffix = suffix.substring(0, 8);
        }
        return suffix.toLowerCase(Locale.ROOT);
    }

    static String extractIdPrefix(String slug) {
        if (slug == null || slug.isBlank()) {
            return null;
        }
        String trimmed = slug.trim();
        int marker = trimmed.lastIndexOf(ID_MARKER);
        if (marker >= 0 && marker < trimmed.length() - ID_MARKER.length()) {
            return normalizeHexSuffix(trimmed.substring(marker + ID_MARKER.length()));
        }
        // Legacy single-dash suffix (pre -- separator).
        int dash = trimmed.lastIndexOf('-');
        if (dash < 0 || dash >= trimmed.length() - 1) {
            return null;
        }
        return normalizeHexSuffix(trimmed.substring(dash + 1));
    }

    private static String normalizeHexSuffix(String suffix) {
        if (suffix == null || suffix.isBlank()) {
            return null;
        }
        String hex = suffix.toLowerCase(Locale.ROOT);
        if (!hex.matches("[0-9a-f]{6,12}")) {
            return null;
        }
        return hex.length() > 8 ? hex.substring(0, 8) : hex;
    }

    public static String slugify(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "");
        String cleaned = normalized.toLowerCase(Locale.ROOT)
                .replace("&", " and ")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-+|-+$", "");
        if (cleaned.length() > 64) {
            cleaned = cleaned.substring(0, 64).replaceAll("-+$", "");
        }
        return cleaned;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
