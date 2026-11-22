package zelisline.ub.marketplace.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemPackOptionRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.marketplace.api.dto.OpenSupplierShopRequest;
import zelisline.ub.marketplace.api.dto.SupplierShopImportResponse;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.domain.MarketplaceSupplierPriceOffer;
import zelisline.ub.marketplace.domain.MarketplaceSupplierProduct;
import zelisline.ub.marketplace.domain.MarketplaceSupplierStatuses;
import zelisline.ub.marketplace.repository.MarketplaceSupplierPriceOfferRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierProductRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.pricing.application.PricingService;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/** ADR 0038 P1-4: a supplier's price list is copied into its own shop, once. */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.tenancy.slug-domain-suffix=kiosk.test"
})
class SupplierShopCatalogIT {

    @Autowired
    private SupplierShopService supplierShopService;

    @Autowired
    private SupplierShopCatalogService supplierShopCatalogService;

    @Autowired
    private MarketplaceSupplierRepository marketplaceSupplierRepository;

    @Autowired
    private MarketplaceSupplierProductRepository productRepository;

    @Autowired
    private MarketplaceSupplierPriceOfferRepository priceOfferRepository;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private ItemPackOptionRepository itemPackOptionRepository;

    @Autowired
    private PricingService pricingService;

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private DomainMappingRepository domainMappingRepository;

    private String passportId;
    private String otherPassportId;
    private String shopId;

    @BeforeEach
    void seed() {
        priceOfferRepository.deleteAll();
        productRepository.deleteAll();
        itemPackOptionRepository.deleteAll();
        itemRepository.deleteAll();
        domainMappingRepository.deleteAll();
        marketplaceSupplierRepository.deleteAll();
        businessRepository.deleteAll();

        passportId = savePassport("S-900501", "Copy Wholesalers", "copyshop");
        otherPassportId = savePassport("S-900502", "Unrelated Traders", "unrelated");
        shopId = supplierShopService.open(passportId, new OpenSupplierShopRequest(null)).businessId();

        String soda = saveProduct(passportId, "Soda 500ml", "6161100000017", "https://img.example/soda.jpg");
        saveOffer(passportId, soda, BigDecimal.ONE, "each", new BigDecimal("100.00"));
        saveOffer(passportId, soda, new BigDecimal("12"), "carton", new BigDecimal("1100.00"));
        saveProduct(passportId, "Bread 400g", "6161100000024", null);

        String theirs = saveProduct(otherPassportId, "Somebody Else's Rice", "6161100000031", null);
        saveOffer(otherPassportId, theirs, BigDecimal.ONE, "each", new BigDecimal("250.00"));
    }

    @Test
    void thePriceListIsCopiedWithPricesPacksAndPictures() {
        SupplierShopImportResponse result = supplierShopCatalogService.copyPriceListIntoShop(passportId);

        assertThat(result.copied()).isEqualTo(2);
        assertThat(result.failed()).isZero();
        assertThat(result.stoppedByPlanLimit()).isFalse();

        Item soda = itemRepository.findByBusinessIdAndBarcodeAndDeletedAtIsNull(shopId, "6161100000017")
                .orElseThrow();
        assertThat(soda.getName()).isEqualTo("Soda 500ml");
        assertThat(soda.getImageKey()).isEqualTo("https://img.example/soda.jpg");
        assertThat(pricingService.getCurrentOpenSellingPrice(shopId, soda.getId(), null))
                .isEqualByComparingTo("100.00");
        // The carton is a pack product under the single one: sold at its own price, stock from the single.
        List<Item> packs = itemRepository
                .findByBusinessIdAndVariantOfItemIdAndDeletedAtIsNullOrderBySkuAsc(shopId, soda.getId());
        assertThat(packs).hasSize(1);
        Item carton = packs.get(0);
        assertThat(carton.getVariantName()).isEqualTo("Carton of 12");
        assertThat(carton.isPackageVariant()).isTrue();
        assertThat(carton.isStocked()).isFalse();
        assertThat(carton.getPackagingUnitQty()).isEqualByComparingTo("12");
        assertThat(pricingService.getCurrentOpenSellingPrice(shopId, carton.getId(), null))
                .isEqualByComparingTo("1100.00");
        // A selling price is never written where the shop keeps what it pays its own suppliers.
        assertThat(itemPackOptionRepository.count()).isZero();

        assertThat(itemRepository.findByBusinessIdAndBarcodeAndDeletedAtIsNull(shopId, "6161100000024")).isPresent();
        // Each passport product now points at its shop product.
        assertThat(productRepository.findAll().stream()
                .filter(p -> passportId.equals(p.getMarketplaceSupplierId()))
                .allMatch(p -> p.getItemId() != null)).isTrue();
    }

    @Test
    void copyingAgainCreatesNothingTwice() {
        supplierShopCatalogService.copyPriceListIntoShop(passportId);
        long itemsAfterFirst = itemRepository.count();

        SupplierShopImportResponse second = supplierShopCatalogService.copyPriceListIntoShop(passportId);

        assertThat(second.copied()).isZero();
        assertThat(second.alreadyInShop()).isEqualTo(2);
        assertThat(itemRepository.count()).isEqualTo(itemsAfterFirst);
    }

    @Test
    void anotherSuppliersProductsNeverReachThisShop() {
        supplierShopCatalogService.copyPriceListIntoShop(passportId);

        assertThat(itemRepository.findByBusinessIdAndBarcodeAndDeletedAtIsNull(shopId, "6161100000031")).isEmpty();
        assertThat(productRepository.findAll().stream()
                .filter(p -> otherPassportId.equals(p.getMarketplaceSupplierId()))
                .allMatch(p -> p.getItemId() == null)).isTrue();
    }

    @Test
    void aSupplierWithNoShopIsToldToOpenOne() {
        assertThatThrownBy(() -> supplierShopCatalogService.copyPriceListIntoShop(otherPassportId))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(itemRepository.count()).isZero();
    }

    private String savePassport(String number, String name, String username) {
        MarketplaceSupplier passport = new MarketplaceSupplier();
        passport.setSupplierNumber(number);
        passport.setName(name);
        passport.setUsername(username);
        passport.setStatus(MarketplaceSupplierStatuses.ACTIVE);
        return marketplaceSupplierRepository.save(passport).getId();
    }

    private String saveProduct(String passport, String name, String barcode, String imageUrl) {
        MarketplaceSupplierProduct product = new MarketplaceSupplierProduct();
        product.setMarketplaceSupplierId(passport);
        product.setName(name);
        product.setBarcode(barcode);
        product.setImageUrl(imageUrl);
        return productRepository.save(product).getId();
    }

    private void saveOffer(String passport, String productId, BigDecimal size, String unit, BigDecimal price) {
        MarketplaceSupplierPriceOffer offer = new MarketplaceSupplierPriceOffer();
        offer.setMarketplaceSupplierId(passport);
        offer.setProductId(productId);
        offer.setPackageSize(size);
        offer.setPackageUnit(unit);
        offer.setUnitPrice(price);
        offer.setPriceConfirmed(true);
        offer.setEffectiveFrom(Instant.now().minusSeconds(60));
        priceOfferRepository.save(offer);
    }
}
