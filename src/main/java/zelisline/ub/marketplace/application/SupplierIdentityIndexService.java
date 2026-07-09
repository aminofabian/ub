package zelisline.ub.marketplace.application;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.PublicMarketplaceSupplierSearchRow;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.domain.MarketplaceSupplierStatuses;
import zelisline.ub.marketplace.domain.SupplierIdentityIndex;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.marketplace.repository.SupplierIdentityIndexRepository;
import zelisline.ub.suppliers.domain.Supplier;

@Service
@RequiredArgsConstructor
public class SupplierIdentityIndexService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final SupplierIdentityIndexRepository supplierIdentityIndexRepository;

    @Transactional
    public void upsertTenantSupplier(Supplier supplier, String phone, String email) {
        SupplierIdentityIndex row = supplierIdentityIndexRepository.findBySupplierId(supplier.getId())
                .orElseGet(SupplierIdentityIndex::new);
        row.setSource("tenant");
        row.setBusinessId(supplier.getBusinessId());
        row.setSupplierId(supplier.getId());
        row.setMarketplaceSupplierId(supplier.getMarketplaceSupplierId());
        row.setNameNormalized(SupplierIdentityNormalizer.normalizeName(supplier.getName()));
        row.setPhoneNormalized(SupplierIdentityNormalizer.normalizePhone(
                phone != null ? phone : supplier.getPayoutPhone()));
        row.setEmailNormalized(SupplierIdentityNormalizer.normalizeEmail(email));
        row.setTaxIdNormalized(SupplierIdentityNormalizer.normalizeTaxId(supplier.getVatPin()));
        supplierIdentityIndexRepository.save(row);
    }

    @Transactional
    public void upsertMarketplaceSupplier(MarketplaceSupplier supplier) {
        SupplierIdentityIndex row = supplierIdentityIndexRepository
                .findByMarketplaceSupplierIdAndSupplierIdIsNull(supplier.getId())
                .orElseGet(SupplierIdentityIndex::new);
        row.setSource("marketplace");
        row.setBusinessId(null);
        row.setSupplierId(null);
        row.setMarketplaceSupplierId(supplier.getId());
        row.setNameNormalized(SupplierIdentityNormalizer.normalizeName(supplier.getName()));
        row.setPhoneNormalized(SupplierIdentityNormalizer.normalizePhone(supplier.getContactPhone()));
        row.setEmailNormalized(SupplierIdentityNormalizer.normalizeEmail(supplier.getContactEmail()));
        row.setTaxIdNormalized(null);
        supplierIdentityIndexRepository.save(row);
    }
}
