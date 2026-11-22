package zelisline.ub.marketplace.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.billing.application.SubscriptionPlanLimitGuard;
import zelisline.ub.catalog.api.dto.CreateItemRequest;
import zelisline.ub.catalog.api.dto.CreateVariantRequest;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.domain.ItemType;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.marketplace.api.dto.SupplierShopImportResponse;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.domain.MarketplaceSupplierPriceOffer;
import zelisline.ub.marketplace.domain.MarketplaceSupplierProduct;
import zelisline.ub.marketplace.domain.MarketplaceSupplierProductStatuses;
import zelisline.ub.marketplace.repository.MarketplaceSupplierPriceOfferRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierProductRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.pricing.api.dto.PostSellingPriceRequest;
import zelisline.ub.pricing.application.PricingService;

/**
 * Copies a supplier's price list (its passport products) into the supplier's own shop as ordinary
 * shop products (ADR 0038, P1-4). Each passport product then points at its shop product, so a
 * second run creates nothing twice.
 *
 * <p>A pack price (a carton of 12) becomes a pack product under the single one: a package variant
 * with its own selling price that draws stock from the single product, which is how every shop
 * already sells by the tray or crate at the till and online. It is deliberately not an
 * {@code item_pack_options} row: those describe how the shop buys, and their price is a buy price.
 *
 * <p>Only the supplier's own passport and its own shop are read or written. Each product is copied
 * in its own transaction: one that cannot be created (a clash, a bad value) is skipped and the rest
 * still go in. The shop is on the same plans as any shop, so the copy stops when the plan has no
 * room for another product.
 */
@Service
public class SupplierShopCatalogService {

    private static final Logger log = LoggerFactory.getLogger(SupplierShopCatalogService.class);
    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final MarketplaceSupplierProductRepository productRepository;
    private final MarketplaceSupplierPriceOfferRepository priceOfferRepository;
    private final ItemTypeRepository itemTypeRepository;
    private final ItemCatalogService itemCatalogService;
    private final PricingService pricingService;
    private final SubscriptionPlanLimitGuard planLimitGuard;
    private final TransactionTemplate perProduct;

    public SupplierShopCatalogService(
            MarketplaceSupplierRepository marketplaceSupplierRepository,
            MarketplaceSupplierProductRepository productRepository,
            MarketplaceSupplierPriceOfferRepository priceOfferRepository,
            ItemTypeRepository itemTypeRepository,
            ItemCatalogService itemCatalogService,
            PricingService pricingService,
            SubscriptionPlanLimitGuard planLimitGuard,
            PlatformTransactionManager transactionManager
    ) {
        this.marketplaceSupplierRepository = marketplaceSupplierRepository;
        this.productRepository = productRepository;
        this.priceOfferRepository = priceOfferRepository;
        this.itemTypeRepository = itemTypeRepository;
        this.itemCatalogService = itemCatalogService;
        this.pricingService = pricingService;
        this.planLimitGuard = planLimitGuard;
        this.perProduct = new TransactionTemplate(transactionManager);
        this.perProduct.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Not transactional itself: every product commits on its own. */
    public SupplierShopImportResponse copyPriceListIntoShop(String marketplaceSupplierId) {
        MarketplaceSupplier passport = marketplaceSupplierRepository.findById(marketplaceSupplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
        String businessId = passport.getBusinessId();
        if (businessId == null || businessId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Open your wholesale shop first");
        }
        String itemTypeId = goodsItemTypeId(businessId);

        int copied = 0;
        int already = 0;
        int failed = 0;
        String planMessage = null;

        List<MarketplaceSupplierProduct> shelf = new ArrayList<>(productRepository
                .findByMarketplaceSupplierIdAndStatus(marketplaceSupplierId, MarketplaceSupplierProductStatuses.ACTIVE));
        shelf.sort(Comparator.comparing(
                (MarketplaceSupplierProduct p) -> p.getName() == null ? "" : p.getName().toLowerCase()));

        for (MarketplaceSupplierProduct product : shelf) {
            if (product.getItemId() != null && !product.getItemId().isBlank()) {
                already++;
                continue;
            }
            try {
                planLimitGuard.assertCanAddProduct(businessId);
            } catch (ResponseStatusException full) {
                planMessage = full.getReason();
                break;
            }
            String productId = product.getId();
            try {
                perProduct.executeWithoutResult(status -> copyOne(businessId, itemTypeId, productId));
                copied++;
            } catch (RuntimeException ex) {
                failed++;
                log.warn("supplier shop copy skipped product={} shop={}: {}", productId, businessId, ex.getMessage());
            }
        }
        return new SupplierShopImportResponse(copied, already, failed, planMessage != null, planMessage);
    }

    private void copyOne(String businessId, String itemTypeId, String productId) {
        MarketplaceSupplierProduct product = productRepository.findById(productId).orElseThrow();
        if (product.getItemId() != null && !product.getItemId().isBlank()) {
            return;
        }
        String itemId = itemCatalogService
                .createItem(businessId, toItem(product, itemTypeId), null)
                .body()
                .id();

        List<MarketplaceSupplierPriceOffer> offers =
                priceOfferRepository.findCurrentOffers(product.getId(), Instant.now());
        List<String> packNames = new ArrayList<>();
        boolean singlePriceSet = false;
        for (MarketplaceSupplierPriceOffer offer : offers) {
            BigDecimal price = offer.getUnitPrice();
            if (price == null || price.signum() <= 0) {
                continue;
            }
            BigDecimal size = offer.getPackageSize();
            boolean isPack = size != null && size.compareTo(BigDecimal.ONE) > 0;
            if (isPack) {
                String unit = offer.getPackageUnit() == null || offer.getPackageUnit().isBlank()
                        ? "pack"
                        : offer.getPackageUnit().trim();
                String packName = packName(unit, size);
                if (packNames.contains(packName)) {
                    // Same pack priced twice (by region or quantity); the first current offer wins.
                    continue;
                }
                packNames.add(packName);
                String packItemId = itemCatalogService
                        .createVariant(businessId, itemId, toPack(packName, unit, size))
                        .id();
                setPrice(businessId, packItemId, price);
            } else if (!singlePriceSet) {
                setPrice(businessId, itemId, price);
                singlePriceSet = true;
            }
        }

        product.setItemId(itemId);
        productRepository.save(product);
    }

    private void setPrice(String businessId, String itemId, BigDecimal price) {
        pricingService.setSellingPrice(
                businessId,
                new PostSellingPriceRequest(
                        itemId, null, price, LocalDate.now(NAIROBI), "From the supplier price list"),
                null);
    }

    /** "Carton of 12". */
    static String packName(String unit, BigDecimal size) {
        String word = unit.substring(0, 1).toUpperCase() + unit.substring(1);
        return word + " of " + size.stripTrailingZeros().toPlainString();
    }

    private static CreateVariantRequest toPack(String packName, String unit, BigDecimal size) {
        return new CreateVariantRequest(
                null,
                packName,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Boolean.TRUE,
                null,
                Boolean.TRUE,
                unit,
                size,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static CreateItemRequest toItem(MarketplaceSupplierProduct product, String itemTypeId) {
        return new CreateItemRequest(
                null,
                blankToNull(product.getBarcode()),
                product.getName().trim(),
                blankToNull(product.getDescription()),
                itemTypeId,
                null,
                null,
                null,
                null,
                Boolean.TRUE,
                Boolean.TRUE,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                blankToNull(product.getImageUrl()),
                null,
                null,
                null);
    }

    private String goodsItemTypeId(String businessId) {
        List<ItemType> types = itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(businessId);
        return types.stream()
                .filter(t -> "goods".equals(t.getTypeKey()))
                .findFirst()
                .or(() -> types.stream().findFirst())
                .map(ItemType::getId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "The shop has no product types set up yet"));
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
