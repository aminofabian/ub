package zelisline.ub.marketplace.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.ClaimSupplierUsernameRequest;
import zelisline.ub.marketplace.api.dto.LinkLocalSupplierRequest;
import zelisline.ub.marketplace.api.dto.PatchSupplierPortalProfileRequest;
import zelisline.ub.marketplace.api.dto.PatchSupplierPortalVisibilityRequest;
import zelisline.ub.marketplace.api.dto.SupplierPortalLinkCandidateRow;
import zelisline.ub.marketplace.api.dto.SupplierPortalLinkedShopRow;
import zelisline.ub.marketplace.api.dto.SupplierPortalProfileResponse;
import zelisline.ub.marketplace.api.dto.SupplierPortalVisibilityResponse;
import zelisline.ub.marketplace.domain.BusinessSupplierConnection;
import zelisline.ub.marketplace.domain.BusinessSupplierConnectionStatuses;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.domain.SupplierIdentityIndex;
import zelisline.ub.marketplace.repository.BusinessSupplierConnectionRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.marketplace.repository.SupplierIdentityIndexRepository;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierSlug;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class SupplierPortalProfileService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final int CANDIDATE_LIMIT = 25;

    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final BusinessSupplierConnectionRepository connectionRepository;
    private final SupplierRepository supplierRepository;
    private final BusinessRepository businessRepository;
    private final SupplierIdentityIndexRepository identityIndexRepository;
    private final SupplierIdentityIndexService identityIndexService;
    private final SupplierPortalShopLinkService shopLinkService;
    private final ObjectMapper objectMapper;

    @Transactional
    public SupplierPortalProfileResponse getProfile(String marketplaceSupplierId) {
        try {
            shopLinkService.ensureLinksAndCatalogue(marketplaceSupplierId);
        } catch (RuntimeException ignored) {
            // Soft heal.
        }
        MarketplaceSupplier supplier = requireSupplier(marketplaceSupplierId);
        return toResponse(supplier);
    }

    @Transactional
    public SupplierPortalProfileResponse updateProfile(
            String marketplaceSupplierId,
            PatchSupplierPortalProfileRequest request
    ) {
        MarketplaceSupplier supplier = requireSupplier(marketplaceSupplierId);
        if (request.description() != null) {
            supplier.setDescription(blankToNull(request.description()));
        }
        if (request.contactEmail() != null) {
            supplier.setContactEmail(blankToNull(request.contactEmail()));
        }
        if (request.contactPhone() != null) {
            supplier.setContactPhone(blankToNull(request.contactPhone()));
        }
        if (request.altContactPhone() != null) {
            String alt = blankToNull(request.altContactPhone());
            if (alt != null) {
                String normalized = StkPhoneNormalizer.normalize(alt);
                if (normalized == null || normalized.isBlank()) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "Enter a valid extra WhatsApp number");
                }
                supplier.setAltContactPhone(normalized);
            } else {
                supplier.setAltContactPhone(null);
            }
        }
        if (request.contactLocation() != null) {
            supplier.setContactLocation(blankToNull(request.contactLocation()));
        }
        if (request.deliveryRegions() != null) {
            supplier.setDeliveryRegionsJson(writeJson(request.deliveryRegions()));
        }
        if (request.categoryTags() != null) {
            supplier.setCategoryTagsJson(writeJson(request.categoryTags()));
        }
        marketplaceSupplierRepository.save(supplier);
        identityIndexService.upsertMarketplaceSupplier(supplier);
        return toResponse(supplier);
    }

    @Transactional
    public SupplierPortalProfileResponse claimUsername(
            String marketplaceSupplierId,
            ClaimSupplierUsernameRequest request
    ) {
        MarketplaceSupplier supplier = requireSupplier(marketplaceSupplierId);
        if (supplier.getUsername() != null && !supplier.getUsername().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already claimed");
        }
        String username = normalizeUsername(request.username());
        if (username.length() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username too short");
        }
        if (marketplaceSupplierRepository.existsByUsernameIgnoreCase(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username is taken");
        }
        supplier.setUsername(username);
        marketplaceSupplierRepository.save(supplier);
        return toResponse(supplier);
    }

    @Transactional(readOnly = true)
    public SupplierPortalVisibilityResponse getVisibility(String marketplaceSupplierId) {
        MarketplaceSupplier supplier = requireSupplier(marketplaceSupplierId);
        return new SupplierPortalVisibilityResponse(supplier.isHidePrices(), supplier.isHideContacts());
    }

    /** A supplier stays in the directory; it chooses whether its prices and contacts show there. */
    @Transactional
    public SupplierPortalVisibilityResponse updateVisibility(
            String marketplaceSupplierId,
            PatchSupplierPortalVisibilityRequest request
    ) {
        MarketplaceSupplier supplier = requireSupplier(marketplaceSupplierId);
        if (request.hidePrices() != null) {
            supplier.setHidePrices(request.hidePrices());
        }
        if (request.hideContacts() != null) {
            supplier.setHideContacts(request.hideContacts());
        }
        marketplaceSupplierRepository.save(supplier);
        return new SupplierPortalVisibilityResponse(supplier.isHidePrices(), supplier.isHideContacts());
    }

    @Transactional(readOnly = true)
    public List<SupplierPortalLinkCandidateRow> listLinkCandidates(String marketplaceSupplierId) {
        MarketplaceSupplier supplier = requireSupplier(marketplaceSupplierId);
        // Heal first so tagged locals show up as linked shops, not stale candidates.
        try {
            // Injected lazily via field below — see shopLinkService.
            shopLinkService.ensureLinksAndCatalogue(marketplaceSupplierId);
        } catch (RuntimeException ignored) {
            // Soft.
        }
        // No candidates are offered. The list used to match other tenants' supplier rows by this
        // account's contact phone, email and name, none of which is verified (the contact phone can be
        // edited in the profile), so it told any signed-in supplier which shops buy from whom. A supplier
        // can no longer link itself either (see linkLocalSupplier); the shop adds the supplier
        // (ADR 0038, P0.4).
        return List.of();
    }

    @Transactional
    public SupplierPortalProfileResponse linkLocalSupplier(
            String marketplaceSupplierId,
            LinkLocalSupplierRequest request
    ) {
        MarketplaceSupplier marketplace = requireSupplier(marketplaceSupplierId);
        String localId = request.localSupplierId().trim();
        Supplier local = supplierRepository.findByIdAndDeletedAtIsNull(localId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Local supplier not found"));

        if (local.getMarketplaceSupplierId() != null
                && !local.getMarketplaceSupplierId().equals(marketplaceSupplierId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Supplier already linked to another account");
        }
        if (connectionRepository.existsByLocalSupplierIdAndStatus(
                localId, BusinessSupplierConnectionStatuses.ACTIVE)) {
            var existing = connectionRepository
                    .findByMarketplaceSupplierIdAndLocalSupplierId(marketplaceSupplierId, localId);
            if (existing.isEmpty()
                    || !BusinessSupplierConnectionStatuses.ACTIVE.equals(existing.get().getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Supplier already claimed by another account");
            }
            return toResponse(marketplace);
        }

        // A supplier cannot connect itself to a shop: that would open the shop's orders, invoices and
        // payments for this supplier to whoever asked. Only the shop can add the supplier (ADR 0038,
        // P0.4). Nothing is written.
        throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Only the shop can add you. Ask the shop to add you from its Suppliers page using your "
                        + "supplier number " + marketplace.getSupplierNumber() + ".");
    }

    private void addCandidate(
            Map<String, SupplierPortalLinkCandidateRow> byLocalId,
            SupplierIdentityIndex row,
            String reason,
            String marketplaceSupplierId
    ) {
        if (row.getSupplierId() == null || row.getBusinessId() == null) {
            return;
        }
        if (byLocalId.containsKey(row.getSupplierId())) {
            return;
        }
        if (connectionRepository.existsByLocalSupplierIdAndStatus(
                row.getSupplierId(), BusinessSupplierConnectionStatuses.ACTIVE)) {
            return;
        }
        Supplier local = supplierRepository.findByIdAndDeletedAtIsNull(row.getSupplierId()).orElse(null);
        if (local == null) {
            return;
        }
        if (local.getMarketplaceSupplierId() != null
                && !local.getMarketplaceSupplierId().equals(marketplaceSupplierId)) {
            return;
        }
        Business business = businessRepository.findById(row.getBusinessId()).orElse(null);
        String shopName = business != null && business.getName() != null
                ? business.getName().trim()
                : "Shop";
        byLocalId.put(row.getSupplierId(), new SupplierPortalLinkCandidateRow(
                local.getId(),
                row.getBusinessId(),
                shopName,
                local.getName(),
                reason));
    }

    private MarketplaceSupplier requireSupplier(String marketplaceSupplierId) {
        return marketplaceSupplierRepository.findById(marketplaceSupplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
    }

    private SupplierPortalProfileResponse toResponse(MarketplaceSupplier supplier) {
        List<SupplierPortalLinkedShopRow> linked = new ArrayList<>();
        for (BusinessSupplierConnection c : connectionRepository.findByMarketplaceSupplierIdAndStatus(
                supplier.getId(), BusinessSupplierConnectionStatuses.ACTIVE)) {
            Business business = businessRepository.findById(c.getBusinessId()).orElse(null);
            Supplier local = supplierRepository.findByIdAndDeletedAtIsNull(c.getLocalSupplierId()).orElse(null);
            linked.add(new SupplierPortalLinkedShopRow(
                    c.getId(),
                    c.getBusinessId(),
                    business != null && business.getName() != null ? business.getName().trim() : "Shop",
                    c.getLocalSupplierId(),
                    local != null ? local.getName() : "Supplier",
                    c.getStatus()));
        }
        String username = supplier.getUsername();
        String hubPath = username != null && !username.isBlank() ? "/s/" + username : null;
        return new SupplierPortalProfileResponse(
                supplier.getId(),
                supplier.getName(),
                username,
                supplier.getDescription(),
                supplier.getContactEmail(),
                supplier.getContactPhone(),
                supplier.getAltContactPhone(),
                supplier.getContactLocation(),
                supplier.getStatus(),
                readJsonList(supplier.getDeliveryRegionsJson()),
                readJsonList(supplier.getCategoryTagsJson()),
                hubPath,
                List.copyOf(linked));
    }

    public static String normalizeUsername(String raw) {
        String slug = SupplierSlug.slugify(raw == null ? "" : raw.trim());
        if (slug.startsWith("@")) {
            slug = SupplierSlug.slugify(slug.substring(1));
        }
        return slug;
    }

    private List<String> readJsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String writeJson(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
