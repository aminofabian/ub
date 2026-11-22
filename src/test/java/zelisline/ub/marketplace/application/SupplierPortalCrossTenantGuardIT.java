package zelisline.ub.marketplace.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.marketplace.api.dto.LinkLocalSupplierRequest;
import zelisline.ub.marketplace.api.dto.SupplierPortalRespondRequest;
import zelisline.ub.marketplace.domain.BusinessSupplierConnection;
import zelisline.ub.marketplace.domain.BusinessSupplierConnectionStatuses;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.domain.MarketplaceSupplierStatuses;
import zelisline.ub.marketplace.repository.BusinessSupplierConnectionRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.purchasing.PurchasingConstants;
import zelisline.ub.purchasing.domain.PurchaseOrder;
import zelisline.ub.purchasing.repository.PurchaseOrderRepository;
import zelisline.ub.purchasing.repository.SupplierInvoiceRepository;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * ADR 0038 P0.3 / P0.4: a supplier account must not be able to open, or write into, a shop's books
 * unless that shop connected the supplier itself.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class SupplierPortalCrossTenantGuardIT {

    private static final String SHOP = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String PHONE = "254712345678";

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private MarketplaceSupplierRepository marketplaceSupplierRepository;

    @Autowired
    private BusinessSupplierConnectionRepository connectionRepository;

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private SupplierInvoiceRepository supplierInvoiceRepository;

    @Autowired
    private SupplierPortalShopLinkService shopLinkService;

    @Autowired
    private SupplierPortalProfileService profileService;

    @Autowired
    private SupplierPortalOrdersService ordersService;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private String passportId;

    @BeforeEach
    void seed() {
        purchaseOrderRepository.deleteAll();
        connectionRepository.deleteAll();
        supplierRepository.deleteAll();
        marketplaceSupplierRepository.deleteAll();
        businessRepository.deleteAll();

        Business shop = new Business();
        shop.setId(SHOP);
        shop.setName("Test shop");
        shop.setSlug("guard-shop");
        shop.setSettings("{}");
        businessRepository.save(shop);

        MarketplaceSupplier passport = new MarketplaceSupplier();
        passport.setSupplierNumber("S-900001");
        passport.setName("Outside Wholesalers");
        passport.setStatus(MarketplaceSupplierStatuses.ACTIVE);
        passport.setContactPhone(PHONE);
        passportId = marketplaceSupplierRepository.save(passport).getId();
    }

    @Test
    void matchingPhoneOrTagDoesNotCreateAConnection() {
        Supplier byPhone = saveLocal("phone", null);
        Supplier tagged = saveLocal("tagged", passportId);

        shopLinkService.ensureLinksAndCatalogue(passportId);

        assertThat(connectionRepository.count()).isZero();
        assertThat(supplierRepository.findById(byPhone.getId()).orElseThrow().getMarketplaceSupplierId())
                .isNull();
        assertThat(supplierRepository.findById(tagged.getId()).orElseThrow().getMarketplaceSupplierId())
                .isEqualTo(passportId);
    }

    @Test
    void suspendedConnectionIsNotReactivated() {
        Supplier local = saveLocal("phone", passportId);
        String connectionId = saveConnection(local.getId(), BusinessSupplierConnectionStatuses.SUSPENDED);

        shopLinkService.ensureLinksAndCatalogue(passportId);

        assertThat(connectionRepository.findById(connectionId).orElseThrow().getStatus())
                .isEqualTo(BusinessSupplierConnectionStatuses.SUSPENDED);
    }

    @Test
    void supplierCannotLinkItselfToAShop() {
        Supplier local = saveLocal("phone", null);

        assertThatThrownBy(() -> profileService.linkLocalSupplier(
                passportId, new LinkLocalSupplierRequest(local.getId())))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(connectionRepository.count()).isZero();
        assertThat(supplierRepository.findById(local.getId()).orElseThrow().getMarketplaceSupplierId())
                .isNull();
        assertThat(profileService.listLinkCandidates(passportId)).isEmpty();
    }

    @Test
    void tagWithoutActiveConnectionOpensNoOrdersOrInvoices() {
        Supplier local = saveLocal("phone", passportId);
        savePo(local.getId(), PurchasingConstants.PO_SENT, PurchasingConstants.PO_SOURCE_MARKETPLACE, Instant.now());

        assertThat(purchaseOrderRepository.findSupplierPortalInbox(passportId)).isEmpty();
        assertThat(supplierInvoiceRepository.findForSupplierPortal(passportId)).isEmpty();

        String connectionId = saveConnection(local.getId(), BusinessSupplierConnectionStatuses.ACTIVE);
        assertThat(purchaseOrderRepository.findSupplierPortalInbox(passportId)).hasSize(1);

        BusinessSupplierConnection connection = connectionRepository.findById(connectionId).orElseThrow();
        connection.setStatus(BusinessSupplierConnectionStatuses.SUSPENDED);
        connectionRepository.save(connection);
        assertThat(purchaseOrderRepository.findSupplierPortalInbox(passportId)).isEmpty();
    }

    @Test
    void inboxShowsTheSuppliersOwnProposalButNotTheShopsOtherDrafts() {
        Supplier local = saveLocal("phone", passportId);
        saveConnection(local.getId(), BusinessSupplierConnectionStatuses.ACTIVE);
        String proposalId = savePo(
                local.getId(), PurchasingConstants.PO_DRAFT, PurchasingConstants.PO_SOURCE_SUPPLIER_PORTAL, null);
        savePo(local.getId(), PurchasingConstants.PO_DRAFT, PurchasingConstants.PO_SOURCE_MANUAL, null);

        List<PurchaseOrder> inbox = purchaseOrderRepository.findSupplierPortalInbox(passportId);

        assertThat(inbox).extracting(PurchaseOrder::getId).containsExactly(proposalId);
    }

    @Test
    void supplierCannotRespondToAProposalTheShopHasNotSent() {
        Supplier local = saveLocal("phone", passportId);
        saveConnection(local.getId(), BusinessSupplierConnectionStatuses.ACTIVE);
        String proposalId = savePo(
                local.getId(), PurchasingConstants.PO_DRAFT, PurchasingConstants.PO_SOURCE_SUPPLIER_PORTAL, null);

        assertThatThrownBy(() -> ordersService.respond(
                passportId, proposalId, new SupplierPortalRespondRequest(List.of())))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        PurchaseOrder after = purchaseOrderRepository.findById(proposalId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(PurchasingConstants.PO_DRAFT);
        assertThat(after.getSupplierResponseAt()).isNull();
    }

    private Supplier saveLocal(String code, String marketplaceSupplierId) {
        Supplier supplier = new Supplier();
        supplier.setBusinessId(SHOP);
        supplier.setName("Outside Wholesalers " + code);
        supplier.setCode(code);
        supplier.setSupplierType("distributor");
        supplier.setStatus("active");
        supplier.setPayoutPhone(PHONE);
        supplier.setMarketplaceSupplierId(marketplaceSupplierId);
        return supplierRepository.save(supplier);
    }

    private String saveConnection(String localSupplierId, String status) {
        BusinessSupplierConnection connection = new BusinessSupplierConnection();
        connection.setBusinessId(SHOP);
        connection.setMarketplaceSupplierId(passportId);
        connection.setLocalSupplierId(localSupplierId);
        connection.setStatus(status);
        return connectionRepository.save(connection).getId();
    }

    private String savePo(String supplierId, String status, String source, Instant sentAt) {
        PurchaseOrder po = new PurchaseOrder();
        po.setId(UUID.randomUUID().toString());
        po.setBusinessId(SHOP);
        po.setSupplierId(supplierId);
        po.setBranchId(UUID.randomUUID().toString());
        po.setPoNumber("PO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        po.setStatus(status);
        po.setSource(source);
        po.setSentToSupplierAt(sentAt);
        return purchaseOrderRepository.save(po).getId();
    }
}
