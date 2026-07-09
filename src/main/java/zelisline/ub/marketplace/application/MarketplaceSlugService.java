package zelisline.ub.marketplace.application;

import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.suppliers.SupplierCodes;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.repository.SupplierRepository;

@Service
@RequiredArgsConstructor
public class MarketplaceSlugService {

    private final SupplierRepository supplierRepository;

    /**
     * Ensures the supplier has a stable public slug; persists when newly generated.
     * Synthetic unassigned suppliers never get a public slug.
     */
    @Transactional
    public String ensureSupplierSlug(Supplier supplier) {
        if (supplier == null) {
            return null;
        }
        if (SupplierCodes.SYSTEM_UNASSIGNED.equals(supplier.getCode())) {
            return null;
        }
        if (supplier.getPublicSlug() != null && !supplier.getPublicSlug().isBlank()) {
            return supplier.getPublicSlug();
        }
        String base = slugify(supplier.getName());
        if (base.isBlank()) {
            base = "supplier";
        }
        String suffix = supplier.getId() == null
                ? Integer.toHexString(Math.abs(supplier.getName().hashCode()))
                : supplier.getId().replace("-", "");
        if (suffix.length() > 8) {
            suffix = suffix.substring(0, 8);
        }
        String candidate = (base + "-" + suffix).toLowerCase(Locale.ROOT);
        if (candidate.length() > 96) {
            candidate = candidate.substring(0, 96);
        }
        int n = 0;
        String trySlug = candidate;
        while (supplierRepository.existsByPublicSlugAndDeletedAtIsNull(trySlug)) {
            n += 1;
            String extra = "-" + n;
            trySlug = candidate;
            if (trySlug.length() + extra.length() > 96) {
                trySlug = trySlug.substring(0, 96 - extra.length());
            }
            trySlug = trySlug + extra;
            if (n > 50) {
                trySlug = "s-" + (supplier.getId() == null
                        ? Long.toHexString(System.nanoTime())
                        : supplier.getId().replace("-", ""));
                break;
            }
        }
        supplier.setPublicSlug(trySlug);
        supplierRepository.save(supplier);
        return trySlug;
    }

    public static String slugify(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String cleaned = raw.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-+|-+$", "");
        if (cleaned.length() > 48) {
            cleaned = cleaned.substring(0, 48).replaceAll("-+$", "");
        }
        return cleaned;
    }

    public static String productSlug(String name, String productId) {
        String base = slugify(name);
        if (base.isBlank()) {
            base = "product";
        }
        String suffix = productId == null ? "x" : productId.replace("-", "");
        if (suffix.length() > 8) {
            suffix = suffix.substring(0, 8);
        }
        String out = base + "-" + suffix;
        return out.length() > 96 ? out.substring(0, 96) : out;
    }
}
