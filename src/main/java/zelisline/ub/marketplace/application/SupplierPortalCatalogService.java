package zelisline.ub.marketplace.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.CreateSupplierPortalProductRequest;
import zelisline.ub.marketplace.api.dto.PatchSupplierPortalProductRequest;
import zelisline.ub.marketplace.api.dto.SupplierPortalProductResponse;
import zelisline.ub.marketplace.domain.MarketplaceSupplierPriceOffer;
import zelisline.ub.marketplace.domain.MarketplaceSupplierProduct;
import zelisline.ub.marketplace.domain.MarketplaceSupplierProductStatuses;
import zelisline.ub.marketplace.repository.MarketplaceSupplierPriceOfferRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierProductRepository;

@Service
@RequiredArgsConstructor
public class SupplierPortalCatalogService {

    private final MarketplaceSupplierProductRepository productRepository;
    private final MarketplaceSupplierPriceOfferRepository priceOfferRepository;

    @Transactional(readOnly = true)
    public Page<SupplierPortalProductResponse> listProducts(
            String marketplaceSupplierId,
            String q,
            String status,
            Pageable pageable
    ) {
        return productRepository.searchForSupplier(
                        marketplaceSupplierId,
                        blankToNull(q),
                        blankToNull(status),
                        pageable)
                .map(this::toResponse);
    }

    @Transactional
    public SupplierPortalProductResponse createProduct(
            String marketplaceSupplierId,
            CreateSupplierPortalProductRequest request
    ) {
        MarketplaceSupplierProduct product = new MarketplaceSupplierProduct();
        product.setMarketplaceSupplierId(marketplaceSupplierId);
        applyProductFields(product, request.name(), request.barcode(), request.sku(),
                request.categoryName(), request.description(), request.packSize(),
                request.packUnit(), request.minOrderQty(), MarketplaceSupplierProductStatuses.ACTIVE);
        productRepository.save(product);

        MarketplaceSupplierPriceOffer offer = new MarketplaceSupplierPriceOffer();
        offer.setMarketplaceSupplierId(marketplaceSupplierId);
        offer.setProductId(product.getId());
        offer.setPackageSize(defaultPackSize(request.packSize()));
        offer.setPackageUnit(defaultPackUnit(request.packUnit()));
        offer.setMinQty(BigDecimal.ONE);
        offer.setUnitPrice(request.unitPrice());
        offer.setCurrency(defaultCurrency(request.currency()));
        offer.setAvailable(request.available() == null || request.available());
        priceOfferRepository.save(offer);

        return toResponse(product);
    }

    @Transactional
    public SupplierPortalProductResponse updateProduct(
            String marketplaceSupplierId,
            String productId,
            PatchSupplierPortalProductRequest request
    ) {
        MarketplaceSupplierProduct product = requireProduct(marketplaceSupplierId, productId);
        if (request.name() != null) {
            if (request.name().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name cannot be empty");
            }
            product.setName(request.name().trim());
        }
        if (request.barcode() != null) {
            product.setBarcode(blankToNull(request.barcode()));
        }
        if (request.sku() != null) {
            product.setSku(blankToNull(request.sku()));
        }
        if (request.categoryName() != null) {
            product.setCategoryName(blankToNull(request.categoryName()));
        }
        if (request.description() != null) {
            product.setDescription(blankToNull(request.description()));
        }
        if (request.packSize() != null) {
            product.setPackSize(request.packSize());
        }
        if (request.packUnit() != null) {
            product.setPackUnit(blankToNull(request.packUnit()));
        }
        if (request.minOrderQty() != null) {
            product.setMinOrderQty(request.minOrderQty());
        }
        if (request.status() != null) {
            product.setStatus(request.status().trim());
        }
        productRepository.save(product);

        if (request.unitPrice() != null || request.currency() != null || request.available() != null
                || request.packSize() != null || request.packUnit() != null) {
            MarketplaceSupplierPriceOffer offer = currentPrimaryOffer(product.getId())
                    .orElseGet(() -> {
                        MarketplaceSupplierPriceOffer created = new MarketplaceSupplierPriceOffer();
                        created.setMarketplaceSupplierId(marketplaceSupplierId);
                        created.setProductId(product.getId());
                        created.setPackageSize(defaultPackSize(product.getPackSize()));
                        created.setPackageUnit(defaultPackUnit(product.getPackUnit()));
                        created.setMinQty(BigDecimal.ONE);
                        created.setEffectiveFrom(Instant.now());
                        return created;
                    });
            if (request.packSize() != null) {
                offer.setPackageSize(request.packSize());
            }
            if (request.packUnit() != null) {
                offer.setPackageUnit(defaultPackUnit(request.packUnit()));
            }
            if (request.unitPrice() != null) {
                offer.setUnitPrice(request.unitPrice());
            }
            if (request.currency() != null) {
                offer.setCurrency(defaultCurrency(request.currency()));
            }
            if (request.available() != null) {
                offer.setAvailable(request.available());
            }
            priceOfferRepository.save(offer);
        }
        return toResponse(product);
    }

    @Transactional
    public void deleteProduct(String marketplaceSupplierId, String productId) {
        MarketplaceSupplierProduct product = requireProduct(marketplaceSupplierId, productId);
        product.setStatus(MarketplaceSupplierProductStatuses.INACTIVE);
        productRepository.save(product);
        for (MarketplaceSupplierPriceOffer offer : priceOfferRepository.findByProductId(product.getId())) {
            offer.setAvailable(false);
            priceOfferRepository.save(offer);
        }
    }

    private MarketplaceSupplierProduct requireProduct(String marketplaceSupplierId, String productId) {
        return productRepository.findByIdAndMarketplaceSupplierId(productId, marketplaceSupplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
    }

    private java.util.Optional<MarketplaceSupplierPriceOffer> currentPrimaryOffer(String productId) {
        List<MarketplaceSupplierPriceOffer> offers =
                priceOfferRepository.findCurrentOffers(productId, Instant.now());
        return offers.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(offers.get(0));
    }

    private SupplierPortalProductResponse toResponse(MarketplaceSupplierProduct product) {
        MarketplaceSupplierPriceOffer offer = currentPrimaryOffer(product.getId()).orElse(null);
        return new SupplierPortalProductResponse(
                product.getId(),
                product.getName(),
                product.getBarcode(),
                product.getSku(),
                product.getCategoryName(),
                product.getDescription(),
                product.getPackSize(),
                product.getPackUnit(),
                product.getMinOrderQty(),
                offer == null ? null : offer.getUnitPrice(),
                offer == null ? null : offer.getCurrency(),
                offer != null && offer.isAvailable(),
                product.getStatus(),
                product.getVersion(),
                product.getCreatedAt(),
                product.getUpdatedAt());
    }

    private static void applyProductFields(
            MarketplaceSupplierProduct product,
            String name,
            String barcode,
            String sku,
            String categoryName,
            String description,
            BigDecimal packSize,
            String packUnit,
            BigDecimal minOrderQty,
            String status
    ) {
        product.setName(name.trim());
        product.setBarcode(blankToNull(barcode));
        product.setSku(blankToNull(sku));
        product.setCategoryName(blankToNull(categoryName));
        product.setDescription(blankToNull(description));
        product.setPackSize(packSize);
        product.setPackUnit(blankToNull(packUnit));
        product.setMinOrderQty(minOrderQty);
        product.setStatus(status);
    }

    private static BigDecimal defaultPackSize(BigDecimal packSize) {
        return packSize == null ? BigDecimal.ONE : packSize;
    }

    private static String defaultPackUnit(String packUnit) {
        String unit = blankToNull(packUnit);
        return unit == null ? "each" : unit;
    }

    private static String defaultCurrency(String currency) {
        String c = blankToNull(currency);
        return c == null ? "KES" : c.toUpperCase();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
