package zelisline.ub.marketplace.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.marketplace.api.dto.MarketplaceConnectResponse;
import zelisline.ub.marketplace.api.dto.MarketplaceSupplierDetailResponse;
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
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierProduct;
import zelisline.ub.suppliers.repository.SupplierProductRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;

@Service
@RequiredArgsConstructor
public class MarketplaceConnectService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final MarketplaceSupplierProductRepository productRepository;
    private final MarketplaceSupplierPriceOfferRepository priceOfferRepository;
    private final BusinessSupplierConnectionRepository connectionRepository;
    private final SupplierRepository supplierRepository;
    private final SupplierProductRepository supplierProductRepository;
    private final ItemRepository itemRepository;
    private final SupplierIdentityIndexService supplierIdentityIndexService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public MarketplaceSupplierDetailResponse getSupplierDetail(String marketplaceSupplierId) {
        MarketplaceSupplier supplier = requireActiveSupplier(marketplaceSupplierId);
        List<MarketplaceSupplierProduct> products = productRepository.findByMarketplaceSupplierIdAndStatus(
                marketplaceSupplierId, MarketplaceSupplierProductStatuses.ACTIVE);
        List<MarketplaceSupplierDetailResponse.MarketplaceCatalogProductPreview> previews = new ArrayList<>();
        for (MarketplaceSupplierProduct product : products) {
            MarketplaceSupplierPriceOffer offer = primaryOffer(product.getId());
            previews.add(new MarketplaceSupplierDetailResponse.MarketplaceCatalogProductPreview(
                    product.getId(),
                    product.getName(),
                    product.getBarcode(),
                    product.getSku(),
                    product.getCategoryName(),
                    product.getPackSize(),
                    product.getPackUnit(),
                    product.getMinOrderQty(),
                    offer == null ? null : offer.getUnitPrice(),
                    offer == null ? null : offer.getCurrency(),
                    offer != null && offer.isAvailable()));
        }
        return new MarketplaceSupplierDetailResponse(
                supplier.getId(),
                supplier.getName(),
                supplier.getDescription(),
                supplier.getContactEmail(),
                supplier.getContactPhone(),
                supplier.getStatus(),
                readJsonList(supplier.getDeliveryRegionsJson()),
                readJsonList(supplier.getCategoryTagsJson()),
                previews);
    }

    @Transactional
    public MarketplaceConnectResponse connect(String businessId, String marketplaceSupplierId) {
        MarketplaceSupplier marketplace = requireActiveSupplier(marketplaceSupplierId);
        if (connectionRepository.existsByBusinessIdAndMarketplaceSupplierId(businessId, marketplaceSupplierId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Supplier is already connected to this business");
        }
        if (supplierRepository.existsDuplicateName(businessId, marketplace.getName(), null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A local supplier with this name already exists; open it or rename before connecting.");
        }

        Supplier local = new Supplier();
        local.setBusinessId(businessId);
        local.setName(marketplace.getName());
        local.setSupplierType("distributor");
        local.setStatus("active");
        local.setMarketplaceSupplierId(marketplace.getId());
        local.setNotes("Connected from marketplace supplier " + marketplace.getId());
        if (marketplace.getContactPhone() != null) {
            local.setPayoutPhone(marketplace.getContactPhone());
        }
        supplierRepository.save(local);
        supplierIdentityIndexService.upsertTenantSupplier(local, marketplace.getContactPhone(), marketplace.getContactEmail());

        BusinessSupplierConnection connection = new BusinessSupplierConnection();
        connection.setBusinessId(businessId);
        connection.setMarketplaceSupplierId(marketplace.getId());
        connection.setLocalSupplierId(local.getId());
        connection.setStatus(BusinessSupplierConnectionStatuses.ACTIVE);
        connectionRepository.save(connection);

        int imported = importCatalogueLinks(businessId, local.getId(), marketplace.getId());

        return new MarketplaceConnectResponse(
                connection.getId(),
                local.getId(),
                marketplace.getId(),
                local.getName(),
                imported,
                connection.getStatus());
    }

    private int importCatalogueLinks(String businessId, String localSupplierId, String marketplaceSupplierId) {
        List<MarketplaceSupplierProduct> products = productRepository.findByMarketplaceSupplierIdAndStatus(
                marketplaceSupplierId, MarketplaceSupplierProductStatuses.ACTIVE);
        int imported = 0;
        for (MarketplaceSupplierProduct product : products) {
            if (product.getBarcode() == null || product.getBarcode().isBlank()) {
                continue;
            }
            var item = itemRepository.findByBusinessIdAndBarcodeAndDeletedAtIsNull(
                    businessId, product.getBarcode().trim());
            if (item.isEmpty()) {
                continue;
            }
            String itemId = item.get().getId();
            SupplierProduct link = supplierProductRepository.findBySupplierIdAndItemId(localSupplierId, itemId)
                    .orElseGet(SupplierProduct::new);
            if (link.getId() != null && link.getDeletedAt() == null) {
                continue;
            }
            link.setSupplierId(localSupplierId);
            link.setItemId(itemId);
            link.setSupplierSku(product.getSku());
            link.setPackSize(product.getPackSize());
            link.setPackUnit(product.getPackUnit());
            link.setMinOrderQty(product.getMinOrderQty());
            link.setActive(true);
            link.setDeletedAt(null);
            MarketplaceSupplierPriceOffer offer = primaryOffer(product.getId());
            if (offer != null) {
                link.setDefaultCostPrice(offer.getUnitPrice());
            }
            supplierProductRepository.save(link);
            imported++;
        }
        return imported;
    }

    private MarketplaceSupplierPriceOffer primaryOffer(String productId) {
        List<MarketplaceSupplierPriceOffer> offers =
                priceOfferRepository.findCurrentOffers(productId, Instant.now());
        return offers.isEmpty() ? null : offers.get(0);
    }

    private MarketplaceSupplier requireActiveSupplier(String marketplaceSupplierId) {
        MarketplaceSupplier supplier = marketplaceSupplierRepository.findById(marketplaceSupplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
        if (!MarketplaceSupplierStatuses.ACTIVE.equals(supplier.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier is not available");
        }
        return supplier;
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
