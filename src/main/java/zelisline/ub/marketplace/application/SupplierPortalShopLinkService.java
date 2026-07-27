package zelisline.ub.marketplace.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.marketplace.domain.BusinessSupplierConnection;
import zelisline.ub.marketplace.domain.BusinessSupplierConnectionStatuses;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.domain.MarketplaceSupplierPriceOffer;
import zelisline.ub.marketplace.domain.MarketplaceSupplierProduct;
import zelisline.ub.marketplace.domain.MarketplaceSupplierProductStatuses;
import zelisline.ub.marketplace.domain.SupplierIdentityIndex;
import zelisline.ub.marketplace.domain.SupplierUser;
import zelisline.ub.marketplace.repository.BusinessSupplierConnectionRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierPriceOfferRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierProductRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.marketplace.repository.SupplierIdentityIndexRepository;
import zelisline.ub.marketplace.repository.SupplierUserRepository;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierContact;
import zelisline.ub.suppliers.domain.SupplierProduct;
import zelisline.ub.suppliers.repository.SupplierContactRepository;
import zelisline.ub.suppliers.repository.SupplierProductRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;

/**
 * Heals missing shop connections and imports shop-linked catalogue items into the
 * portal marketplace catalogue so the supplier sees the same products shops already use.
 *
 * Shop "linked products" live on tenant {@code supplier_products}. Portal shops/catalogue
 * need {@code business_supplier_connections} (+ optional MSP import). Contact phones on
 * {@code supplier_contacts} are the usual match key when payout phone was never set.
 */
@Service
@RequiredArgsConstructor
public class SupplierPortalShopLinkService {

    private static final Logger log = LoggerFactory.getLogger(SupplierPortalShopLinkService.class);

    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final BusinessSupplierConnectionRepository connectionRepository;
    private final SupplierRepository supplierRepository;
    private final SupplierContactRepository supplierContactRepository;
    private final SupplierUserRepository supplierUserRepository;
    private final SupplierIdentityIndexRepository identityIndexRepository;
    private final SupplierIdentityIndexService identityIndexService;
    private final MarketplaceSupplierPassportService passportService;
    private final SupplierProductRepository supplierProductRepository;
    private final MarketplaceSupplierProductRepository productRepository;
    private final MarketplaceSupplierPriceOfferRepository priceOfferRepository;
    private final ItemRepository itemRepository;

    @Transactional
    public int ensureLinksAndCatalogue(String marketplaceSupplierId) {
        if (marketplaceSupplierId == null || marketplaceSupplierId.isBlank()) {
            return 0;
        }
        MarketplaceSupplier marketplace = marketplaceSupplierRepository.findById(marketplaceSupplierId)
                .orElse(null);
        if (marketplace == null) {
            return 0;
        }

        int linked = 0;
        linked += linkLocalsAlreadyTagged(marketplaceSupplierId);
        for (String phone : collectPhones(marketplace)) {
            linked += linkLocalsByPhone(marketplaceSupplierId, phone);
        }
        for (String email : collectEmails(marketplace)) {
            linked += linkLocalsByEmail(marketplaceSupplierId, email);
        }

        healPlaceholderName(marketplace);

        int imported = 0;
        for (BusinessSupplierConnection link : connectionRepository.findByMarketplaceSupplierIdAndStatus(
                marketplaceSupplierId, BusinessSupplierConnectionStatuses.ACTIVE)) {
            imported += importCatalogueFromLocal(marketplaceSupplierId, link.getLocalSupplierId());
        }
        if (linked > 0 || imported > 0) {
            log.info(
                    "supplier portal heal marketplace={} linkedShops={} importedProducts={}",
                    marketplaceSupplierId,
                    linked,
                    imported);
        }
        return linked + imported;
    }

    private void healPlaceholderName(MarketplaceSupplier marketplace) {
        if (!MarketplaceSupplierNaming.isPlaceholderName(marketplace.getName())) {
            return;
        }
        String better = supplierRepository
                .findByMarketplaceSupplierIdAndDeletedAtIsNull(marketplace.getId())
                .stream()
                .map(Supplier::getName)
                .filter(n -> n != null && !MarketplaceSupplierNaming.isPlaceholderName(n))
                .findFirst()
                .orElse(null);
        if (better != null) {
            passportService.upgradeNameIfPlaceholder(marketplace, better);
        }
    }

    private Set<String> collectPhones(MarketplaceSupplier marketplace) {
        Set<String> phones = new HashSet<>();
        addPhone(phones, marketplace.getContactPhone());
        for (SupplierUser user : supplierUserRepository.findByMarketplaceSupplierIdAndActiveTrue(
                marketplace.getId())) {
            addPhone(phones, user.getPhone());
        }
        return phones;
    }

    private Set<String> collectEmails(MarketplaceSupplier marketplace) {
        Set<String> emails = new HashSet<>();
        addEmail(emails, marketplace.getContactEmail());
        for (SupplierUser user : supplierUserRepository.findByMarketplaceSupplierIdAndActiveTrue(
                marketplace.getId())) {
            addEmail(emails, user.getEmail());
        }
        return emails;
    }

    private static void addPhone(Set<String> phones, String raw) {
        String stk = StkPhoneNormalizer.normalize(raw);
        if (stk != null) {
            phones.add(stk);
        }
        String alt = SupplierIdentityNormalizer.normalizePhone(raw);
        if (alt != null) {
            phones.add(alt);
        }
    }

    private static void addEmail(Set<String> emails, String raw) {
        String email = SupplierIdentityNormalizer.normalizeEmail(raw);
        if (email != null) {
            emails.add(email);
        }
    }

    private int linkLocalsAlreadyTagged(String marketplaceSupplierId) {
        int count = 0;
        for (Supplier local : supplierRepository.findByMarketplaceSupplierIdAndDeletedAtIsNull(
                marketplaceSupplierId)) {
            if (ensureConnection(marketplaceSupplierId, local, null)) {
                count += 1;
            }
        }
        return count;
    }

    private int linkLocalsByPhone(String marketplaceSupplierId, String phone) {
        if (phone == null || phone.isBlank()) {
            return 0;
        }
        String alt = SupplierIdentityNormalizer.normalizePhone(phone);
        if (alt == null) {
            alt = phone;
        }
        String tail = phone.length() >= 9 ? phone.substring(phone.length() - 9) : phone;

        Set<String> seenLocals = new HashSet<>();
        int count = 0;

        for (SupplierIdentityIndex row : identityIndexRepository.findTenantByPhoneVariants(
                phone, alt, tail)) {
            if (row.getSupplierId() == null || !seenLocals.add(row.getSupplierId())) {
                continue;
            }
            Supplier local = supplierRepository.findByIdAndDeletedAtIsNull(row.getSupplierId()).orElse(null);
            if (local == null) {
                continue;
            }
            if (ensureConnection(marketplaceSupplierId, local, phone)) {
                count += 1;
            }
        }

        for (Supplier local : supplierRepository.findActiveByPayoutPhoneVariants(phone, alt, tail)) {
            if (!seenLocals.add(local.getId())) {
                continue;
            }
            if (ensureConnection(marketplaceSupplierId, local, phone)) {
                count += 1;
            }
        }

        // Shop contacts are where phone usually lives (screenshot: Fabian / 0714…).
        for (SupplierContact contact : supplierContactRepository.findByPhoneVariants(phone, alt, tail)) {
            if (contact.getSupplierId() == null || !seenLocals.add(contact.getSupplierId())) {
                continue;
            }
            Supplier local = supplierRepository.findByIdAndDeletedAtIsNull(contact.getSupplierId()).orElse(null);
            if (local == null) {
                continue;
            }
            String matchPhone = contact.getPhone() != null ? contact.getPhone() : phone;
            if (ensureConnection(marketplaceSupplierId, local, matchPhone)) {
                count += 1;
            }
        }
        return count;
    }

    private int linkLocalsByEmail(String marketplaceSupplierId, String email) {
        if (email == null || email.isBlank()) {
            return 0;
        }
        Set<String> seenLocals = new HashSet<>();
        int count = 0;

        for (SupplierIdentityIndex row : identityIndexRepository.findTenantByEmail(email)) {
            if (row.getSupplierId() == null || !seenLocals.add(row.getSupplierId())) {
                continue;
            }
            Supplier local = supplierRepository.findByIdAndDeletedAtIsNull(row.getSupplierId()).orElse(null);
            if (local == null) {
                continue;
            }
            if (ensureConnection(marketplaceSupplierId, local, null)) {
                count += 1;
            }
        }

        for (SupplierContact contact : supplierContactRepository.findByEmailIgnoreCase(email)) {
            if (contact.getSupplierId() == null || !seenLocals.add(contact.getSupplierId())) {
                continue;
            }
            Supplier local = supplierRepository.findByIdAndDeletedAtIsNull(contact.getSupplierId()).orElse(null);
            if (local == null) {
                continue;
            }
            if (ensureConnection(marketplaceSupplierId, local, contact.getPhone())) {
                count += 1;
            }
        }
        return count;
    }

    /**
     * @return true when a new ACTIVE connection was created or an existing one reactivated
     */
    private boolean ensureConnection(String marketplaceSupplierId, Supplier local, String matchPhone) {
        if (local.getMarketplaceSupplierId() != null
                && !local.getMarketplaceSupplierId().equals(marketplaceSupplierId)) {
            return false;
        }

        var byLocal = connectionRepository.findByLocalSupplierId(local.getId());
        if (byLocal.isPresent()) {
            BusinessSupplierConnection existing = byLocal.get();
            if (!marketplaceSupplierId.equals(existing.getMarketplaceSupplierId())) {
                return false;
            }
            boolean changed = false;
            if (!BusinessSupplierConnectionStatuses.ACTIVE.equals(existing.getStatus())) {
                existing.setStatus(BusinessSupplierConnectionStatuses.ACTIVE);
                changed = true;
            }
            if (local.getMarketplaceSupplierId() == null) {
                local.setMarketplaceSupplierId(marketplaceSupplierId);
                supplierRepository.save(local);
                changed = true;
            }
            backfillPayoutPhone(local, matchPhone);
            if (changed) {
                connectionRepository.save(existing);
                return true;
            }
            return false;
        }

        if (connectionRepository.existsByBusinessIdAndMarketplaceSupplierId(
                local.getBusinessId(), marketplaceSupplierId)) {
            // Another local at this shop already linked — still tag this local for orders/catalogue.
            if (local.getMarketplaceSupplierId() == null) {
                local.setMarketplaceSupplierId(marketplaceSupplierId);
                supplierRepository.save(local);
                backfillPayoutPhone(local, matchPhone);
                return true;
            }
            return false;
        }

        BusinessSupplierConnection connection = new BusinessSupplierConnection();
        connection.setBusinessId(local.getBusinessId());
        connection.setMarketplaceSupplierId(marketplaceSupplierId);
        connection.setLocalSupplierId(local.getId());
        connection.setStatus(BusinessSupplierConnectionStatuses.ACTIVE);
        connection.setCanViewPurchaseHistory(true);
        connectionRepository.saveAndFlush(connection);

        local.setMarketplaceSupplierId(marketplaceSupplierId);
        backfillPayoutPhone(local, matchPhone);
        supplierRepository.save(local);
        try {
            identityIndexService.upsertTenantSupplier(
                    local,
                    matchPhone != null ? matchPhone : local.getPayoutPhone(),
                    null);
        } catch (RuntimeException ex) {
            log.warn("identity upsert after heal skipped for {}: {}", local.getId(), ex.getMessage());
        }
        return true;
    }

    private void backfillPayoutPhone(Supplier local, String matchPhone) {
        if (matchPhone == null || matchPhone.isBlank()) {
            return;
        }
        if (local.getPayoutPhone() != null && !local.getPayoutPhone().isBlank()) {
            return;
        }
        String normalized = StkPhoneNormalizer.normalize(matchPhone);
        local.setPayoutPhone(normalized != null ? normalized : matchPhone.trim());
        supplierRepository.save(local);
    }

    private int importCatalogueFromLocal(String marketplaceSupplierId, String localSupplierId) {
        int imported = 0;
        List<SupplierProduct> links = supplierProductRepository.listActivePublicForSupplier(localSupplierId);
        for (SupplierProduct link : links) {
            try {
                if (importOneProduct(marketplaceSupplierId, link)) {
                    imported += 1;
                }
            } catch (RuntimeException ex) {
                log.warn(
                        "catalogue import skipped link={} msg={}",
                        link.getId(),
                        ex.getMessage());
            }
        }
        return imported;
    }

    private boolean importOneProduct(String marketplaceSupplierId, SupplierProduct link) {
        Item item = itemRepository.findById(link.getItemId()).orElse(null);
        if (item == null || item.getDeletedAt() != null || !item.isActive()) {
            return false;
        }
        String barcode = blankToNull(item.getBarcode());
        if (barcode != null
                && productRepository.existsByMarketplaceSupplierIdAndBarcodeIgnoreCase(
                        marketplaceSupplierId, barcode)) {
            return false;
        }
        if (barcode == null
                && productRepository.existsByMarketplaceSupplierIdAndNameIgnoreCase(
                        marketplaceSupplierId, item.getName())) {
            return false;
        }

        MarketplaceSupplierProduct product = new MarketplaceSupplierProduct();
        product.setMarketplaceSupplierId(marketplaceSupplierId);
        product.setName(item.getName().trim());
        product.setBarcode(barcode);
        product.setSku(blankToNull(link.getSupplierSku() != null ? link.getSupplierSku() : item.getSku()));
        product.setDescription(blankToNull(item.getDescription()));
        product.setPackSize(link.getPackSize());
        product.setPackUnit(blankToNull(link.getPackUnit()));
        product.setMinOrderQty(link.getMinOrderQty());
        product.setStatus(MarketplaceSupplierProductStatuses.ACTIVE);
        productRepository.save(product);

        BigDecimal unitPrice = link.getDefaultCostPrice() != null
                ? link.getDefaultCostPrice()
                : link.getLastCostPrice();
        if (unitPrice == null) {
            unitPrice = BigDecimal.ZERO;
        }
        MarketplaceSupplierPriceOffer offer = new MarketplaceSupplierPriceOffer();
        offer.setMarketplaceSupplierId(marketplaceSupplierId);
        offer.setProductId(product.getId());
        offer.setPackageSize(link.getPackSize() != null ? link.getPackSize() : BigDecimal.ONE);
        offer.setPackageUnit(blankToNull(link.getPackUnit()) != null ? link.getPackUnit().trim() : "each");
        offer.setMinQty(BigDecimal.ONE);
        offer.setUnitPrice(unitPrice);
        offer.setCurrency("KES");
        offer.setAvailable(true);
        offer.setEffectiveFrom(Instant.now());
        priceOfferRepository.save(offer);
        return true;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
