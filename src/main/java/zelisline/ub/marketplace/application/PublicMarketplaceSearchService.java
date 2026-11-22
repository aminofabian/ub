package zelisline.ub.marketplace.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.MarketplaceSupplierDetailResponse;
import zelisline.ub.marketplace.api.dto.PublicMarketplaceProductSearchRow;
import zelisline.ub.marketplace.api.dto.PublicMarketplaceSupplierSearchRow;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.domain.MarketplaceSupplierPriceOffer;
import zelisline.ub.marketplace.domain.MarketplaceSupplierProduct;
import zelisline.ub.marketplace.domain.MarketplaceSupplierProductStatuses;
import zelisline.ub.marketplace.domain.MarketplaceSupplierStatuses;
import zelisline.ub.marketplace.repository.MarketplaceSupplierPriceOfferRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierProductRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.repository.SupplierRepository;

/**
 * Public supplier directory (ADR 0038, P0.1).
 *
 * <p>It lists <em>passports</em> ({@code marketplace_suppliers}) that are listed and active, with the
 * passport's own products, its confirmed prices and the contact details the supplier entered for
 * itself. A supplier can hide its prices and its contacts. It never reads a shop's supplier rows,
 * product links, items, costs, contacts or payout details: those are the shop's private records.
 * Every {@code id} in these payloads is a passport id or a passport product id.
 *
 * <p>A shop's supplier id is still accepted on the detail lookups, because older links and screens
 * send one; it only resolves to the listed passport that row is tagged to.
 */
@Service
@RequiredArgsConstructor
public class PublicMarketplaceSearchService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final MarketplaceSupplierProductRepository productRepository;
    private final MarketplaceSupplierPriceOfferRepository priceOfferRepository;
    private final SupplierRepository supplierRepository;
    private final ObjectMapper objectMapper;
    private final zelisline.ub.tenancy.repository.BusinessRepository businessRepository;
    private final zelisline.ub.tenancy.application.StorefrontSettingsService storefrontSettingsService;

    @Transactional(readOnly = true)
    public Page<PublicMarketplaceSupplierSearchRow> searchSuppliers(
            String q, String location, Pageable pageable) {
        return searchSuppliers(q, location, null, false, pageable);
    }

    /**
     * As above, narrowed to suppliers that deliver to {@code coverage} (one of their delivery
     * regions contains it) and, with {@code shopsOnly}, to suppliers that have their own shop open
     * online (P3-2).
     */
    @Transactional(readOnly = true)
    public Page<PublicMarketplaceSupplierSearchRow> searchSuppliers(
            String q, String location, String coverage, boolean shopsOnly, Pageable pageable) {
        String locationFilter = blankToNull(location);
        String coverageFilter = blankToNull(coverage) == null ? null : coverage.trim().toLowerCase(java.util.Locale.ROOT);
        boolean filtered = locationFilter != null || coverageFilter != null || shopsOnly;
        // Fetch a wider page when filtering (location, coverage and "has a shop" are resolved in memory).
        Pageable fetchPage = filtered
                ? Pageable.ofSize(Math.max(pageable.getPageSize() * 8, 80)).withPage(0)
                : pageable;
        Page<MarketplaceSupplier> page =
                marketplaceSupplierRepository.searchPublicDirectory(blankToEmpty(q), fetchPage);

        List<PublicMarketplaceSupplierSearchRow> rows = new ArrayList<>();
        for (MarketplaceSupplier passport : page.getContent()) {
            ListingLocation loc = locationOf(passport);
            if (locationFilter != null && !loc.matches(locationFilter)) {
                continue;
            }
            PublicMarketplaceSupplierSearchRow row = toSupplierRow(passport, loc);
            if (shopsOnly && row.shopSlug() == null) {
                continue;
            }
            if (coverageFilter != null) {
                final String wanted = coverageFilter;
                boolean delivers = row.deliveryRegions() != null && row.deliveryRegions().stream()
                        .anyMatch(region -> region != null && region.toLowerCase(java.util.Locale.ROOT).contains(wanted));
                if (!delivers) {
                    continue;
                }
            }
            rows.add(row);
        }
        if (filtered) {
            return slicePage(rows, pageable);
        }
        return new PageImpl<>(rows, pageable, page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public Page<PublicMarketplaceProductSearchRow> searchProducts(
            String q, String location, String supplierId, Pageable pageable) {
        String locationFilter = blankToNull(location);
        Pageable fetchPage = locationFilter != null
                ? Pageable.ofSize(Math.max(pageable.getPageSize() * 8, 120)).withPage(0)
                : pageable;
        Page<MarketplaceSupplierProduct> page = productRepository.searchPublicDirectory(
                blankToEmpty(q), blankToEmpty(supplierId), fetchPage);

        Map<String, MarketplaceSupplier> passportsById = new LinkedHashMap<>();
        List<String> passportIds = page.getContent().stream()
                .map(MarketplaceSupplierProduct::getMarketplaceSupplierId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        for (MarketplaceSupplier passport : marketplaceSupplierRepository.findAllById(passportIds)) {
            passportsById.put(passport.getId(), passport);
        }
        Map<String, Integer> productCounts = new LinkedHashMap<>();
        Instant now = Instant.now();

        List<PublicMarketplaceProductSearchRow> rows = new ArrayList<>(page.getContent().size());
        for (MarketplaceSupplierProduct product : page.getContent()) {
            MarketplaceSupplier passport = passportsById.get(product.getMarketplaceSupplierId());
            if (passport == null || !isPublic(passport)) {
                continue;
            }
            ListingLocation loc = locationOf(passport);
            if (locationFilter != null && !loc.matches(locationFilter)) {
                continue;
            }
            int supplierProductCount = productCounts.computeIfAbsent(passport.getId(), this::activeProductCount);
            MarketplaceSupplierPriceOffer offer = passport.isHidePrices()
                    ? null
                    : primaryPublicOffer(product.getId(), now);
            rows.add(new PublicMarketplaceProductSearchRow(
                    product.getId(),
                    product.getName(),
                    MarketplaceSlugService.productSlug(product.getName(), product.getId()),
                    blankToNull(product.getBarcode()),
                    blankToNull(product.getSku()),
                    blankToNull(product.getCategoryName()),
                    blankToNull(product.getImageUrl()),
                    passport.getId(),
                    passport.getName(),
                    passportSlug(passport),
                    null,
                    supplierProductCount,
                    loc.primary(),
                    loc.locations(),
                    product.getPackSize(),
                    blankToNull(product.getPackUnit()),
                    product.getMinOrderQty(),
                    offer != null ? offer.getUnitPrice() : null,
                    offer != null ? offer.getCurrency() : null,
                    true));
        }
        if (locationFilter != null) {
            return slicePage(rows, pageable);
        }
        return new PageImpl<>(rows, pageable, page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public List<String> listLocations() {
        Page<MarketplaceSupplier> page = marketplaceSupplierRepository.searchPublicDirectory(
                "", Pageable.ofSize(200).withPage(0));
        Set<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (MarketplaceSupplier passport : page.getContent()) {
            out.addAll(locationOf(passport).locations());
        }
        return List.copyOf(out);
    }

    /** {@code supplierId} is a passport id; a shop's supplier id resolves to its listed passport. */
    @Transactional(readOnly = true)
    public MarketplaceSupplierDetailResponse getSupplierDetail(String supplierId) {
        String needle = blankToNull(supplierId);
        if (needle == null) {
            throw notFound("Supplier not found");
        }
        Optional<MarketplaceSupplier> direct = marketplaceSupplierRepository.findById(needle);
        if (direct.isPresent()) {
            if (!isPublic(direct.get())) {
                throw notFound("Supplier is not available");
            }
            return buildSupplierDetail(direct.get());
        }
        MarketplaceSupplier viaShopRow = supplierRepository.findByIdAndDeletedAtIsNull(needle)
                .map(Supplier::getMarketplaceSupplierId)
                .filter(id -> id != null && !id.isBlank())
                .flatMap(marketplaceSupplierRepository::findById)
                .filter(PublicMarketplaceSearchService::isPublic)
                .orElseThrow(() -> notFound("Supplier not found"));
        return buildSupplierDetail(viaShopRow);
    }

    @Transactional(readOnly = true)
    public MarketplaceSupplierDetailResponse getSupplierDetailBySlug(String slug) {
        return buildSupplierDetail(resolvePassportBySlug(slug));
    }

    /**
     * Resolves a public product URL under a supplier slug.
     * Returns the supplier detail; callers match {@code productSlug} against catalogue product slugs.
     */
    @Transactional(readOnly = true)
    public MarketplaceSupplierDetailResponse getSupplierDetailForProductSlug(
            String supplierSlug, String productSlug) {
        MarketplaceSupplierDetailResponse detail = getSupplierDetailBySlug(supplierSlug);
        String needle = blankToNull(productSlug);
        if (needle == null) {
            throw notFound("Product not found");
        }
        boolean found = detail.products().stream().anyMatch(p -> needle.equalsIgnoreCase(p.slug()));
        if (!found) {
            String idPrefix = MarketplaceSlugService.extractIdPrefix(needle);
            if (idPrefix != null) {
                long matches = detail.products().stream()
                        .filter(p -> idPrefix.equals(MarketplaceSlugService.idPrefix(p.id())))
                        .count();
                found = matches == 1;
            }
        }
        if (!found) {
            throw notFound("Product not found");
        }
        return detail;
    }

    private MarketplaceSupplier resolvePassportBySlug(String slug) {
        String needle = blankToNull(slug);
        String idPrefix = needle != null ? MarketplaceSlugService.extractIdPrefix(needle) : null;
        if (idPrefix == null) {
            throw notFound("Supplier not found");
        }
        List<MarketplaceSupplier> candidates = marketplaceSupplierRepository.findListedActiveByIdPrefix(idPrefix);
        for (MarketplaceSupplier candidate : candidates) {
            if (needle.equalsIgnoreCase(passportSlug(candidate))) {
                return candidate;
            }
        }
        // The name may have changed since the link was shared.
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (candidates.isEmpty()) {
            // Links shared before ADR 0038 carry a shop's supplier id. Follow it to the listed
            // passport that row is tagged to, and to nothing else.
            Set<String> passportIds = new LinkedHashSet<>();
            for (Supplier shopRow : supplierRepository.findPublicActiveByIdPrefix(idPrefix)) {
                String passportId = shopRow.getMarketplaceSupplierId();
                if (passportId != null && !passportId.isBlank()) {
                    passportIds.add(passportId);
                }
            }
            if (passportIds.size() == 1) {
                Optional<MarketplaceSupplier> passport =
                        marketplaceSupplierRepository.findById(passportIds.iterator().next());
                if (passport.isPresent() && isPublic(passport.get())) {
                    return passport.get();
                }
            }
        }
        throw notFound("Supplier not found");
    }

    private MarketplaceSupplierDetailResponse buildSupplierDetail(MarketplaceSupplier passport) {
        ListingLocation loc = locationOf(passport);
        Instant now = Instant.now();

        List<MarketplaceSupplierProduct> shelf = new ArrayList<>(productRepository
                .findByMarketplaceSupplierIdAndStatus(passport.getId(), MarketplaceSupplierProductStatuses.ACTIVE));
        shelf.sort(Comparator.comparing(
                (MarketplaceSupplierProduct p) -> p.getName() == null ? "" : p.getName().toLowerCase(Locale.ROOT)));

        List<MarketplaceSupplierDetailResponse.MarketplaceCatalogProductPreview> products = new ArrayList<>();
        for (MarketplaceSupplierProduct product : shelf) {
            List<MarketplaceSupplierPriceOffer> offers = passport.isHidePrices()
                    ? List.of()
                    : publicOffers(product.getId(), now);
            MarketplaceSupplierPriceOffer primary = offers.isEmpty() ? null : offers.get(0);
            String imageUrl = blankToNull(product.getImageUrl());
            products.add(new MarketplaceSupplierDetailResponse.MarketplaceCatalogProductPreview(
                    product.getId(),
                    product.getName(),
                    MarketplaceSlugService.productSlug(product.getName(), product.getId()),
                    blankToNull(product.getBarcode()),
                    blankToNull(product.getSku()),
                    blankToNull(product.getCategoryName()),
                    imageUrl,
                    product.getPackSize(),
                    blankToNull(product.getPackUnit()),
                    product.getMinOrderQty(),
                    primary != null ? primary.getUnitPrice() : null,
                    primary != null ? primary.getCurrency() : null,
                    true,
                    product.getId(),
                    null,
                    null,
                    imageUrl,
                    toPackPreviews(offers)));
        }

        // Contacts are the supplier's own (from its passport) and only while it has not hidden them.
        // Payment, payout and credit fields stay empty: those came from a shop's own supplier record.
        String contactPhone = publicContactPhone(passport);
        String contactEmail = publicContactEmail(passport);
        List<MarketplaceSupplierDetailResponse.MarketplaceContactPreview> contacts =
                contactPhone == null && contactEmail == null
                        ? List.of()
                        : List.of(new MarketplaceSupplierDetailResponse.MarketplaceContactPreview(
                                publicContactName(passport), null, contactPhone, contactEmail, true));
        return new MarketplaceSupplierDetailResponse(
                passport.getId(),
                passport.getName(),
                passportSlug(passport),
                blankToNull(passport.getDescription()),
                null,
                null,
                loc.primary(),
                loc.locations(),
                passport.getStatus(),
                contactEmail,
                contactPhone,
                contacts,
                null,
                null,
                null,
                null,
                null,
                readJsonList(passport.getDeliveryRegionsJson()),
                readJsonList(passport.getCategoryTagsJson()),
                products,
                openShopSlug(passport));
    }

    /** The supplier's own shop, only when it exists and its online shop is switched on (ADR 0038, P1-9). */
    private String openShopSlug(MarketplaceSupplier passport) {
        String businessId = passport.getBusinessId();
        if (businessId == null || businessId.isBlank()) {
            return null;
        }
        return businessRepository.findById(businessId)
                .filter(shop -> {
                    try {
                        var settings = storefrontSettingsService.readFromSettingsJson(shop.getSettings());
                        return settings != null && settings.enabled();
                    } catch (RuntimeException unreadable) {
                        return false;
                    }
                })
                .map(shop -> blankToNull(shop.getSlug()))
                .orElse(null);
    }

    private PublicMarketplaceSupplierSearchRow toSupplierRow(MarketplaceSupplier passport, ListingLocation loc) {
        return new PublicMarketplaceSupplierSearchRow(
                passport.getId(),
                passport.getName(),
                passportSlug(passport),
                blankToNull(passport.getDescription()),
                null,
                null,
                loc.primary(),
                loc.locations(),
                activeProductCount(passport.getId()),
                publicContactPhone(passport) == null && publicContactEmail(passport) == null
                        ? null
                        : publicContactName(passport),
                publicContactPhone(passport),
                publicContactEmail(passport),
                null,
                null,
                readJsonList(passport.getDeliveryRegionsJson()),
                readJsonList(passport.getCategoryTagsJson()),
                openShopSlug(passport));
    }

    private int activeProductCount(String passportId) {
        return (int) Math.min(
                Integer.MAX_VALUE,
                productRepository.countByMarketplaceSupplierIdAndStatus(
                        passportId, MarketplaceSupplierProductStatuses.ACTIVE));
    }

    /**
     * Current offers whose price the supplier set itself. A price imported from a shop's cost is
     * not public, and neither is a zero placeholder.
     */
    private List<MarketplaceSupplierPriceOffer> publicOffers(String productId, Instant now) {
        List<MarketplaceSupplierPriceOffer> out = new ArrayList<>();
        for (MarketplaceSupplierPriceOffer offer : priceOfferRepository.findCurrentOffers(productId, now)) {
            if (offer.isPriceConfirmed()
                    && offer.getUnitPrice() != null
                    && offer.getUnitPrice().signum() > 0) {
                out.add(offer);
            }
        }
        return out;
    }

    private MarketplaceSupplierPriceOffer primaryPublicOffer(String productId, Instant now) {
        List<MarketplaceSupplierPriceOffer> offers = publicOffers(productId, now);
        return offers.isEmpty() ? null : offers.get(0);
    }

    /** Pack choices exist only when the supplier published more than one offer for the product. */
    private static List<MarketplaceSupplierDetailResponse.MarketplacePackOptionPreview> toPackPreviews(
            List<MarketplaceSupplierPriceOffer> offers) {
        if (offers.size() < 2) {
            return List.of();
        }
        List<MarketplaceSupplierDetailResponse.MarketplacePackOptionPreview> out = new ArrayList<>();
        for (MarketplaceSupplierPriceOffer offer : offers) {
            BigDecimal size = offer.getPackageSize();
            BigDecimal each = size != null && size.signum() > 0
                    ? offer.getUnitPrice().divide(size, 2, RoundingMode.HALF_UP)
                    : null;
            out.add(new MarketplaceSupplierDetailResponse.MarketplacePackOptionPreview(
                    offer.getId(),
                    null,
                    blankToNull(offer.getPackageUnit()),
                    size,
                    offer.getUnitPrice(),
                    each));
        }
        return out;
    }

    private static String publicContactPhone(MarketplaceSupplier passport) {
        return passport.isHideContacts() ? null : blankToNull(passport.getContactPhone());
    }

    private static String publicContactEmail(MarketplaceSupplier passport) {
        return passport.isHideContacts() ? null : blankToNull(passport.getContactEmail());
    }

    private static String publicContactName(MarketplaceSupplier passport) {
        String person = blankToNull(passport.getContactPerson());
        return person != null ? person : blankToNull(passport.getName());
    }

    private static boolean isPublic(MarketplaceSupplier passport) {
        return passport.isListed()
                && MarketplaceSupplierStatuses.ACTIVE.equalsIgnoreCase(passport.getStatus());
    }

    private static String passportSlug(MarketplaceSupplier passport) {
        return MarketplaceSlugService.buildSlug(passport.getName(), passport.getId(), "supplier");
    }

    /** The area the supplier named, then the regions it delivers to. */
    private ListingLocation locationOf(MarketplaceSupplier passport) {
        Set<String> labels = new LinkedHashSet<>();
        String area = blankToNull(passport.getContactLocation());
        if (area != null) {
            labels.add(area);
        }
        for (String region : readJsonList(passport.getDeliveryRegionsJson())) {
            String label = blankToNull(region);
            if (label != null) {
                labels.add(label);
            }
        }
        return ListingLocation.of(new ArrayList<>(labels));
    }

    private List<String> readJsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = objectMapper.readValue(json, STRING_LIST);
            return values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private static <T> Page<T> slicePage(List<T> rows, Pageable pageable) {
        int from = (int) Math.min(pageable.getOffset(), rows.size());
        int to = Math.min(from + pageable.getPageSize(), rows.size());
        return new PageImpl<>(rows.subList(from, to), pageable, rows.size());
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String blankToEmpty(String value) {
        String trimmed = blankToNull(value);
        return trimmed == null ? "" : trimmed;
    }

    private record ListingLocation(String primary, List<String> locations) {
        static ListingLocation empty() {
            return new ListingLocation(null, List.of());
        }

        static ListingLocation of(List<String> locations) {
            if (locations == null || locations.isEmpty()) {
                return empty();
            }
            return new ListingLocation(locations.get(0), List.copyOf(locations));
        }

        boolean matches(String filter) {
            if (filter == null || filter.isBlank() || locations.isEmpty()) {
                return false;
            }
            String needle = filter.trim().toLowerCase(Locale.ROOT);
            for (String loc : locations) {
                if (loc != null && loc.toLowerCase(Locale.ROOT).contains(needle)) {
                    return true;
                }
            }
            return false;
        }
    }
}
