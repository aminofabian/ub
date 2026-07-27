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
import zelisline.ub.marketplace.domain.MarketplaceSupplierStatuses;
import zelisline.ub.marketplace.domain.SupplierIdentityIndex;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.marketplace.repository.SupplierIdentityIndexRepository;
import zelisline.ub.platform.application.PlatformSupplierPortalSettingsService;
import zelisline.ub.suppliers.api.dto.SupplierDuplicateCheckRequest;
import zelisline.ub.suppliers.api.dto.SupplierDuplicateCheckResponse;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.repository.SupplierContactRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;

@Service
@RequiredArgsConstructor
public class SupplierDuplicateCheckService {

    private static final double NAME_SIMILARITY_THRESHOLD = 0.5;

    private final SupplierIdentityIndexRepository identityIndexRepository;
    private final SupplierRepository supplierRepository;
    private final SupplierContactRepository supplierContactRepository;
    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final PlatformSupplierPortalSettingsService portalSettingsService;

    @Transactional(readOnly = true)
    public SupplierDuplicateCheckResponse check(String businessId, SupplierDuplicateCheckRequest request) {
        if (!request.hasAnyKey()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Provide a query, or at least one of name, phone, email, tax ID, or supplier number");
        }

        String nameRaw = request.name();
        String phoneRaw = request.phone();
        String supplierNumberRaw = request.supplierNumber();
        if (request.query() != null && !request.query().isBlank()) {
            var classified = SupplierLookupClassifier.classify(request.query());
            if (nameRaw == null || nameRaw.isBlank()) {
                nameRaw = classified.name();
            }
            if (phoneRaw == null || phoneRaw.isBlank()) {
                phoneRaw = classified.phone();
            }
            if (supplierNumberRaw == null || supplierNumberRaw.isBlank()) {
                supplierNumberRaw = classified.supplierNumber();
            }
        }

        String taxId = SupplierIdentityNormalizer.normalizeTaxId(request.taxId());
        String phone = SupplierIdentityNormalizer.normalizePhone(phoneRaw);
        String email = SupplierIdentityNormalizer.normalizeEmail(request.email());
        String nameNorm = SupplierIdentityNormalizer.normalizeName(nameRaw);
        String supplierNumber = SupplierNumberFormat.normalize(supplierNumberRaw);
        boolean allowDrafts = portalSettingsService.loadSingleton().isAllowFindUnclaimedDrafts();
        var phoneForms = phoneRaw != null && !phoneRaw.isBlank()
                ? SupplierIdentityNormalizer.phoneLookupForms(phoneRaw)
                : null;

        Map<String, SupplierDuplicateCheckResponse.SupplierDuplicateMatch> matches = new LinkedHashMap<>();

        if (supplierNumber != null) {
            for (SupplierIdentityIndex row : identityIndexRepository.findMarketplaceBySupplierNumber(supplierNumber)) {
                addMarketplaceMatch(matches, row, "strong", null, null, null, allowDrafts, List.of("supplier_number"));
            }
            marketplaceSupplierRepository.findBySupplierNumber(supplierNumber).ifPresent(ms ->
                    addMarketplaceEntity(matches, ms, "strong", null, null, null, allowDrafts, List.of("supplier_number")));
        }
        if (taxId != null) {
            for (SupplierIdentityIndex row : identityIndexRepository.findOwnBusinessByTaxId(businessId, taxId)) {
                addOwnBusinessMatch(matches, row, "strong", revealTaxId(request.taxId(), taxId), null, null, List.of("tax_id"));
            }
            for (SupplierIdentityIndex row : identityIndexRepository.findMarketplaceByTaxId(taxId)) {
                addMarketplaceMatch(matches, row, "strong", revealTaxId(request.taxId(), taxId), null, null, allowDrafts, List.of("tax_id"));
            }
            for (SupplierIdentityIndex row : identityIndexRepository.findTenantByTaxId(taxId)) {
                addPlatformTenantMatch(matches, businessId, row, "strong", revealTaxId(request.taxId(), taxId), null, null, allowDrafts, List.of("tax_id"));
            }
        }
        if (phoneForms != null) {
            String revealed = revealPhone(phoneRaw, phoneForms.phone());
            List<String> reasons = List.of("phone_last9");
            for (SupplierIdentityIndex row : identityIndexRepository.findOwnBusinessByPhoneVariants(
                    businessId, phoneForms.phone(), phoneForms.altPhone(), phoneForms.phoneTail())) {
                addOwnBusinessMatch(matches, row, "strong", null, revealed, null, reasons);
            }
            // Direct table scan covers contacts/payout not yet indexed.
            for (Supplier payoutHit : supplierRepository.findOwnBusinessByPayoutPhoneVariants(
                    businessId, phoneForms.phone(), phoneForms.altPhone(), phoneForms.phoneTail(), null)) {
                addOwnSupplierEntity(matches, payoutHit, "strong", null, revealed, null, reasons);
            }
            for (var contactHit : supplierContactRepository.findOwnBusinessByPhoneVariants(
                    businessId, phoneForms.phone(), phoneForms.altPhone(), phoneForms.phoneTail(), null)) {
                supplierRepository.findByIdAndDeletedAtIsNull(contactHit.getSupplierId()).ifPresent(owner ->
                        addOwnSupplierEntity(matches, owner, "strong", null, revealed, null, reasons));
            }
            for (SupplierIdentityIndex row : identityIndexRepository.findMarketplaceByPhoneVariants(
                    phoneForms.phone(), phoneForms.altPhone(), phoneForms.phoneTail())) {
                addMarketplaceMatch(matches, row, "strong", null, revealed, null, allowDrafts, reasons);
            }
            for (SupplierIdentityIndex row : identityIndexRepository.findTenantByPhoneVariants(
                    phoneForms.phone(), phoneForms.altPhone(), phoneForms.phoneTail())) {
                addPlatformTenantMatch(matches, businessId, row, "strong", null, revealed, null, allowDrafts, reasons);
            }
        } else if (phone != null) {
            String revealed = revealPhone(phoneRaw, phone);
            for (SupplierIdentityIndex row : identityIndexRepository.findOwnBusinessByPhone(businessId, phone)) {
                addOwnBusinessMatch(matches, row, "strong", null, revealed, null, List.of("phone"));
            }
            for (SupplierIdentityIndex row : identityIndexRepository.findMarketplaceByPhone(phone)) {
                addMarketplaceMatch(matches, row, "strong", null, revealed, null, allowDrafts, List.of("phone"));
            }
            for (SupplierIdentityIndex row : identityIndexRepository.findTenantByPhone(phone)) {
                addPlatformTenantMatch(matches, businessId, row, "strong", null, revealed, null, allowDrafts, List.of("phone"));
            }
        }
        if (email != null) {
            String revealed = revealEmail(request.email(), email);
            List<String> reasons = List.of("email");
            for (SupplierIdentityIndex row : identityIndexRepository.findOwnBusinessByEmail(businessId, email)) {
                addOwnBusinessMatch(matches, row, "strong", null, null, revealed, reasons);
            }
            for (var contactHit : supplierContactRepository.findOwnBusinessByEmail(businessId, email, null)) {
                supplierRepository.findByIdAndDeletedAtIsNull(contactHit.getSupplierId()).ifPresent(owner ->
                        addOwnSupplierEntity(matches, owner, "strong", null, null, revealed, reasons));
            }
            for (SupplierIdentityIndex row : identityIndexRepository.findMarketplaceByEmail(email)) {
                addMarketplaceMatch(matches, row, "strong", null, null, revealed, allowDrafts, reasons);
            }
            for (SupplierIdentityIndex row : identityIndexRepository.findTenantByEmail(email)) {
                addPlatformTenantMatch(matches, businessId, row, "strong", null, null, revealed, allowDrafts, reasons);
            }
        }
        if (!nameNorm.isBlank()) {
            String prefix = nameNorm.length() >= 3 ? nameNorm.substring(0, 3) : nameNorm;
            for (SupplierIdentityIndex row : identityIndexRepository.findOwnBusinessByNamePrefix(businessId, prefix)) {
                if (namesMatch(nameNorm, row.getNameNormalized())) {
                    addOwnBusinessMatch(
                            matches,
                            row,
                            confidenceForName(nameNorm, row.getNameNormalized()),
                            null,
                            null,
                            null,
                            List.of("name"));
                }
            }
            for (SupplierIdentityIndex row : identityIndexRepository.findMarketplaceByNamePrefix(prefix)) {
                if (namesMatch(nameNorm, row.getNameNormalized())) {
                    addMarketplaceMatch(
                            matches,
                            row,
                            confidenceForName(nameNorm, row.getNameNormalized()),
                            null,
                            null,
                            null,
                            allowDrafts,
                            List.of("name"));
                }
            }
            for (SupplierIdentityIndex row : identityIndexRepository.findTenantByNamePrefix(prefix)) {
                if (namesMatch(nameNorm, row.getNameNormalized())) {
                    addPlatformTenantMatch(
                            matches,
                            businessId,
                            row,
                            confidenceForName(nameNorm, row.getNameNormalized()),
                            null,
                            null,
                            null,
                            allowDrafts,
                            List.of("name"));
                }
            }
        }

        return new SupplierDuplicateCheckResponse(collapseOwnAndMarketplaceDuplicates(matches));
    }

    private static List<SupplierDuplicateCheckResponse.SupplierDuplicateMatch> collapseOwnAndMarketplaceDuplicates(
            Map<String, SupplierDuplicateCheckResponse.SupplierDuplicateMatch> matches
    ) {
        Set<String> ownMarketplaceIds = new LinkedHashSet<>();
        Set<String> ownSupplierNumbers = new LinkedHashSet<>();
        for (SupplierDuplicateCheckResponse.SupplierDuplicateMatch match : matches.values()) {
            if (!"own_business".equals(match.source())) {
                continue;
            }
            if (match.marketplaceSupplierId() != null && !match.marketplaceSupplierId().isBlank()) {
                ownMarketplaceIds.add(match.marketplaceSupplierId());
            }
            if (match.supplierNumber() != null && !match.supplierNumber().isBlank()) {
                ownSupplierNumbers.add(match.supplierNumber().trim().toUpperCase(Locale.ROOT));
            }
        }
        List<SupplierDuplicateCheckResponse.SupplierDuplicateMatch> out = new ArrayList<>();
        for (SupplierDuplicateCheckResponse.SupplierDuplicateMatch match : matches.values()) {
            if ("marketplace".equals(match.source())) {
                if (match.marketplaceSupplierId() != null
                        && ownMarketplaceIds.contains(match.marketplaceSupplierId())) {
                    continue;
                }
                if (match.supplierNumber() != null
                        && ownSupplierNumbers.contains(match.supplierNumber().trim().toUpperCase(Locale.ROOT))) {
                    continue;
                }
            }
            out.add(match);
        }
        return out;
    }

    private void addOwnBusinessMatch(
            Map<String, SupplierDuplicateCheckResponse.SupplierDuplicateMatch> matches,
            SupplierIdentityIndex row,
            String confidence,
            String taxId,
            String phone,
            String email,
            List<String> reasons
    ) {
        if (row.getSupplierId() == null) {
            return;
        }
        Supplier supplier = supplierRepository.findById(row.getSupplierId()).orElse(null);
        if (supplier == null || supplier.getDeletedAt() != null) {
            return;
        }
        addOwnSupplierEntity(matches, supplier, confidence, taxId, phone, email, reasons);
    }

    private void addOwnSupplierEntity(
            Map<String, SupplierDuplicateCheckResponse.SupplierDuplicateMatch> matches,
            Supplier supplier,
            String confidence,
            String taxId,
            String phone,
            String email,
            List<String> reasons
    ) {
        String key = "own:" + supplier.getId();
        String supplierNumber = null;
        String marketplaceId = supplier.getMarketplaceSupplierId();
        if (marketplaceId != null) {
            supplierNumber = marketplaceSupplierRepository.findById(marketplaceId)
                    .map(MarketplaceSupplier::getSupplierNumber)
                    .orElse(null);
        }
        putMerged(matches, key, new SupplierDuplicateCheckResponse.SupplierDuplicateMatch(
                confidence,
                "own_business",
                supplier.getId(),
                marketplaceId,
                supplier.getName(),
                phone,
                email,
                taxId,
                null,
                supplierNumber,
                reasons));
    }

    private void addPlatformTenantMatch(
            Map<String, SupplierDuplicateCheckResponse.SupplierDuplicateMatch> matches,
            String businessId,
            SupplierIdentityIndex row,
            String confidence,
            String taxId,
            String phone,
            String email,
            boolean allowDrafts,
            List<String> reasons
    ) {
        if (row.getSupplierId() == null) {
            return;
        }
        if (businessId.equals(row.getBusinessId())) {
            addOwnBusinessMatch(matches, row, confidence, taxId, phone, email, reasons);
            return;
        }
        Supplier supplier = supplierRepository.findById(row.getSupplierId()).orElse(null);
        if (supplier == null || supplier.getDeletedAt() != null) {
            return;
        }
        if (supplier.getMarketplaceSupplierId() != null && !supplier.getMarketplaceSupplierId().isBlank()) {
            MarketplaceSupplier ms = marketplaceSupplierRepository.findById(supplier.getMarketplaceSupplierId())
                    .orElse(null);
            if (ms != null) {
                addMarketplaceEntity(matches, ms, confidence, taxId, phone, email, allowDrafts, reasons);
                return;
            }
        }
        String key = "platform:" + supplier.getId();
        putMerged(matches, key, new SupplierDuplicateCheckResponse.SupplierDuplicateMatch(
                confidence,
                "platform",
                supplier.getId(),
                null,
                supplier.getName(),
                phone != null ? phone : row.getPhoneNormalized(),
                email != null ? email : row.getEmailNormalized(),
                taxId,
                row.getRegionHint(),
                null,
                reasons));
    }

    private void addMarketplaceMatch(
            Map<String, SupplierDuplicateCheckResponse.SupplierDuplicateMatch> matches,
            SupplierIdentityIndex row,
            String confidence,
            String taxId,
            String phone,
            String email,
            boolean allowDrafts,
            List<String> reasons
    ) {
        if (row.getMarketplaceSupplierId() == null) {
            return;
        }
        MarketplaceSupplier supplier = marketplaceSupplierRepository.findById(row.getMarketplaceSupplierId()).orElse(null);
        if (supplier == null) {
            return;
        }
        addMarketplaceEntity(matches, supplier, confidence, taxId, phone, email, allowDrafts, reasons);
    }

    private void addMarketplaceEntity(
            Map<String, SupplierDuplicateCheckResponse.SupplierDuplicateMatch> matches,
            MarketplaceSupplier supplier,
            String confidence,
            String taxId,
            String phone,
            String email,
            boolean allowDrafts,
            List<String> reasons
    ) {
        if (MarketplaceSupplierStatuses.SUSPENDED.equalsIgnoreCase(supplier.getStatus())) {
            return;
        }
        if (MarketplaceSupplierStatuses.DRAFT.equalsIgnoreCase(supplier.getStatus()) && !allowDrafts) {
            return;
        }
        String key = "marketplace:" + supplier.getId();
        putMerged(matches, key, new SupplierDuplicateCheckResponse.SupplierDuplicateMatch(
                confidence,
                "marketplace",
                null,
                supplier.getId(),
                supplier.getName(),
                phone != null ? phone : supplier.getContactPhone(),
                email != null ? email : supplier.getContactEmail(),
                taxId,
                null,
                supplier.getSupplierNumber(),
                reasons));
    }

    private static void putMerged(
            Map<String, SupplierDuplicateCheckResponse.SupplierDuplicateMatch> matches,
            String key,
            SupplierDuplicateCheckResponse.SupplierDuplicateMatch incoming
    ) {
        matches.merge(key, incoming, SupplierDuplicateCheckService::mergeMatches);
    }

    private static SupplierDuplicateCheckResponse.SupplierDuplicateMatch mergeMatches(
            SupplierDuplicateCheckResponse.SupplierDuplicateMatch a,
            SupplierDuplicateCheckResponse.SupplierDuplicateMatch b
    ) {
        String confidence = "strong".equals(a.confidence()) || "strong".equals(b.confidence())
                ? "strong"
                : a.confidence();
        Set<String> reasons = new LinkedHashSet<>();
        if (a.matchReasons() != null) {
            reasons.addAll(a.matchReasons());
        }
        if (b.matchReasons() != null) {
            reasons.addAll(b.matchReasons());
        }
        return new SupplierDuplicateCheckResponse.SupplierDuplicateMatch(
                confidence,
                a.source(),
                a.localSupplierId() != null ? a.localSupplierId() : b.localSupplierId(),
                a.marketplaceSupplierId() != null ? a.marketplaceSupplierId() : b.marketplaceSupplierId(),
                a.name() != null ? a.name() : b.name(),
                a.phone() != null ? a.phone() : b.phone(),
                a.email() != null ? a.email() : b.email(),
                a.taxId() != null ? a.taxId() : b.taxId(),
                a.regionHint() != null ? a.regionHint() : b.regionHint(),
                a.supplierNumber() != null ? a.supplierNumber() : b.supplierNumber(),
                new ArrayList<>(reasons));
    }

    private static boolean namesMatch(String query, String candidate) {
        if (query == null || candidate == null || query.isBlank() || candidate.isBlank()) {
            return false;
        }
        if (candidate.contains(query) || query.contains(candidate)) {
            return true;
        }
        return nameSimilarity(query, candidate) >= NAME_SIMILARITY_THRESHOLD;
    }

    private static String confidenceForName(String query, String candidate) {
        if (query.equals(candidate) || candidate.contains(query) || query.contains(candidate)) {
            return "strong";
        }
        return nameSimilarity(query, candidate) >= 0.85 ? "strong" : "possible";
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
