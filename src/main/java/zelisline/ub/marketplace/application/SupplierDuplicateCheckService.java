package zelisline.ub.marketplace.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.domain.SupplierIdentityIndex;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.marketplace.repository.SupplierIdentityIndexRepository;
import zelisline.ub.suppliers.api.dto.SupplierDuplicateCheckRequest;
import zelisline.ub.suppliers.api.dto.SupplierDuplicateCheckResponse;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.repository.SupplierRepository;

@Service
@RequiredArgsConstructor
public class SupplierDuplicateCheckService {

    private static final double NAME_SIMILARITY_THRESHOLD = 0.85;

    private final SupplierIdentityIndexRepository identityIndexRepository;
    private final SupplierRepository supplierRepository;
    private final MarketplaceSupplierRepository marketplaceSupplierRepository;

    @Transactional(readOnly = true)
    public SupplierDuplicateCheckResponse check(String businessId, SupplierDuplicateCheckRequest request) {
        if (!request.hasAnyKey()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Provide at least one of name, phone, email, or tax ID");
        }

        String taxId = SupplierIdentityNormalizer.normalizeTaxId(request.taxId());
        String phone = SupplierIdentityNormalizer.normalizePhone(request.phone());
        String email = SupplierIdentityNormalizer.normalizeEmail(request.email());
        String nameNorm = SupplierIdentityNormalizer.normalizeName(request.name());

        Map<String, SupplierDuplicateCheckResponse.SupplierDuplicateMatch> matches = new LinkedHashMap<>();

        if (taxId != null) {
            for (SupplierIdentityIndex row : identityIndexRepository.findOwnBusinessByTaxId(businessId, taxId)) {
                addOwnBusinessMatch(matches, row, "strong", revealTaxId(request.taxId(), taxId));
            }
            for (SupplierIdentityIndex row : identityIndexRepository.findMarketplaceByTaxId(taxId)) {
                addMarketplaceMatch(matches, row, "strong", revealTaxId(request.taxId(), taxId), null, null);
            }
        }
        if (phone != null) {
            for (SupplierIdentityIndex row : identityIndexRepository.findOwnBusinessByPhone(businessId, phone)) {
                addOwnBusinessMatch(matches, row, "strong", null, revealPhone(request.phone(), phone), null);
            }
            for (SupplierIdentityIndex row : identityIndexRepository.findMarketplaceByPhone(phone)) {
                addMarketplaceMatch(matches, row, "strong", null, revealPhone(request.phone(), phone), null);
            }
        }
        if (email != null) {
            for (SupplierIdentityIndex row : identityIndexRepository.findOwnBusinessByEmail(businessId, email)) {
                addOwnBusinessMatch(matches, row, "strong", null, null, revealEmail(request.email(), email));
            }
            for (SupplierIdentityIndex row : identityIndexRepository.findMarketplaceByEmail(email)) {
                addMarketplaceMatch(matches, row, "strong", null, null, revealEmail(request.email(), email));
            }
        }
        if (!nameNorm.isBlank()) {
            String prefix = nameNorm.length() >= 4 ? nameNorm.substring(0, 4) : nameNorm;
            for (SupplierIdentityIndex row : identityIndexRepository.findOwnBusinessByNamePrefix(businessId, prefix)) {
                if (nameSimilarity(nameNorm, row.getNameNormalized()) >= NAME_SIMILARITY_THRESHOLD) {
                    addOwnBusinessMatch(matches, row, "possible", null, null, null);
                }
            }
            for (SupplierIdentityIndex row : identityIndexRepository.findMarketplaceByNamePrefix(prefix)) {
                if (nameSimilarity(nameNorm, row.getNameNormalized()) >= NAME_SIMILARITY_THRESHOLD) {
                    addMarketplaceMatch(matches, row, "possible", null, null, null);
                }
            }
        }

        return new SupplierDuplicateCheckResponse(new ArrayList<>(matches.values()));
    }

    private void addOwnBusinessMatch(
            Map<String, SupplierDuplicateCheckResponse.SupplierDuplicateMatch> matches,
            SupplierIdentityIndex row,
            String confidence,
            String taxId
    ) {
        addOwnBusinessMatch(matches, row, confidence, taxId, null, null);
    }

    private void addOwnBusinessMatch(
            Map<String, SupplierDuplicateCheckResponse.SupplierDuplicateMatch> matches,
            SupplierIdentityIndex row,
            String confidence,
            String taxId,
            String phone,
            String email
    ) {
        if (row.getSupplierId() == null) {
            return;
        }
        String key = "own:" + row.getSupplierId();
        Supplier supplier = supplierRepository.findById(row.getSupplierId()).orElse(null);
        if (supplier == null || supplier.getDeletedAt() != null) {
            return;
        }
        matches.putIfAbsent(key, new SupplierDuplicateCheckResponse.SupplierDuplicateMatch(
                confidence,
                "own_business",
                row.getSupplierId(),
                null,
                supplier.getName(),
                phone,
                email,
                taxId,
                row.getRegionHint()));
    }

    private void addMarketplaceMatch(
            Map<String, SupplierDuplicateCheckResponse.SupplierDuplicateMatch> matches,
            SupplierIdentityIndex row,
            String confidence,
            String taxId,
            String phone,
            String email
    ) {
        if (row.getMarketplaceSupplierId() == null) {
            return;
        }
        String key = "marketplace:" + row.getMarketplaceSupplierId();
        MarketplaceSupplier supplier = marketplaceSupplierRepository.findById(row.getMarketplaceSupplierId()).orElse(null);
        if (supplier == null) {
            return;
        }
        matches.putIfAbsent(key, new SupplierDuplicateCheckResponse.SupplierDuplicateMatch(
                confidence,
                "marketplace",
                null,
                row.getMarketplaceSupplierId(),
                supplier.getName(),
                phone != null ? phone : supplier.getContactPhone(),
                email != null ? email : supplier.getContactEmail(),
                taxId,
                row.getRegionHint()));
    }

    private static String revealTaxId(String raw, String normalized) {
        return raw == null || raw.isBlank() ? null : normalized;
    }

    private static String revealPhone(String raw, String normalized) {
        return raw == null || raw.isBlank() ? null : normalized;
    }

    private static String revealEmail(String raw, String normalized) {
        return raw == null || raw.isBlank() ? null : normalized;
    }

    /** Token-set Jaccard similarity on normalized names. */
    static double nameSimilarity(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return 0;
        }
        Set<String> ta = tokens(a);
        Set<String> tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new LinkedHashSet<>(ta);
        intersection.retainAll(tb);
        Set<String> union = new LinkedHashSet<>(ta);
        union.addAll(tb);
        return (double) intersection.size() / union.size();
    }

    private static Set<String> tokens(String value) {
        Set<String> out = new LinkedHashSet<>();
        for (String part : value.toLowerCase(Locale.ROOT).split("\\s+")) {
            String t = part.trim();
            if (!t.isBlank()) {
                out.add(t);
            }
        }
        return out;
    }
}
