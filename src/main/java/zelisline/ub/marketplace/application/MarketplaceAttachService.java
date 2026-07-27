package zelisline.ub.marketplace.application;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.api.dto.CreateItemRequest;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.marketplace.api.dto.MarketplaceAttachResponse;
import zelisline.ub.marketplace.domain.BusinessSupplierConnection;
import zelisline.ub.marketplace.domain.BusinessSupplierConnectionStatuses;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.domain.MarketplaceSupplierPriceOffer;
import zelisline.ub.marketplace.domain.MarketplaceSupplierProduct;
import zelisline.ub.marketplace.domain.MarketplaceSupplierProductStatuses;
import zelisline.ub.marketplace.domain.MarketplaceSupplierStatuses;
import zelisline.ub.marketplace.repository.BusinessSupplierConnectionRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierPriceOfferRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierProductRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.platform.application.PlatformSupplierPortalSettingsService;
import zelisline.ub.platform.domain.PlatformSupplierPortalSettings;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierContact;
import zelisline.ub.suppliers.domain.SupplierProduct;
import zelisline.ub.suppliers.repository.SupplierContactRepository;
import zelisline.ub.suppliers.repository.SupplierProductRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;

/**
 * Attaches a shop to a marketplace supplier by marketplace id or S-number,
 * importing catalogue links and creating missing local items.
 */
@Service
@RequiredArgsConstructor
public class MarketplaceAttachService {

    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final BusinessSupplierConnectionRepository connectionRepository;
    private final SupplierRepository supplierRepository;
    private final SupplierContactRepository supplierContactRepository;
    private final SupplierProductRepository supplierProductRepository;
    private final MarketplaceSupplierProductRepository marketplaceProductRepository;
    private final MarketplaceSupplierPriceOfferRepository priceOfferRepository;
    private final ItemRepository itemRepository;
    private final ItemTypeRepository itemTypeRepository;
    private final MarketplaceItemImportHelper itemImportHelper;
    private final SupplierIdentityIndexService identityIndexService;
    private final MarketplaceSupplierPassportService passportService;
    private final PlatformSupplierPortalSettingsService portalSettingsService;
    private final SupplierPortalShopLinkService shopLinkService;

    @Transactional
    public MarketplaceAttachResponse attachByMarketplaceId(String businessId, String marketplaceSupplierId) {
        MarketplaceSupplier marketplace = requireFindableMarketplace(marketplaceSupplierId);
        return attachMarketplace(businessId, marketplace, null);
    }

    @Transactional
    public MarketplaceAttachResponse attachBySupplierNumber(String businessId, String supplierNumberRaw) {
        String number = SupplierNumberFormat.normalize(supplierNumberRaw);
        if (number == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid supplier number");
        }
        MarketplaceSupplier marketplace = marketplaceSupplierRepository.findBySupplierNumber(number)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
        assertFindable(marketplace);
        return attachMarketplace(businessId, marketplace, null);
    }

    /**
     * Attach using another shop's local supplier row (platform discovery).
     * Promotes that seed to a global passport if needed, then attaches.
     */
    @Transactional
    public MarketplaceAttachResponse attachFromPlatformSeed(String businessId, String sourceLocalSupplierId) {
        Supplier source = supplierRepository.findByIdAndDeletedAtIsNull(sourceLocalSupplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
        if (businessId.equals(source.getBusinessId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This supplier already belongs to your business");
        }

        MarketplaceSupplier marketplace;
        if (source.getMarketplaceSupplierId() != null && !source.getMarketplaceSupplierId().isBlank()) {
            marketplace = marketplaceSupplierRepository.findById(source.getMarketplaceSupplierId())
                    .orElse(null);
        } else {
            marketplace = null;
        }
        if (marketplace == null) {
            String phone = source.getPayoutPhone();
            if (phone == null || phone.isBlank()) {
                phone = supplierContactRepository
                        .findBySupplierIdOrderByPrimaryContactDescNameAsc(source.getId())
                        .stream()
                        .map(SupplierContact::getPhone)
                        .filter(p -> p != null && !p.isBlank())
                        .findFirst()
                        .orElse(null);
            }
            marketplace = passportService.createDraftPassport(
                    source.getName(),
                    phone,
                    null,
                    source.getVatPin());
            source.setMarketplaceSupplierId(marketplace.getId());
            supplierRepository.save(source);
            identityIndexService.upsertTenantSupplier(source, phone, null);
            try {
                shopLinkService.ensureLinksAndCatalogue(marketplace.getId());
            } catch (RuntimeException ignored) {
                // Soft.
            }
        } else {
            assertFindable(marketplace);
            // Prefer the seed shop's real name over phone-derived "Supplier 2874".
            passportService.upgradeNameIfPlaceholder(marketplace, source.getName());
        }

        return attachMarketplace(businessId, marketplace, source.getId());
    }

    private MarketplaceAttachResponse attachMarketplace(
            String businessId,
            MarketplaceSupplier marketplace,
            String seedLocalSupplierId
    ) {
        var existingConn = connectionRepository.findByBusinessIdAndMarketplaceSupplierId(
                businessId, marketplace.getId());
        if (existingConn.isPresent()) {
            BusinessSupplierConnection conn = existingConn.get();
            if (BusinessSupplierConnectionStatuses.ACTIVE.equals(conn.getStatus())) {
                Supplier local = supplierRepository.findByIdAndDeletedAtIsNull(conn.getLocalSupplierId())
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.CONFLICT, "Existing connection points to a missing local supplier"));
                ImportStats stats = importMarketplaceCatalogue(businessId, local.getId(), marketplace.getId());
                if (seedLocalSupplierId != null) {
                    stats = stats.merge(importSeedLocalCatalogue(businessId, local.getId(), seedLocalSupplierId));
                }
                return toResponse(conn.getId(), local, marketplace, stats);
            }
            conn.setStatus(BusinessSupplierConnectionStatuses.ACTIVE);
            connectionRepository.save(conn);
            Supplier local = supplierRepository.findByIdAndDeletedAtIsNull(conn.getLocalSupplierId())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.CONFLICT, "Existing connection points to a missing local supplier"));
            ImportStats stats = importMarketplaceCatalogue(businessId, local.getId(), marketplace.getId());
            if (seedLocalSupplierId != null) {
                stats = stats.merge(importSeedLocalCatalogue(businessId, local.getId(), seedLocalSupplierId));
            }
            return toResponse(conn.getId(), local, marketplace, stats);
        }

        String preferredName = marketplace.getName();
        if (seedLocalSupplierId != null) {
            Supplier seed = supplierRepository.findByIdAndDeletedAtIsNull(seedLocalSupplierId).orElse(null);
            if (seed != null && seed.getName() != null && !seed.getName().isBlank()) {
                passportService.upgradeNameIfPlaceholder(marketplace, seed.getName());
                preferredName = MarketplaceSupplierNaming.preferDisplayName(
                        seed.getName(), marketplace.getName());
            }
        } else if (MarketplaceSupplierNaming.isPlaceholderName(marketplace.getName())) {
            // Heal from any already-linked local shop name before cloning into this shop.
            preferredName = supplierRepository
                    .findByMarketplaceSupplierIdAndDeletedAtIsNull(marketplace.getId())
                    .stream()
                    .map(Supplier::getName)
                    .filter(n -> n != null && !MarketplaceSupplierNaming.isPlaceholderName(n))
                    .findFirst()
                    .orElse(preferredName);
            passportService.upgradeNameIfPlaceholder(marketplace, preferredName);
            preferredName = MarketplaceSupplierNaming.preferDisplayName(preferredName, marketplace.getName());
        }

        if (supplierRepository.existsDuplicateName(businessId, preferredName, null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A local supplier with this name already exists; open it or rename before attaching.");
        }

        Supplier local = new Supplier();
        local.setBusinessId(businessId);
        local.setName(preferredName);
        local.setSupplierType("distributor");
        local.setStatus("active");
        local.setVatPin(marketplace.getTaxPin() != null ? marketplace.getTaxPin() : marketplace.getVatNumber());
        local.setMarketplaceSupplierId(marketplace.getId());
        local.setNotes("Attached from global supplier " + marketplace.getSupplierNumber());
        if (marketplace.getContactPhone() != null && !marketplace.getContactPhone().isBlank()) {
            local.setPayoutPhone(marketplace.getContactPhone());
        }
        supplierRepository.save(local);
        identityIndexService.upsertTenantSupplier(
                local, marketplace.getContactPhone(), marketplace.getContactEmail());

        if ((marketplace.getContactPhone() != null && !marketplace.getContactPhone().isBlank())
                || (marketplace.getContactEmail() != null && !marketplace.getContactEmail().isBlank())
                || (marketplace.getContactPerson() != null && !marketplace.getContactPerson().isBlank())) {
            SupplierContact contact = new SupplierContact();
            contact.setSupplierId(local.getId());
            contact.setName(marketplace.getContactPerson() != null && !marketplace.getContactPerson().isBlank()
                    ? marketplace.getContactPerson()
                    : marketplace.getName());
            contact.setPhone(marketplace.getContactPhone());
            contact.setEmail(marketplace.getContactEmail());
            contact.setPrimaryContact(true);
            supplierContactRepository.save(contact);
        }

        BusinessSupplierConnection connection = new BusinessSupplierConnection();
        connection.setBusinessId(businessId);
        connection.setMarketplaceSupplierId(marketplace.getId());
        connection.setLocalSupplierId(local.getId());
        connection.setStatus(BusinessSupplierConnectionStatuses.ACTIVE);
        connection.setCanViewPurchaseHistory(true);
        connectionRepository.save(connection);

        ImportStats stats = importMarketplaceCatalogue(businessId, local.getId(), marketplace.getId());
        if (seedLocalSupplierId != null) {
            stats = stats.merge(importSeedLocalCatalogue(businessId, local.getId(), seedLocalSupplierId));
        }

        try {
            shopLinkService.ensureLinksAndCatalogue(marketplace.getId());
        } catch (RuntimeException ignored) {
            // Soft — attach should succeed even if portal heal fails.
        }

        return toResponse(connection.getId(), local, marketplace, stats);
    }

    private ImportStats importSeedLocalCatalogue(
            String businessId,
            String localSupplierId,
            String sourceSupplierId
    ) {
        List<SupplierProduct> sourceLinks =
                supplierProductRepository.listActivePublicForSupplier(sourceSupplierId);
        int linkedExisting = 0;
        int alreadyLinked = 0;
        int skipped = 0;
        for (SupplierProduct sourceLink : sourceLinks) {
            Item sourceItem = itemRepository.findById(sourceLink.getItemId()).orElse(null);
            if (sourceItem == null
                    || sourceItem.getDeletedAt() != null
                    || !sourceItem.isActive()
                    || sourceItem.getBarcode() == null
                    || sourceItem.getBarcode().isBlank()) {
                skipped++;
                continue;
            }
            var localItem = itemRepository.findByBusinessIdAndBarcodeAndDeletedAtIsNull(
                    businessId, sourceItem.getBarcode().trim());
            if (localItem.isEmpty()) {
                skipped++;
                continue;
            }
            String itemId = localItem.get().getId();
            SupplierProduct link = supplierProductRepository.findBySupplierIdAndItemId(localSupplierId, itemId)
                    .orElseGet(SupplierProduct::new);
            if (link.getId() != null && link.getDeletedAt() == null) {
                alreadyLinked++;
                continue;
            }
            link.setSupplierId(localSupplierId);
            link.setItemId(itemId);
            link.setSupplierSku(sourceLink.getSupplierSku());
            link.setPackSize(sourceLink.getPackSize());
            link.setPackUnit(sourceLink.getPackUnit());
            link.setMinOrderQty(sourceLink.getMinOrderQty());
            link.setDefaultCostPrice(sourceLink.getDefaultCostPrice() != null
                    ? sourceLink.getDefaultCostPrice()
                    : sourceLink.getLastCostPrice());
            link.setActive(true);
            link.setDeletedAt(null);
            supplierProductRepository.save(link);
            linkedExisting++;
        }
        return new ImportStats(linkedExisting, 0, alreadyLinked, skipped);
    }

    private ImportStats importMarketplaceCatalogue(
            String businessId,
            String localSupplierId,
            String marketplaceSupplierId
    ) {
        String defaultItemTypeId = itemTypeRepository.findByBusinessIdAndTypeKey(businessId, "goods")
                .or(() -> itemTypeRepository.findByBusinessIdAndIsDefaultTrue(businessId))
                .map(t -> t.getId())
                .orElse(null);

        List<MarketplaceSupplierProduct> products =
                marketplaceProductRepository.findByMarketplaceSupplierIdAndStatus(
                        marketplaceSupplierId, MarketplaceSupplierProductStatuses.ACTIVE);

        int linkedExisting = 0;
        int createdItems = 0;
        int alreadyLinked = 0;
        int skipped = 0;

        for (MarketplaceSupplierProduct product : products) {
            String barcode = product.getBarcode() != null ? product.getBarcode().trim() : null;
            Item localItem = null;
            if (barcode != null && !barcode.isBlank()) {
                localItem = itemRepository.findByBusinessIdAndBarcodeAndDeletedAtIsNull(businessId, barcode)
                        .orElse(null);
            }

            boolean created = false;
            if (localItem == null) {
                if (defaultItemTypeId == null) {
                    skipped++;
                    continue;
                }
                if ((barcode == null || barcode.isBlank())
                        && (product.getName() == null || product.getName().isBlank())) {
                    skipped++;
                    continue;
                }
                try {
                    BigDecimal buyingPrice = primaryOfferPrice(product.getId());
                    CreateItemRequest createReq = new CreateItemRequest(
                            blankToNull(product.getSku()),
                            blankToNull(barcode),
                            product.getName().trim(),
                            blankToNull(product.getDescription()),
                            defaultItemTypeId,
                            null,
                            null,
                            product.getPackUnit() != null ? product.getPackUnit() : "pcs",
                            false,
                            true,
                            true,
                            product.getPackUnit(),
                            product.getPackSize(),
                            null,
                            null,
                            buyingPrice,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null
                    );
                    String createdId = itemImportHelper.createItemOrNull(businessId, createReq);
                    if (createdId == null) {
                        skipped++;
                        continue;
                    }
                    localItem = itemRepository.findById(createdId).orElse(null);
                    if (localItem == null) {
                        skipped++;
                        continue;
                    }
                    created = true;
                } catch (RuntimeException ex) {
                    skipped++;
                    continue;
                }
            }

            SupplierProduct link = supplierProductRepository
                    .findBySupplierIdAndItemId(localSupplierId, localItem.getId())
                    .orElseGet(SupplierProduct::new);
            if (link.getId() != null && link.getDeletedAt() == null) {
                alreadyLinked++;
                continue;
            }
            link.setSupplierId(localSupplierId);
            link.setItemId(localItem.getId());
            link.setSupplierSku(blankToNull(product.getSku()));
            link.setPackSize(product.getPackSize());
            link.setPackUnit(product.getPackUnit());
            link.setMinOrderQty(product.getMinOrderQty());
            BigDecimal cost = primaryOfferPrice(product.getId());
            if (cost != null) {
                link.setDefaultCostPrice(cost);
            }
            link.setActive(true);
            link.setDeletedAt(null);
            supplierProductRepository.save(link);
            if (created) {
                createdItems++;
            } else {
                linkedExisting++;
            }
        }

        return new ImportStats(linkedExisting, createdItems, alreadyLinked, skipped);
    }

    private BigDecimal primaryOfferPrice(String productId) {
        List<MarketplaceSupplierPriceOffer> offers = priceOfferRepository.findByProductId(productId);
        return offers.stream()
                .filter(MarketplaceSupplierPriceOffer::isAvailable)
                .map(MarketplaceSupplierPriceOffer::getUnitPrice)
                .findFirst()
                .orElse(null);
    }

    private MarketplaceSupplier requireFindableMarketplace(String marketplaceSupplierId) {
        MarketplaceSupplier marketplace = marketplaceSupplierRepository.findById(marketplaceSupplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
        assertFindable(marketplace);
        return marketplace;
    }

    private void assertFindable(MarketplaceSupplier marketplace) {
        if (MarketplaceSupplierStatuses.SUSPENDED.equalsIgnoreCase(marketplace.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier is not available");
        }
        if (MarketplaceSupplierStatuses.DRAFT.equalsIgnoreCase(marketplace.getStatus())) {
            PlatformSupplierPortalSettings settings = portalSettingsService.loadSingleton();
            if (!settings.isAllowFindUnclaimedDrafts()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier is not available");
            }
        }
    }

    private static MarketplaceAttachResponse toResponse(
            String connectionId,
            Supplier local,
            MarketplaceSupplier marketplace,
            ImportStats stats
    ) {
        return new MarketplaceAttachResponse(
                connectionId,
                local.getId(),
                marketplace.getId(),
                marketplace.getSupplierNumber(),
                local.getName(),
                stats.linkedExisting(),
                stats.createdItems(),
                stats.alreadyLinked(),
                stats.skipped(),
                "active");
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private record ImportStats(int linkedExisting, int createdItems, int alreadyLinked, int skipped) {
        ImportStats merge(ImportStats other) {
            return new ImportStats(
                    linkedExisting + other.linkedExisting,
                    createdItems + other.createdItems,
                    alreadyLinked + other.alreadyLinked,
                    skipped + other.skipped);
        }
    }
}
