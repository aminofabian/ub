package zelisline.ub.suppliers.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import zelisline.ub.marketplace.domain.BusinessSupplierConnection;
import zelisline.ub.marketplace.domain.BusinessSupplierConnectionStatuses;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.domain.MarketplaceSupplierStatuses;
import zelisline.ub.marketplace.repository.BusinessSupplierConnectionRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.platform.security.SupplierPrincipal;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * ADR 0038 P0.2: a shop's balance with a supplier is not readable by whoever has the page link.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class PublicSupplierPageAccessIT {

    private static final String SHOP = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    @Autowired
    private PublicSupplierPortalService publicSupplierPortalService;

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private MarketplaceSupplierRepository marketplaceSupplierRepository;

    @Autowired
    private BusinessSupplierConnectionRepository connectionRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private String shopSupplierId;
    private String connectedPassportId;
    private String otherPassportId;
    private String connectionId;

    @BeforeEach
    void seed() {
        SecurityContextHolder.clearContext();
        connectionRepository.deleteAll();
        supplierRepository.deleteAll();
        marketplaceSupplierRepository.deleteAll();
        businessRepository.deleteAll();

        Business shop = new Business();
        shop.setId(SHOP);
        shop.setName("Test shop");
        shop.setSlug("page-access-shop");
        shop.setSettings("{}");
        businessRepository.save(shop);

        connectedPassportId = savePassport("S-900201", "Connected Wholesalers");
        otherPassportId = savePassport("S-900202", "Somebody Else Ltd");

        Supplier supplier = new Supplier();
        supplier.setBusinessId(SHOP);
        supplier.setName("Connected Wholesalers");
        supplier.setCode("connected");
        supplier.setSupplierType("distributor");
        supplier.setStatus("active");
        supplier.setMarketplaceSupplierId(connectedPassportId);
        shopSupplierId = supplierRepository.save(supplier).getId();

        BusinessSupplierConnection connection = new BusinessSupplierConnection();
        connection.setBusinessId(SHOP);
        connection.setMarketplaceSupplierId(connectedPassportId);
        connection.setLocalSupplierId(shopSupplierId);
        connection.setStatus(BusinessSupplierConnectionStatuses.ACTIVE);
        connectionId = connectionRepository.save(connection).getId();
    }

    @AfterEach
    void clearSignIn() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anonymousViewerOfAnUnsealedPageGetsNamesOnly() {
        var page = publicSupplierPortalService.overview(SHOP, shopSupplierId, null);

        assertThat(page.restricted()).isTrue();
        assertThat(page.supplierName()).isEqualTo("Connected Wholesalers");
        assertThat(page.supplies()).isEmpty();
        assertThat(page.movements()).isEmpty();
        assertThat(page.linkedProducts()).isEmpty();
        assertThat(publicSupplierPortalService.productsSelling(SHOP, shopSupplierId, null, null, null).products())
                .isEmpty();
    }

    @Test
    void theConnectedSupplierSeesItsOwnFigures() {
        signInAs(connectedPassportId);

        assertThat(publicSupplierPortalService.overview(SHOP, shopSupplierId, null).restricted()).isFalse();
    }

    @Test
    void aSupplierTheShopDidNotConnectGetsNamesOnly() {
        signInAs(otherPassportId);

        assertThat(publicSupplierPortalService.overview(SHOP, shopSupplierId, null).restricted()).isTrue();
    }

    @Test
    void aSuspendedSupplierGetsNamesOnly() {
        BusinessSupplierConnection connection = connectionRepository.findById(connectionId).orElseThrow();
        connection.setStatus(BusinessSupplierConnectionStatuses.SUSPENDED);
        connectionRepository.save(connection);
        signInAs(connectedPassportId);

        assertThat(publicSupplierPortalService.overview(SHOP, shopSupplierId, null).restricted()).isTrue();
    }

    private void signInAs(String passportId) {
        SupplierPrincipal principal = new SupplierPrincipal("user-1", passportId, "admin", null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private String savePassport(String number, String name) {
        MarketplaceSupplier passport = new MarketplaceSupplier();
        passport.setSupplierNumber(number);
        passport.setName(name);
        passport.setStatus(MarketplaceSupplierStatuses.ACTIVE);
        return marketplaceSupplierRepository.save(passport).getId();
    }
}
