package zelisline.ub.marketplace.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.marketplace.api.dto.MarketplaceSupplierDetailResponse;
import zelisline.ub.marketplace.api.dto.PublicMarketplaceProductSearchRow;
import zelisline.ub.marketplace.api.dto.PublicMarketplaceSupplierSearchRow;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierContact;
import zelisline.ub.suppliers.domain.SupplierProduct;
import zelisline.ub.suppliers.repository.SupplierContactRepository;
import zelisline.ub.suppliers.repository.SupplierProductRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Public directory over tenant-owned suppliers and their linked catalogue items.
 * Only active, non-deleted suppliers that have at least one active product link are listed.
 */
@Service
@RequiredArgsConstructor
public class PublicMarketplaceSearchService {

    private final SupplierRepository supplierRepository;
    private final SupplierProductRepository supplierProductRepository;
    private final SupplierContactRepository supplierContactRepository;
    private final ItemRepository itemRepository;
    private final BusinessRepository businessRepository;

    @Transactional(readOnly = true)
    public Page<PublicMarketplaceSupplierSearchRow> searchSuppliers(String q, Pageable pageable) {
        String query = blankToNull(q);
        return supplierRepository.searchPublicDirectory(query, pageable).map(this::toSupplierRow);
    }

    @Transactional(readOnly = true)
    public Page<PublicMarketplaceProductSearchRow> searchProducts(String q, Pageable pageable) {
        String query = blankToNull(q);
        return supplierProductRepository.searchPublicDirectory(query, pageable).map(this::toProductRow);
    }

    @Transactional(readOnly = true)
    public MarketplaceSupplierDetailResponse getSupplierDetail(String supplierId) {
        Supplier supplier = supplierRepository.findByIdAndDeletedAtIsNull(supplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
        if (!"active".equalsIgnoreCase(supplier.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier is not available");
        }

        List<SupplierProduct> links =
                supplierProductRepository.listActivePublicForSupplier(supplier.getId());
        List<MarketplaceSupplierDetailResponse.MarketplaceCatalogProductPreview> products = new ArrayList<>();
        for (SupplierProduct link : links) {
            Item item = itemRepository.findById(link.getItemId()).orElse(null);
            if (item == null || item.getDeletedAt() != null || !item.isActive()) {
                continue;
            }
            BigDecimal price = link.getDefaultCostPrice() != null
                    ? link.getDefaultCostPrice()
                    : link.getLastCostPrice();
            products.add(new MarketplaceSupplierDetailResponse.MarketplaceCatalogProductPreview(
                    link.getId(),
                    item.getName(),
                    item.getBarcode(),
                    link.getSupplierSku() != null && !link.getSupplierSku().isBlank()
                            ? link.getSupplierSku()
                            : item.getSku(),
                    null,
                    link.getPackSize(),
                    link.getPackUnit(),
                    link.getMinOrderQty(),
                    price,
                    price != null ? "KES" : null,
                    link.isActive()));
        }

        SupplierContact primary = supplierContactRepository
                .findBySupplierIdOrderByPrimaryContactDescNameAsc(supplier.getId())
                .stream()
                .findFirst()
                .orElse(null);

        String businessName = businessRepository.findByIdAndDeletedAtIsNull(supplier.getBusinessId())
                .map(b -> b.getName())
                .orElse(null);
        String description = supplier.getNotes();
        if ((description == null || description.isBlank()) && businessName != null) {
            description = "Supplier listed by " + businessName;
        }

        List<String> tags = new ArrayList<>();
        if (supplier.getSupplierType() != null && !supplier.getSupplierType().isBlank()) {
            tags.add(supplier.getSupplierType());
        }
        if (businessName != null && !businessName.isBlank()) {
            tags.add(businessName);
        }

        return new MarketplaceSupplierDetailResponse(
                supplier.getId(),
                supplier.getName(),
                description,
                primary != null ? primary.getEmail() : null,
                primary != null ? primary.getPhone() : null,
                supplier.getStatus(),
                List.of(),
                tags,
                products);
    }

    private PublicMarketplaceSupplierSearchRow toSupplierRow(Supplier supplier) {
        String businessName = businessRepository.findByIdAndDeletedAtIsNull(supplier.getBusinessId())
                .map(b -> b.getName())
                .orElse(null);
        String description = supplier.getNotes();
        if ((description == null || description.isBlank()) && businessName != null) {
            description = "Listed by " + businessName;
        }
        List<String> tags = new ArrayList<>();
        if (supplier.getSupplierType() != null && !supplier.getSupplierType().isBlank()) {
            tags.add(supplier.getSupplierType());
        }
        if (businessName != null && !businessName.isBlank()) {
            tags.add(businessName);
        }
        return new PublicMarketplaceSupplierSearchRow(
                supplier.getId(),
                supplier.getName(),
                description,
                List.of(),
                tags);
    }

    private PublicMarketplaceProductSearchRow toProductRow(SupplierProduct link) {
        Supplier supplier = supplierRepository.findById(link.getSupplierId()).orElse(null);
        Item item = itemRepository.findById(link.getItemId()).orElse(null);
        BigDecimal price = link.getDefaultCostPrice() != null
                ? link.getDefaultCostPrice()
                : link.getLastCostPrice();
        return new PublicMarketplaceProductSearchRow(
                link.getId(),
                item != null ? item.getName() : "Product",
                item != null ? item.getBarcode() : null,
                link.getSupplierSku() != null && !link.getSupplierSku().isBlank()
                        ? link.getSupplierSku()
                        : (item != null ? item.getSku() : null),
                null,
                link.getSupplierId(),
                supplier != null ? supplier.getName() : "Supplier",
                link.getPackSize(),
                link.getPackUnit(),
                link.getMinOrderQty(),
                price,
                price != null ? "KES" : null,
                link.isActive());
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
