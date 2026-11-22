package zelisline.ub.marketplace.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.marketplace.api.dto.AddSupplierPortalPackRequest;
import zelisline.ub.marketplace.api.dto.PatchSupplierPortalVisibilityRequest;
import zelisline.ub.marketplace.api.dto.SupplierPortalProductResponse;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.domain.MarketplaceSupplierPriceOffer;
import zelisline.ub.marketplace.domain.MarketplaceSupplierProduct;
import zelisline.ub.marketplace.domain.MarketplaceSupplierStatuses;
import zelisline.ub.marketplace.repository.MarketplaceSupplierPriceOfferRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierProductRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/** Packs and the hide switches a supplier manages for its own passport. */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class SupplierPortalCatalogPacksIT {

    @Autowired
    private SupplierPortalCatalogService catalogService;

    @Autowired
    private SupplierPortalProfileService profileService;

    @Autowired
    private MarketplaceSupplierRepository marketplaceSupplierRepository;

    @Autowired
    private MarketplaceSupplierProductRepository productRepository;

    @Autowired
    private MarketplaceSupplierPriceOfferRepository priceOfferRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private String passportId;
    private String otherPassportId;
    private String productId;

    @BeforeEach
    void seed() {
        priceOfferRepository.deleteAll();
        productRepository.deleteAll();
        marketplaceSupplierRepository.deleteAll();

        passportId = savePassport("S-900301", "Pack Wholesalers");
        otherPassportId = savePassport("S-900302", "Another Supplier");

        MarketplaceSupplierProduct product = new MarketplaceSupplierProduct();
        product.setMarketplaceSupplierId(passportId);
        product.setName("Soda 500ml");
        productId = productRepository.save(product).getId();

        MarketplaceSupplierPriceOffer single = new MarketplaceSupplierPriceOffer();
        single.setMarketplaceSupplierId(passportId);
        single.setProductId(productId);
        single.setPackageSize(BigDecimal.ONE);
        single.setPackageUnit("each");
        single.setUnitPrice(new BigDecimal("100.00"));
        single.setPriceConfirmed(true);
        single.setEffectiveFrom(Instant.now().minusSeconds(60));
        priceOfferRepository.save(single);
    }

    @Test
    void aSupplierAddsAndRemovesAPack() {
        SupplierPortalProductResponse withCarton = catalogService.addPack(
                passportId, productId, carton());

        assertThat(withCarton.packs()).hasSize(2);
        assertThat(withCarton.pricePublic()).isTrue();
        String cartonId = withCarton.packs().stream()
                .filter(p -> "carton".equals(p.packUnit()))
                .findFirst()
                .orElseThrow()
                .id();

        assertThatThrownBy(() -> catalogService.addPack(passportId, productId, carton()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        SupplierPortalProductResponse afterRemove = catalogService.removePack(passportId, productId, cartonId);
        assertThat(afterRemove.packs()).hasSize(1);

        String lastId = afterRemove.packs().get(0).id();
        assertThatThrownBy(() -> catalogService.removePack(passportId, productId, lastId))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void anotherSupplierCannotTouchTheProduct() {
        assertThatThrownBy(() -> catalogService.addPack(otherPassportId, productId, carton()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(priceOfferRepository.findByProductId(productId)).hasSize(1);
    }

    @Test
    void hideSwitchesChangeOnlyWhatWasSent() {
        var afterPrices = profileService.updateVisibility(
                passportId, new PatchSupplierPortalVisibilityRequest(true, null));
        assertThat(afterPrices.hidePrices()).isTrue();
        assertThat(afterPrices.hideContacts()).isFalse();

        profileService.updateVisibility(passportId, new PatchSupplierPortalVisibilityRequest(null, true));
        var now = profileService.getVisibility(passportId);
        assertThat(now.hidePrices()).isTrue();
        assertThat(now.hideContacts()).isTrue();
        assertThat(profileService.getVisibility(otherPassportId).hidePrices()).isFalse();
    }

    private static AddSupplierPortalPackRequest carton() {
        return new AddSupplierPortalPackRequest(new BigDecimal("12"), "carton", new BigDecimal("1100.00"));
    }

    private String savePassport(String number, String name) {
        MarketplaceSupplier passport = new MarketplaceSupplier();
        passport.setSupplierNumber(number);
        passport.setName(name);
        passport.setStatus(MarketplaceSupplierStatuses.ACTIVE);
        passport.setListed(true);
        return marketplaceSupplierRepository.save(passport).getId();
    }
}
