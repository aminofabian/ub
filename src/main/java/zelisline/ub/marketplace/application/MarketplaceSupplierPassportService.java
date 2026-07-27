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

    @Transactional
    public MarketplaceSupplier createDraftPassport(
            String name,
            String contactPhone,
            String contactEmail,
            String taxPin
    ) {
        MarketplaceSupplier marketplace = new MarketplaceSupplier();
        marketplace.setName(name.trim());
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
