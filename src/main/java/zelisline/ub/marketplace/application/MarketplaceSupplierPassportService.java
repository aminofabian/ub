package zelisline.ub.marketplace.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.domain.MarketplaceSupplierStatuses;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;

/**
 * Ensures every marketplace passport has a sequential S-number and is indexed.
 */
@Service
@RequiredArgsConstructor
public class MarketplaceSupplierPassportService {

    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final SupplierNumberAllocator supplierNumberAllocator;
    private final SupplierIdentityIndexService identityIndexService;

    @Transactional
    public MarketplaceSupplier ensureNumberAndIndex(MarketplaceSupplier supplier) {
        if (supplier.getSupplierNumber() == null || supplier.getSupplierNumber().isBlank()) {
            supplier.setSupplierNumber(supplierNumberAllocator.allocateNext());
        }
        marketplaceSupplierRepository.save(supplier);
        identityIndexService.upsertMarketplaceSupplier(supplier);
        return supplier;
    }

    /**
     * If the passport still has a phone-derived placeholder name and {@code betterName}
     * is a real shop name, upgrade the passport (and re-index).
     */
    @Transactional
    public MarketplaceSupplier upgradeNameIfPlaceholder(MarketplaceSupplier supplier, String betterName) {
        if (supplier == null) {
            return null;
        }
        if (betterName != null
                && !betterName.isBlank()
                && !MarketplaceSupplierNaming.isPlaceholderName(betterName)
                && MarketplaceSupplierNaming.isPlaceholderName(supplier.getName())) {
            supplier.setName(betterName.trim());
        }
        return ensureNumberAndIndex(supplier);
    }

    @Transactional
    public MarketplaceSupplier createDraftPassport(
            String name,
            String contactPhone,
            String contactEmail,
            String taxPin
    ) {
        String resolved = MarketplaceSupplierNaming.preferDisplayName(
                name,
                MarketplaceSupplierNaming.placeholderFromPhone(contactPhone));
        MarketplaceSupplier marketplace = new MarketplaceSupplier();
        marketplace.setName(resolved);
        marketplace.setContactPhone(SupplierIdentityNormalizer.normalizePhone(contactPhone));
        marketplace.setContactEmail(SupplierIdentityNormalizer.normalizeEmail(contactEmail));
        marketplace.setTaxPin(SupplierIdentityNormalizer.normalizeTaxId(taxPin));
        marketplace.setStatus(MarketplaceSupplierStatuses.DRAFT);
        marketplace.setSupplierNumber(supplierNumberAllocator.allocateNext());
        marketplaceSupplierRepository.save(marketplace);
        identityIndexService.upsertMarketplaceSupplier(marketplace);
        return marketplace;
    }
}
