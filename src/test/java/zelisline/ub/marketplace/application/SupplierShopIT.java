package zelisline.ub.marketplace.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.marketplace.api.dto.OpenSupplierShopRequest;
import zelisline.ub.marketplace.api.dto.SupplierShopResponse;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.domain.MarketplaceSupplierStatuses;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.tenancy.api.dto.StoreTypeCodes;
import zelisline.ub.tenancy.application.TenancyService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/** ADR 0038 P1: a supplier opens a shop of its own, bound to its passport. */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.tenancy.slug-domain-suffix=kiosk.test"
})
class SupplierShopIT {

    @Autowired
    private SupplierShopService supplierShopService;

    @Autowired
    private MarketplaceSupplierRepository marketplaceSupplierRepository;

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private DomainMappingRepository domainMappingRepository;

    @Autowired
    private TenancyService tenancyService;

    @BeforeEach
    void clean() {
        domainMappingRepository.deleteAll();
        marketplaceSupplierRepository.deleteAll();
        businessRepository.deleteAll();
    }

    @Test
    void openingAShopCreatesASupplierBusinessAtTheSuppliersUsername() {
        String passportId = savePassport("S-900401", "Acme Wholesalers", "acme");

        SupplierShopResponse shop = supplierShopService.open(passportId, new OpenSupplierShopRequest(null));

        assertThat(shop.opened()).isTrue();
        assertThat(shop.slug()).isEqualTo("acme");
        assertThat(shop.host()).isEqualTo("acme.kiosk.test");
        assertThat(marketplaceSupplierRepository.findById(passportId).orElseThrow().getBusinessId())
                .isEqualTo(shop.businessId());
        assertThat(StoreTypeCodes.isSupplierBusiness(tenancyService.currentStoreTypesForTenant(shop.businessId())))
                .isTrue();
        assertThat(domainMappingRepository.findByDomainAndActiveTrue("acme.kiosk.test")).isPresent();
    }

    @Test
    void openingTwiceReturnsTheSameShop() {
        String passportId = savePassport("S-900402", "Twice Traders", "twice");

        SupplierShopResponse first = supplierShopService.open(passportId, new OpenSupplierShopRequest(null));
        SupplierShopResponse second = supplierShopService.open(passportId, new OpenSupplierShopRequest("another"));

        assertThat(second.businessId()).isEqualTo(first.businessId());
        assertThat(second.slug()).isEqualTo("twice");
        assertThat(businessRepository.count()).isEqualTo(1);
    }

    @Test
    void aUsernameAnotherShopAlreadyHoldsFallsBackToAnAddressFromTheName() {
        Business existing = new Business();
        existing.setName("Somebody's Mini Mart");
        existing.setSlug("taken");
        existing.setSettings("{}");
        businessRepository.save(existing);
        String passportId = savePassport("S-900403", "Taken Name Suppliers", "taken");

        SupplierShopResponse shop = supplierShopService.open(passportId, new OpenSupplierShopRequest(null));

        assertThat(shop.opened()).isTrue();
        assertThat(shop.slug()).isNotEqualTo("taken").startsWith("takennamesuppliers");
        assertThat(shop.businessId()).isNotEqualTo(existing.getId());
    }

    @Test
    void anAddressTheSupplierTypedThatIsTakenIsRefusedAndNothingIsBound() {
        Business existing = new Business();
        existing.setName("Somebody's Mini Mart");
        existing.setSlug("wanted");
        existing.setSettings("{}");
        businessRepository.save(existing);
        String passportId = savePassport("S-900404", "Wanted Suppliers", "wanted-sup");

        assertThatThrownBy(() -> supplierShopService.open(passportId, new OpenSupplierShopRequest("wanted")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(marketplaceSupplierRepository.findById(passportId).orElseThrow().getBusinessId()).isNull();
    }

    @Test
    void aReservedAddressIsRefused() {
        String passportId = savePassport("S-900405", "Marketplace Suppliers", "mkt-sup");

        assertThatThrownBy(() -> supplierShopService.open(passportId, new OpenSupplierShopRequest("marketplace")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(businessRepository.count()).isZero();
    }

    @Test
    void aSupplierThatIsNotActiveCannotOpenAShop() {
        MarketplaceSupplier passport = new MarketplaceSupplier();
        passport.setSupplierNumber("S-900406");
        passport.setName("Draft Suppliers");
        passport.setStatus(MarketplaceSupplierStatuses.DRAFT);
        String passportId = marketplaceSupplierRepository.save(passport).getId();

        assertThatThrownBy(() -> supplierShopService.open(passportId, new OpenSupplierShopRequest(null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(supplierShopService.current(passportId).opened()).isFalse();
    }

    private String savePassport(String number, String name, String username) {
        MarketplaceSupplier passport = new MarketplaceSupplier();
        passport.setSupplierNumber(number);
        passport.setName(name);
        passport.setUsername(username);
        passport.setStatus(MarketplaceSupplierStatuses.ACTIVE);
        return marketplaceSupplierRepository.save(passport).getId();
    }
}
