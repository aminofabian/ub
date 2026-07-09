package zelisline.ub.marketplace.application;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.PublicMarketplaceProductSearchRow;
import zelisline.ub.marketplace.api.dto.PublicMarketplaceSupplierSearchRow;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.domain.MarketplaceSupplierPriceOffer;
import zelisline.ub.marketplace.domain.MarketplaceSupplierStatuses;
import zelisline.ub.marketplace.repository.MarketplaceSupplierPriceOfferRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierProductRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;

@Service
@RequiredArgsConstructor
public class PublicMarketplaceSearchService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final MarketplaceSupplierProductRepository productRepository;
    private final MarketplaceSupplierPriceOfferRepository priceOfferRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public Page<PublicMarketplaceSupplierSearchRow> searchSuppliers(String q, Pageable pageable) {
        String query = q == null || q.isBlank() ? null : q.trim();
        return marketplaceSupplierRepository.search(query, MarketplaceSupplierStatuses.ACTIVE, pageable)
                .map(s -> new PublicMarketplaceSupplierSearchRow(
                        s.getId(),
                        s.getName(),
                        s.getDescription(),
                        readJsonList(s.getDeliveryRegionsJson()),
                        readJsonList(s.getCategoryTagsJson())));
    }

    @Transactional(readOnly = true)
    public Page<PublicMarketplaceProductSearchRow> searchProducts(String q, Pageable pageable) {
        String query = q == null || q.isBlank() ? null : q.trim();
        return productRepository.searchPublic(query, pageable).map(product -> {
            MarketplaceSupplier supplier = marketplaceSupplierRepository.findById(product.getMarketplaceSupplierId())
                    .orElse(null);
            MarketplaceSupplierPriceOffer offer = primaryOffer(product.getId());
            return new PublicMarketplaceProductSearchRow(
                    product.getId(),
                    product.getName(),
                    product.getBarcode(),
                    product.getSku(),
                    product.getCategoryName(),
                    product.getMarketplaceSupplierId(),
                    supplier == null ? null : supplier.getName(),
                    product.getPackSize(),
                    product.getPackUnit(),
                    product.getMinOrderQty(),
                    offer == null ? null : offer.getUnitPrice(),
                    offer == null ? null : offer.getCurrency(),
                    offer != null && offer.isAvailable());
        });
    }

    private MarketplaceSupplierPriceOffer primaryOffer(String productId) {
        List<MarketplaceSupplierPriceOffer> offers =
                priceOfferRepository.findCurrentOffers(productId, Instant.now());
        return offers.isEmpty() ? null : offers.get(0);
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
}
