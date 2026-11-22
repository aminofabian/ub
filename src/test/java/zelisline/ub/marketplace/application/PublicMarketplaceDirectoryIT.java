package zelisline.ub.marketplace.application;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.domain.MarketplaceSupplierPriceOffer;
import zelisline.ub.marketplace.domain.MarketplaceSupplierProduct;
import zelisline.ub.marketplace.domain.MarketplaceSupplierStatuses;
import zelisline.ub.marketplace.repository.MarketplaceSupplierPriceOfferRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierProductRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * ADR 0038 P0.1: the public directory lists listed passports only. A shop's private supplier rows,
 * its costs and its supplier's contact and payout details never reach a public payload.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class PublicMarketplaceDirectoryIT {

    private static final String SHOP = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private MarketplaceSupplierRepository marketplaceSupplierRepository;

    @Autowired
    private MarketplaceSupplierProductRepository productRepository;

    @Autowired
    private MarketplaceSupplierPriceOfferRepository priceOfferRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private String listedId;
    private String unlistedId;
    private String privateShopSupplierId;
    private String taggedShopSupplierId;

    @BeforeEach
    void seed() {
        priceOfferRepository.deleteAll();
        productRepository.deleteAll();
        supplierRepository.deleteAll();
        marketplaceSupplierRepository.deleteAll();
        businessRepository.deleteAll();

        Business shop = new Business();
        shop.setId(SHOP);
        shop.setName("Test shop");
        shop.setSlug("directory-shop");
        shop.setSettings("{}");
        businessRepository.save(shop);

        listedId = savePassport("S-900101", "Zawadi Wholesalers", true);
        unlistedId = savePassport("S-900102", "Zawadi Hidden Traders", false);

        String confirmed = saveProduct(listedId, "Zawadi Rice 2kg", "https://img.example/rice.jpg");
        saveOffer(listedId, confirmed, new BigDecimal("310.00"), true);
        String copiedFromShop = saveProduct(listedId, "Zawadi Sugar 1kg", null);
        saveOffer(listedId, copiedFromShop, new BigDecimal("145.00"), false);
        String hidden = saveProduct(unlistedId, "Zawadi Flour 2kg", null);
        saveOffer(unlistedId, hidden, new BigDecimal("180.00"), true);

        privateShopSupplierId = saveShopSupplier("private", "Zawadi Private Vendor", null);
        taggedShopSupplierId = saveShopSupplier("tagged", "Zawadi Wholesalers (ours)", listedId);
    }

    @Test
    void searchListsOnlyListedPassports() throws Exception {
        mockMvc.perform(get("/api/v1/public/marketplace/suppliers/search").param("q", "Zawadi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(listedId))
                .andExpect(jsonPath("$.content[0].name").value("Zawadi Wholesalers"))
                .andExpect(jsonPath("$.content[0].listedBy").doesNotExist())
                .andExpect(jsonPath("$.content[0].contactPhone").value("254700000001"))
                .andExpect(jsonPath("$.content[0].payoutType").doesNotExist());
    }

    @Test
    void productSearchLeavesOutUnlistedPassportsAndUnconfirmedPrices() throws Exception {
        mockMvc.perform(get("/api/v1/public/marketplace/products/search").param("q", "Zawadi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].productName").value("Zawadi Rice 2kg"))
                .andExpect(jsonPath("$.content[0].unitPrice").value(310.0))
                .andExpect(jsonPath("$.content[0].imageUrl").value("https://img.example/rice.jpg"))
                .andExpect(jsonPath("$.content[0].supplierId").value(listedId))
                .andExpect(jsonPath("$.content[1].productName").value("Zawadi Sugar 1kg"))
                .andExpect(jsonPath("$.content[1].unitPrice").doesNotExist());
    }

    @Test
    void aShopsPrivateSupplierIsNotReachableById() throws Exception {
        mockMvc.perform(get("/api/v1/public/marketplace/suppliers/{id}", privateShopSupplierId))
                .andExpect(status().isNotFound());
    }

    @Test
    void anUnlistedPassportIsNotReachableById() throws Exception {
        mockMvc.perform(get("/api/v1/public/marketplace/suppliers/{id}", unlistedId))
                .andExpect(status().isNotFound());
    }

    @Test
    void aShopsTaggedSupplierIdShowsThePassportAndNothingOfTheShop() throws Exception {
        mockMvc.perform(get("/api/v1/public/marketplace/suppliers/{id}", taggedShopSupplierId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(listedId))
                .andExpect(jsonPath("$.name").value("Zawadi Wholesalers"))
                .andExpect(jsonPath("$.listedBy").doesNotExist())
                // The supplier's own contact from its passport, never the shop's record of it.
                .andExpect(jsonPath("$.contactPhone").value("254700000001"))
                .andExpect(jsonPath("$.contactEmail").value("sales@example.test"))
                .andExpect(jsonPath("$.contacts.length()").value(1))
                .andExpect(jsonPath("$.paymentDetails").doesNotExist())
                .andExpect(jsonPath("$.payoutPhone").doesNotExist())
                .andExpect(jsonPath("$.payoutType").doesNotExist())
                .andExpect(jsonPath("$.creditTermsDays").doesNotExist())
                .andExpect(jsonPath("$.products.length()").value(2))
                .andExpect(jsonPath("$.products[0].name").value("Zawadi Rice 2kg"))
                .andExpect(jsonPath("$.products[0].unitPrice").value(310.0))
                .andExpect(jsonPath("$.products[1].name").value("Zawadi Sugar 1kg"))
                .andExpect(jsonPath("$.products[1].unitPrice").doesNotExist());
    }

    @Test
    void aSupplierCanHideItsPricesAndContactsButStaysListed() throws Exception {
        MarketplaceSupplier passport = marketplaceSupplierRepository.findById(listedId).orElseThrow();
        passport.setHidePrices(true);
        passport.setHideContacts(true);
        marketplaceSupplierRepository.save(passport);

        mockMvc.perform(get("/api/v1/public/marketplace/suppliers/search").param("q", "Zawadi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].contactPhone").doesNotExist());
        mockMvc.perform(get("/api/v1/public/marketplace/suppliers/{id}", listedId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contactPhone").doesNotExist())
                .andExpect(jsonPath("$.contactEmail").doesNotExist())
                .andExpect(jsonPath("$.contacts.length()").value(0))
                .andExpect(jsonPath("$.products[0].unitPrice").doesNotExist());
    }

    private String savePassport(String number, String name, boolean listed) {
        MarketplaceSupplier passport = new MarketplaceSupplier();
        passport.setSupplierNumber(number);
        passport.setName(name);
        passport.setStatus(MarketplaceSupplierStatuses.ACTIVE);
        passport.setListed(listed);
        passport.setContactPhone("254700000001");
        passport.setContactEmail("sales@example.test");
        return marketplaceSupplierRepository.save(passport).getId();
    }

    private String saveProduct(String passportId, String name, String imageUrl) {
        MarketplaceSupplierProduct product = new MarketplaceSupplierProduct();
        product.setMarketplaceSupplierId(passportId);
        product.setName(name);
        product.setImageUrl(imageUrl);
        return productRepository.save(product).getId();
    }

    private void saveOffer(String passportId, String productId, BigDecimal price, boolean confirmed) {
        MarketplaceSupplierPriceOffer offer = new MarketplaceSupplierPriceOffer();
        offer.setMarketplaceSupplierId(passportId);
        offer.setProductId(productId);
        offer.setPackageSize(BigDecimal.ONE);
        offer.setPackageUnit("each");
        offer.setUnitPrice(price);
        offer.setPriceConfirmed(confirmed);
        offer.setEffectiveFrom(Instant.now().minusSeconds(60));
        priceOfferRepository.save(offer);
    }

    private String saveShopSupplier(String code, String name, String passportId) {
        Supplier supplier = new Supplier();
        supplier.setBusinessId(SHOP);
        supplier.setName(name);
        supplier.setCode(code);
        supplier.setSupplierType("distributor");
        supplier.setStatus("active");
        supplier.setPayoutPhone("254711111111");
        supplier.setPaymentDetails("Till 123456");
        supplier.setMarketplaceSupplierId(passportId);
        return supplierRepository.save(supplier).getId();
    }
}
