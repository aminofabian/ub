package zelisline.ub.marketplace.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import zelisline.ub.catalog.api.dto.CreateItemRequest;
import zelisline.ub.catalog.application.CatalogBootstrapService;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.identity.domain.Permission;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.RolePermission;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.PermissionRepository;
import zelisline.ub.identity.repository.RolePermissionRepository;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
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
import zelisline.ub.marketplace.repository.SupplierIdentityIndexRepository;
import zelisline.ub.platform.security.TestAuthenticationFilter;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierProduct;
import zelisline.ub.suppliers.repository.SupplierContactRepository;
import zelisline.ub.suppliers.repository.SupplierProductRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * Choosing which products to pull when a shop attaches a global supplier:
 * {@code GET /marketplace/suppliers/{id}/attach-preview} and the optional {@code productIds}
 * on the three attach endpoints.
 *
 * <p>Most scenarios have the shop already stocking what it pulls (matched by barcode), so the attach links
 * existing items; the "creating, and not duplicating" group covers products the shop lacks, and the ways
 * a product the shop has is recognised (barcode, then name) so that it is linked rather than created again.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class MarketplaceAttachSelectionIT {

    private static final String TENANT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String TENANT_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    private static final String P_CONNECT = "11111111-0000-0000-0000-0000000000a1";
    private static final String ROLE_OWNER = "22222222-0000-0000-0000-000000000001";
    private static final String ROLE_CLERK = "22222222-0000-0000-0000-000000000002";

    private static final String UNGA = "6001";
    private static final String SUGAR = "6002";
    private static final String RICE = "6003";
    private static final String OIL = "6004";
    private static final String RETIRED = "6005";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private RolePermissionRepository rolePermissionRepository;

    @Autowired
    private ItemTypeRepository itemTypeRepository;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private SupplierContactRepository supplierContactRepository;

    @Autowired
    private SupplierProductRepository supplierProductRepository;

    @Autowired
    private MarketplaceSupplierRepository marketplaceSupplierRepository;

    @Autowired
    private MarketplaceSupplierProductRepository marketplaceProductRepository;

    @Autowired
    private MarketplaceSupplierPriceOfferRepository priceOfferRepository;

    @Autowired
    private BusinessSupplierConnectionRepository connectionRepository;

    @Autowired
    private SupplierIdentityIndexRepository identityIndexRepository;

    @Autowired
    private CatalogBootstrapService catalogBootstrapService;

    @Autowired
    private ItemCatalogService itemCatalogService;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User ownerA;
    private User ownerB;

    @BeforeEach
    void seed() {
        cleanAll();

        insertBusiness(TENANT_A, "tenant-a-attach");
        insertBusiness(TENANT_B, "tenant-b-attach");
        catalogBootstrapService.seedDefaultItemTypesIfMissing(TENANT_A);
        catalogBootstrapService.seedDefaultItemTypesIfMissing(TENANT_B);

        permissionRepository.save(perm(P_CONNECT, "marketplace.suppliers.connect", "connect"));
        saveRole(ROLE_OWNER, "owner", "Owner");
        saveRole(ROLE_CLERK, "clerk", "Clerk");
        grant(ROLE_OWNER, P_CONNECT);

        ownerA = userRepository.save(user("owner-a@attach", TENANT_A));
        ownerB = userRepository.save(user("owner-b@attach", TENANT_B));
    }

    @AfterEach
    void tearDown() {
        cleanAll();
    }

    // ------------------------------------------------------------------ attach by id

    @Test
    void attachWithSelectionLinksOnlyTheChosenProducts() throws Exception {
        stockShopA(UNGA, SUGAR, RICE);
        Passport mama = passportWithShelf("S-9001", "Mama Njeri Wholesalers");

        MvcResult result = attach(TENANT_A, ROLE_OWNER, mama.passport().getId(),
                body(mama.id(UNGA), mama.id(RICE)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.supplierNumber").value("S-9001"))
                .andExpect(jsonPath("$.linkedExisting").value(2))
                .andExpect(jsonPath("$.createdItems").value(0))
                .andExpect(jsonPath("$.skipped").value(0))
                .andReturn();

        String localSupplierId = JsonPath.read(result.getResponse().getContentAsString(), "$.localSupplierId");
        assertThat(linkedBarcodes(TENANT_A, localSupplierId)).containsExactly(UNGA, RICE);
        // Products that were not chosen are neither linked nor created as items.
        assertThat(itemRepository.findByBusinessIdAndBarcodeAndDeletedAtIsNull(TENANT_A, OIL)).isEmpty();
        assertThat(connectionRepository
                .findByBusinessIdAndMarketplaceSupplierId(TENANT_A, mama.passport().getId())
                .orElseThrow()
                .getStatus()).isEqualTo(BusinessSupplierConnectionStatuses.ACTIVE);
    }

    @Test
    void attachWithoutABodyPullsTheWholeShelf() throws Exception {
        stockShopA(UNGA, SUGAR);
        Passport baraka = passport("S-9002", "Baraka Distributors");
        baraka.add("Unga 2kg", UNGA, "120.00", MarketplaceSupplierProductStatuses.ACTIVE);
        baraka.add("Sugar 1kg", SUGAR, "150.00", MarketplaceSupplierProductStatuses.ACTIVE);

        MvcResult result = attach(TENANT_A, ROLE_OWNER, baraka.passport().getId(), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.linkedExisting").value(2))
                .andExpect(jsonPath("$.skipped").value(0))
                .andReturn();

        String localSupplierId = JsonPath.read(result.getResponse().getContentAsString(), "$.localSupplierId");
        assertThat(linkedBarcodes(TENANT_A, localSupplierId)).containsExactly(UNGA, SUGAR);
    }

    @Test
    void attachWithAnEmptySelectionCreatesTheSupplierOnly() throws Exception {
        stockShopA(UNGA, SUGAR, RICE);
        Passport mama = passportWithShelf("S-9001", "Mama Njeri Wholesalers");

        MvcResult result = attach(TENANT_A, ROLE_OWNER, mama.passport().getId(), body())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.supplierName").value("Mama Njeri Wholesalers"))
                .andExpect(jsonPath("$.linkedExisting").value(0))
                .andExpect(jsonPath("$.createdItems").value(0))
                .andExpect(jsonPath("$.alreadyLinked").value(0))
                .andExpect(jsonPath("$.skipped").value(0))
                .andReturn();

        String localSupplierId = JsonPath.read(result.getResponse().getContentAsString(), "$.localSupplierId");
        Supplier local = supplierRepository.findById(localSupplierId).orElseThrow();
        assertThat(local.getBusinessId()).isEqualTo(TENANT_A);
        assertThat(local.getMarketplaceSupplierId()).isEqualTo(mama.passport().getId());
        assertThat(linkedBarcodes(TENANT_A, localSupplierId)).isEmpty();
        assertThat(itemRepository.findByBusinessIdAndBarcodeAndDeletedAtIsNull(TENANT_A, OIL)).isEmpty();
    }

    @Test
    void staleAndForeignProductIdsAreSkippedNeverImported() throws Exception {
        stockShopA(UNGA, SUGAR, RICE);
        Passport mama = passportWithShelf("S-9001", "Mama Njeri Wholesalers");
        Passport baraka = passport("S-9002", "Baraka Distributors");
        baraka.add("Sugar 1kg", SUGAR, "150.00", MarketplaceSupplierProductStatuses.ACTIVE);

        // One real product, one the supplier has since retired, one that belongs to another supplier,
        // and one that never existed.
        MvcResult result = attach(TENANT_A, ROLE_OWNER, mama.passport().getId(),
                body(mama.id(UNGA), mama.id(RETIRED), baraka.id(SUGAR), UUID.randomUUID().toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.linkedExisting").value(1))
                .andExpect(jsonPath("$.skipped").value(3))
                .andReturn();

        String localSupplierId = JsonPath.read(result.getResponse().getContentAsString(), "$.localSupplierId");
        assertThat(linkedBarcodes(TENANT_A, localSupplierId)).containsExactly(UNGA);
    }

    @Test
    void attachingAgainTopsUpTheSameSupplierWithoutDuplicates() throws Exception {
        stockShopA(UNGA, SUGAR, RICE);
        Passport mama = passportWithShelf("S-9001", "Mama Njeri Wholesalers");
        String passportId = mama.passport().getId();

        MvcResult first = attach(TENANT_A, ROLE_OWNER, passportId, body(mama.id(UNGA)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.linkedExisting").value(1))
                .andReturn();
        String localSupplierId = JsonPath.read(first.getResponse().getContentAsString(), "$.localSupplierId");

        attach(TENANT_A, ROLE_OWNER, passportId, body(mama.id(SUGAR)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.localSupplierId").value(localSupplierId))
                .andExpect(jsonPath("$.linkedExisting").value(1))
                .andExpect(jsonPath("$.alreadyLinked").value(0));

        attach(TENANT_A, ROLE_OWNER, passportId, body(mama.id(UNGA), mama.id(SUGAR)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.localSupplierId").value(localSupplierId))
                .andExpect(jsonPath("$.linkedExisting").value(0))
                .andExpect(jsonPath("$.alreadyLinked").value(2));

        assertThat(linkedBarcodes(TENANT_A, localSupplierId)).containsExactly(UNGA, SUGAR);
        assertThat(supplierRepository.findByMarketplaceSupplierIdAndDeletedAtIsNull(passportId))
                .hasSize(1);
    }

    @Test
    void rejectsABlankProductId() throws Exception {
        Passport mama = passportWithShelf("S-9001", "Mama Njeri Wholesalers");

        attach(TENANT_A, ROLE_OWNER, mama.passport().getId(), "{\"productIds\":[\"\"]}")
                .andExpect(status().isBadRequest());

        assertThat(connectionRepository
                .findByBusinessIdAndMarketplaceSupplierId(TENANT_A, mama.passport().getId())).isEmpty();
    }

    // ------------------------------------------------------------------ attach by number / from seed

    @Test
    void attachByNumberHonoursTheSelection() throws Exception {
        stockShopA(UNGA, SUGAR, RICE);
        Passport mama = passportWithShelf("S-9001", "Mama Njeri Wholesalers");

        MvcResult result = postJson("/api/v1/marketplace/suppliers/attach-by-number", TENANT_A, ROLE_OWNER,
                "{\"supplierNumber\":\"S-9001\",\"productIds\":[\"" + mama.id(SUGAR) + "\"]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.supplierNumber").value("S-9001"))
                .andExpect(jsonPath("$.linkedExisting").value(1))
                .andReturn();

        String localSupplierId = JsonPath.read(result.getResponse().getContentAsString(), "$.localSupplierId");
        assertThat(linkedBarcodes(TENANT_A, localSupplierId)).containsExactly(SUGAR);
    }

    @Test
    void attachByNumberWithoutAnySelectionStillPullsEverything() throws Exception {
        stockShopA(UNGA, SUGAR);
        Passport baraka = passport("S-9002", "Baraka Distributors");
        baraka.add("Unga 2kg", UNGA, "120.00", MarketplaceSupplierProductStatuses.ACTIVE);
        baraka.add("Sugar 1kg", SUGAR, "150.00", MarketplaceSupplierProductStatuses.ACTIVE);

        MvcResult result = postJson("/api/v1/marketplace/suppliers/attach-by-number", TENANT_A, ROLE_OWNER,
                "{\"supplierNumber\":\"S-9002\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.linkedExisting").value(2))
                .andReturn();

        String localSupplierId = JsonPath.read(result.getResponse().getContentAsString(), "$.localSupplierId");
        assertThat(linkedBarcodes(TENANT_A, localSupplierId)).containsExactly(UNGA, SUGAR);
    }

    @Test
    void attachFromSeedMapsTheSeedShopsLinkIdsOntoThePassportShelf() throws Exception {
        stockShopA(UNGA, SUGAR);
        Passport seedPassport = passport("S-9004", "Seed Supplies Ltd");
        seedPassport.add("Unga 2kg", UNGA, "120.00", MarketplaceSupplierProductStatuses.ACTIVE);
        seedPassport.add("Sugar 1kg", SUGAR, "150.00", MarketplaceSupplierProductStatuses.ACTIVE);

        // Another shop's own copy of that supplier, already tagged with the passport, selling both lines.
        Supplier seed = saveLocalSupplier(TENANT_B, "Seed Supplies Ltd", seedPassport.passport().getId());
        String ungaB = saveItem(TENANT_B, "SKU-B-" + UNGA, UNGA, "Unga 2kg");
        String sugarB = saveItem(TENANT_B, "SKU-B-" + SUGAR, SUGAR, "Sugar 1kg");
        String ungaLink = saveLink(seed.getId(), ungaB);
        saveLink(seed.getId(), sugarB);

        MvcResult result = postJson("/api/v1/marketplace/suppliers/attach-from-seed", TENANT_A, ROLE_OWNER,
                "{\"sourceLocalSupplierId\":\"" + seed.getId() + "\",\"productIds\":[\"" + ungaLink + "\"]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.linkedExisting").value(1))
                .andReturn();

        String localSupplierId = JsonPath.read(result.getResponse().getContentAsString(), "$.localSupplierId");
        assertThat(linkedBarcodes(TENANT_A, localSupplierId)).containsExactly(UNGA);
    }

    // ------------------------------------------------------------------ creating, and not duplicating

    @Test
    void productsTheShopLacksAreCreatedAndLinkedToTheSupplier() throws Exception {
        // Shop A stocks nothing yet, so every chosen product has to be created.
        Passport mama = passportWithShelf("S-9001", "Mama Njeri Wholesalers");

        MvcResult result = attach(TENANT_A, ROLE_OWNER, mama.passport().getId(),
                body(mama.id(UNGA), mama.id(SUGAR)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdItems").value(2))
                .andExpect(jsonPath("$.linkedExisting").value(0))
                .andExpect(jsonPath("$.alreadyLinked").value(0))
                .andExpect(jsonPath("$.skipped").value(0))
                .andExpect(jsonPath("$.notes.length()").value(0))
                .andReturn();

        String localSupplierId = JsonPath.read(result.getResponse().getContentAsString(), "$.localSupplierId");
        // The new items exist and are linked to this supplier, not left floating in the catalogue.
        assertThat(linkedBarcodes(TENANT_A, localSupplierId)).containsExactly(UNGA, SUGAR);
        assertThat(itemRepository.findByBusinessIdAndBarcodeAndDeletedAtIsNull(TENANT_A, UNGA)).isPresent();
        assertThat(itemRepository.findByBusinessIdAndBarcodeAndDeletedAtIsNull(TENANT_A, OIL)).isEmpty();
    }

    @Test
    void aProductTheShopAlreadyHasIsLinkedNotCreatedAgainAndReported() throws Exception {
        stockShopA(UNGA);
        Passport mama = passportWithShelf("S-9001", "Mama Njeri Wholesalers");

        MvcResult result = attach(TENANT_A, ROLE_OWNER, mama.passport().getId(),
                body(mama.id(UNGA), mama.id(SUGAR)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdItems").value(1))
                .andExpect(jsonPath("$.linkedExisting").value(1))
                .andExpect(jsonPath("$.skipped").value(0))
                .andExpect(jsonPath("$.notes.length()").value(1))
                .andExpect(jsonPath("$.notes[0].outcome").value("existing_linked"))
                .andExpect(jsonPath("$.notes[0].name").value("Unga 2kg"))
                .andExpect(jsonPath("$.notes[0].barcode").value(UNGA))
                .andExpect(jsonPath("$.notes[0].reason")
                        .value("Already in your shop as \"Shop A 6001\" (same barcode)"))
                .andReturn();

        String localSupplierId = JsonPath.read(result.getResponse().getContentAsString(), "$.localSupplierId");
        assertThat(linkedBarcodes(TENANT_A, localSupplierId)).containsExactly(UNGA, SUGAR);
        // The shop's own unga plus the new sugar: nothing was duplicated.
        assertThat(itemRepository.findByBusinessIdAndDeletedAtIsNull(TENANT_A)).hasSize(2);
    }

    @Test
    void theSameProductListedTwiceOnAShelfIsCreatedOnce() throws Exception {
        Passport doubled = passport("S-9005", "Double Entry Traders");
        doubled.add("Unga 2kg", UNGA, "120.00", MarketplaceSupplierProductStatuses.ACTIVE);
        doubled.add("Unga Jogoo 2kg", UNGA, "125.00", MarketplaceSupplierProductStatuses.ACTIVE);

        MvcResult result = attach(TENANT_A, ROLE_OWNER, doubled.passport().getId(), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdItems").value(1))
                .andExpect(jsonPath("$.alreadyLinked").value(1))
                .andExpect(jsonPath("$.skipped").value(0))
                .andExpect(jsonPath("$.notes.length()").value(1))
                .andExpect(jsonPath("$.notes[0].outcome").value("already_linked"))
                .andReturn();

        String localSupplierId = JsonPath.read(result.getResponse().getContentAsString(), "$.localSupplierId");
        assertThat(linkedBarcodes(TENANT_A, localSupplierId)).containsExactly(UNGA);
        assertThat(itemRepository.findByBusinessIdAndDeletedAtIsNull(TENANT_A)).hasSize(1);
    }

    @Test
    void aProductWithNoBarcodeIsMatchedToTheSameNameInTheShop() throws Exception {
        saveItem(TENANT_A, "SKU-A-NAMED", null, "Unga 2kg");
        Passport mama = passport("S-9006", "Name Only Traders");
        mama.add("Unga 2kg", null, "120.00", MarketplaceSupplierProductStatuses.ACTIVE);

        MvcResult first = attach(TENANT_A, ROLE_OWNER, mama.passport().getId(), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdItems").value(0))
                .andExpect(jsonPath("$.linkedExisting").value(1))
                .andExpect(jsonPath("$.notes[0].outcome").value("existing_linked"))
                .andExpect(jsonPath("$.notes[0].reason")
                        .value("Already in your shop as \"Unga 2kg\" (same name)"))
                .andReturn();

        String localSupplierId = JsonPath.read(first.getResponse().getContentAsString(), "$.localSupplierId");
        assertThat(supplierProductRepository.listForSupplier(TENANT_A, localSupplierId)).hasSize(1);

        // Attaching again neither creates it nor links it twice.
        attach(TENANT_A, ROLE_OWNER, mama.passport().getId(), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdItems").value(0))
                .andExpect(jsonPath("$.alreadyLinked").value(1));
        assertThat(itemRepository.findByBusinessIdAndDeletedAtIsNull(TENANT_A)).hasSize(1);
        assertThat(supplierProductRepository.listForSupplier(TENANT_A, localSupplierId)).hasSize(1);
    }

    @Test
    void twoDifferentBarcodesAreNeverTreatedAsTheSameProduct() throws Exception {
        saveItem(TENANT_A, "SKU-A-OTHER", "9999", "Unga 2kg");
        Passport mama = passport("S-9007", "Look-alike Traders");
        mama.add("Unga 2kg", UNGA, "120.00", MarketplaceSupplierProductStatuses.ACTIVE);

        attach(TENANT_A, ROLE_OWNER, mama.passport().getId(), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdItems").value(1))
                .andExpect(jsonPath("$.linkedExisting").value(0))
                .andExpect(jsonPath("$.notes.length()").value(0));

        assertThat(itemRepository.findByBusinessIdAndDeletedAtIsNull(TENANT_A)).hasSize(2);
    }

    @Test
    void aShelfBarcodeWrittenWithSeparatorsStillMatchesTheShopsItem() throws Exception {
        stockShopA(UNGA);
        Passport mama = passport("S-9009", "Dashed Traders");
        mama.add("Unga 2kg", "60-01", "120.00", MarketplaceSupplierProductStatuses.ACTIVE);

        attach(TENANT_A, ROLE_OWNER, mama.passport().getId(), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdItems").value(0))
                .andExpect(jsonPath("$.linkedExisting").value(1));

        assertThat(itemRepository.findByBusinessIdAndDeletedAtIsNull(TENANT_A)).hasSize(1);
    }

    @Test
    void aSupplierCodeTheShopAlreadyUsesDoesNotStopTheProductBeingAdded() throws Exception {
        saveItem(TENANT_A, "MAMA-001", RICE, "Shop A rice");
        Passport mama = passport("S-9008", "Coded Traders");
        mama.add("Unga 2kg", UNGA, "MAMA-001", "120.00", MarketplaceSupplierProductStatuses.ACTIVE);

        MvcResult result = attach(TENANT_A, ROLE_OWNER, mama.passport().getId(), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdItems").value(1))
                .andExpect(jsonPath("$.skipped").value(0))
                .andReturn();

        String localSupplierId = JsonPath.read(result.getResponse().getContentAsString(), "$.localSupplierId");
        SupplierProduct link = supplierProductRepository.listForSupplier(TENANT_A, localSupplierId).get(0);
        // The supplier's own code stays on the link; the new item was issued a code of its own.
        assertThat(link.getSupplierSku()).isEqualTo("MAMA-001");
        assertThat(itemRepository.findById(link.getItemId()).orElseThrow().getSku()).isNotEqualTo("MAMA-001");
        assertThat(itemRepository.findByBusinessIdAndSku(TENANT_A, "MAMA-001").orElseThrow().getBarcode())
                .isEqualTo(RICE);
    }

    // ------------------------------------------------------------------ preview

    @Test
    void previewFlagsWhatEachShopAlreadyStocksAndHasLinked() throws Exception {
        stockShopA(UNGA, SUGAR, RICE);
        saveItem(TENANT_B, "SKU-B-" + OIL, OIL, "Cooking Oil 3L");
        Passport mama = passportWithShelf("S-9001", "Mama Njeri Wholesalers");

        MvcResult attached = attach(TENANT_A, ROLE_OWNER, mama.passport().getId(), body(mama.id(UNGA)))
                .andExpect(status().isCreated())
                .andReturn();
        String localSupplierId = JsonPath.read(attached.getResponse().getContentAsString(), "$.localSupplierId");

        // Shop A: connected, stocks unga / sugar / rice, and has only unga linked. The retired product
        // is not offered. Rows come back sorted by name.
        preview(TENANT_A, ROLE_OWNER, mama.passport().getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marketplaceSupplierId").value(mama.passport().getId()))
                .andExpect(jsonPath("$.supplierNumber").value("S-9001"))
                .andExpect(jsonPath("$.supplierName").value("Mama Njeri Wholesalers"))
                .andExpect(jsonPath("$.alreadyConnected").value(true))
                .andExpect(jsonPath("$.localSupplierId").value(localSupplierId))
                .andExpect(jsonPath("$.products.length()").value(4))
                .andExpect(jsonPath("$.products[0].name").value("Cooking Oil 3L"))
                .andExpect(jsonPath("$.products[0].id").value(mama.id(OIL)))
                .andExpect(jsonPath("$.products[0].inShop").value(false))
                .andExpect(jsonPath("$.products[0].alreadyLinked").value(false))
                .andExpect(jsonPath("$.products[1].name").value("Rice 5kg"))
                .andExpect(jsonPath("$.products[1].inShop").value(true))
                .andExpect(jsonPath("$.products[1].alreadyLinked").value(false))
                .andExpect(jsonPath("$.products[2].name").value("Sugar 1kg"))
                .andExpect(jsonPath("$.products[2].inShop").value(true))
                .andExpect(jsonPath("$.products[2].alreadyLinked").value(false))
                .andExpect(jsonPath("$.products[3].name").value("Unga 2kg"))
                .andExpect(jsonPath("$.products[3].barcode").value(UNGA))
                .andExpect(jsonPath("$.products[3].unitPrice").value(120.0))
                .andExpect(jsonPath("$.products[3].currency").value("KES"))
                .andExpect(jsonPath("$.products[3].inShop").value(true))
                .andExpect(jsonPath("$.products[3].alreadyLinked").value(true));

        // Shop B has not connected and stocks only cooking oil: it sees neither shop A's connection nor
        // shop A's links, and the flags are its own.
        preview(TENANT_B, ROLE_OWNER, mama.passport().getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyConnected").value(false))
                .andExpect(jsonPath("$.localSupplierId").doesNotExist())
                .andExpect(jsonPath("$.products.length()").value(4))
                .andExpect(jsonPath("$.products[0].name").value("Cooking Oil 3L"))
                .andExpect(jsonPath("$.products[0].inShop").value(true))
                .andExpect(jsonPath("$.products[0].alreadyLinked").value(false))
                .andExpect(jsonPath("$.products[3].name").value("Unga 2kg"))
                .andExpect(jsonPath("$.products[3].inShop").value(false))
                .andExpect(jsonPath("$.products[3].alreadyLinked").value(false));
    }

    @Test
    void previewTreatsAnItemWithTheSameNameAndNoBarcodeAsInTheShop() throws Exception {
        saveItem(TENANT_A, "SKU-A-NAMED", null, "Unga 2kg");
        Passport mama = passportWithShelf("S-9001", "Mama Njeri Wholesalers");

        preview(TENANT_A, ROLE_OWNER, mama.passport().getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products[0].name").value("Cooking Oil 3L"))
                .andExpect(jsonPath("$.products[0].inShop").value(false))
                .andExpect(jsonPath("$.products[3].name").value("Unga 2kg"))
                .andExpect(jsonPath("$.products[3].inShop").value(true));
    }

    @Test
    void previewDoesNotChangeAnything() throws Exception {
        stockShopA(UNGA, SUGAR, RICE);
        Passport mama = passportWithShelf("S-9001", "Mama Njeri Wholesalers");

        preview(TENANT_A, ROLE_OWNER, mama.passport().getId()).andExpect(status().isOk());

        assertThat(connectionRepository
                .findByBusinessIdAndMarketplaceSupplierId(TENANT_A, mama.passport().getId())).isEmpty();
        assertThat(supplierRepository.findByMarketplaceSupplierIdAndDeletedAtIsNull(mama.passport().getId()))
                .isEmpty();
    }

    // ------------------------------------------------------------------ failure paths

    @Test
    void callersWithoutTheConnectPermissionAreRefused() throws Exception {
        stockShopA(UNGA);
        Passport mama = passportWithShelf("S-9001", "Mama Njeri Wholesalers");

        preview(TENANT_A, ROLE_CLERK, mama.passport().getId()).andExpect(status().isForbidden());
        attach(TENANT_A, ROLE_CLERK, mama.passport().getId(), body(mama.id(UNGA)))
                .andExpect(status().isForbidden());

        assertThat(connectionRepository
                .findByBusinessIdAndMarketplaceSupplierId(TENANT_A, mama.passport().getId())).isEmpty();
    }

    @Test
    void aSuspendedSupplierCanNeitherBePreviewedNorAttached() throws Exception {
        MarketplaceSupplier suspended = savePassport("S-9003", "Struck Off Traders",
                MarketplaceSupplierStatuses.SUSPENDED);

        preview(TENANT_A, ROLE_OWNER, suspended.getId()).andExpect(status().isNotFound());
        attach(TENANT_A, ROLE_OWNER, suspended.getId(), body()).andExpect(status().isNotFound());
    }

    @Test
    void anUnknownSupplierIsNotFound() throws Exception {
        String unknown = UUID.randomUUID().toString();

        preview(TENANT_A, ROLE_OWNER, unknown).andExpect(status().isNotFound());
        attach(TENANT_A, ROLE_OWNER, unknown, body()).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ requests

    private ResultActions preview(String tenantId, String roleId, String marketplaceSupplierId) throws Exception {
        return mockMvc.perform(get("/api/v1/marketplace/suppliers/{id}/attach-preview", marketplaceSupplierId)
                .header("X-Tenant-Id", tenantId)
                .header(TestAuthenticationFilter.HEADER_USER_ID, userId(tenantId))
                .header(TestAuthenticationFilter.HEADER_ROLE_ID, roleId));
    }

    /** {@code jsonBody == null} sends no body at all (the original, pull-everything contract). */
    private ResultActions attach(String tenantId, String roleId, String marketplaceSupplierId, String jsonBody)
            throws Exception {
        var request = post("/api/v1/marketplace/suppliers/{id}/attach", marketplaceSupplierId)
                .header("X-Tenant-Id", tenantId)
                .header(TestAuthenticationFilter.HEADER_USER_ID, userId(tenantId))
                .header(TestAuthenticationFilter.HEADER_ROLE_ID, roleId);
        if (jsonBody != null) {
            request = request.contentType(APPLICATION_JSON).content(jsonBody);
        }
        return mockMvc.perform(request);
    }

    private ResultActions postJson(String url, String tenantId, String roleId, String jsonBody) throws Exception {
        return mockMvc.perform(post(url)
                .header("X-Tenant-Id", tenantId)
                .header(TestAuthenticationFilter.HEADER_USER_ID, userId(tenantId))
                .header(TestAuthenticationFilter.HEADER_ROLE_ID, roleId)
                .contentType(APPLICATION_JSON)
                .content(jsonBody));
    }

    private static String body(String... productIds) {
        StringBuilder out = new StringBuilder("{\"productIds\":[");
        for (int i = 0; i < productIds.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append('"').append(productIds[i]).append('"');
        }
        return out.append("]}").toString();
    }

    // ------------------------------------------------------------------ data

    /** A global supplier and the shelf rows it was given, addressable by barcode. */
    private final class Passport {

        private final MarketplaceSupplier passport;
        private final Map<String, String> productIdByBarcode = new HashMap<>();

        Passport(MarketplaceSupplier passport) {
            this.passport = passport;
        }

        MarketplaceSupplier passport() {
            return passport;
        }

        String id(String barcode) {
            return productIdByBarcode.get(barcode);
        }

        void add(String name, String barcode, String price, String status) {
            add(name, barcode, null, price, status);
        }

        void add(String name, String barcode, String sku, String price, String status) {
            MarketplaceSupplierProduct product = new MarketplaceSupplierProduct();
            product.setMarketplaceSupplierId(passport.getId());
            product.setName(name);
            product.setBarcode(barcode);
            product.setSku(sku);
            product.setStatus(status);
            product = marketplaceProductRepository.save(product);
            productIdByBarcode.put(barcode, product.getId());

            MarketplaceSupplierPriceOffer offer = new MarketplaceSupplierPriceOffer();
            offer.setMarketplaceSupplierId(passport.getId());
            offer.setProductId(product.getId());
            offer.setPackageSize(BigDecimal.ONE);
            offer.setPackageUnit("each");
            offer.setUnitPrice(new BigDecimal(price));
            priceOfferRepository.save(offer);
        }
    }

    private Passport passport(String number, String name) {
        return new Passport(savePassport(number, name, MarketplaceSupplierStatuses.ACTIVE));
    }

    /** Unga, sugar, rice and cooking oil on the shelf, plus one product the supplier has retired. */
    private Passport passportWithShelf(String number, String name) {
        Passport p = passport(number, name);
        p.add("Unga 2kg", UNGA, "120.00", MarketplaceSupplierProductStatuses.ACTIVE);
        p.add("Sugar 1kg", SUGAR, "150.00", MarketplaceSupplierProductStatuses.ACTIVE);
        p.add("Rice 5kg", RICE, "640.00", MarketplaceSupplierProductStatuses.ACTIVE);
        p.add("Cooking Oil 3L", OIL, "810.00", MarketplaceSupplierProductStatuses.ACTIVE);
        p.add("Retired Flour 1kg", RETIRED, "90.00", MarketplaceSupplierProductStatuses.INACTIVE);
        return p;
    }

    private MarketplaceSupplier savePassport(String number, String name, String status) {
        MarketplaceSupplier m = new MarketplaceSupplier();
        m.setSupplierNumber(number);
        m.setName(name);
        m.setStatus(status);
        return marketplaceSupplierRepository.save(m);
    }

    /** Shop A already stocks these barcodes, so an attach links them rather than creating items. */
    private void stockShopA(String... barcodes) {
        for (String barcode : barcodes) {
            saveItem(TENANT_A, "SKU-A-" + barcode, barcode, "Shop A " + barcode);
        }
    }

    private String saveItem(String tenantId, String sku, String barcode, String name) {
        return itemCatalogService.createItem(
                tenantId,
                new CreateItemRequest(sku, barcode, name, null, goodsTypeId(tenantId), null, null, null,
                        false, true, true,
                        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null),
                null
        ).body().id();
    }

    private String goodsTypeId(String tenantId) {
        return itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(tenantId).stream()
                .filter(t -> "goods".equals(t.getTypeKey()))
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private Supplier saveLocalSupplier(String tenantId, String name, String marketplaceSupplierId) {
        Supplier s = new Supplier();
        s.setBusinessId(tenantId);
        s.setName(name);
        s.setSupplierType("distributor");
        s.setStatus("active");
        s.setMarketplaceSupplierId(marketplaceSupplierId);
        return supplierRepository.save(s);
    }

    private String saveLink(String supplierId, String itemId) {
        SupplierProduct link = new SupplierProduct();
        link.setSupplierId(supplierId);
        link.setItemId(itemId);
        link.setActive(true);
        return supplierProductRepository.save(link).getId();
    }

    /** Barcodes of the products linked to a local supplier, sorted so assertions read plainly. */
    private List<String> linkedBarcodes(String tenantId, String localSupplierId) {
        return supplierProductRepository.listForSupplier(tenantId, localSupplierId).stream()
                .map(link -> itemRepository.findById(link.getItemId()).orElseThrow().getBarcode())
                .sorted()
                .toList();
    }

    private String userId(String tenantId) {
        return TENANT_A.equals(tenantId) ? ownerA.getId() : ownerB.getId();
    }

    private void cleanAll() {
        supplierProductRepository.deleteAll();
        supplierContactRepository.deleteAll();
        connectionRepository.deleteAll();
        identityIndexRepository.deleteAll();
        supplierRepository.deleteAll();
        priceOfferRepository.deleteAll();
        marketplaceProductRepository.deleteAll();
        marketplaceSupplierRepository.deleteAll();
        itemRepository.deleteAll();
        itemTypeRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        businessRepository.deleteAll();
    }

    private void insertBusiness(String id, String slug) {
        Business b = new Business();
        b.setId(id);
        b.setName("B " + id.substring(0, 8));
        b.setSlug(slug);
        businessRepository.save(b);
    }

    private static Permission perm(String id, String key, String desc) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(desc);
        return p;
    }

    private void saveRole(String id, String key, String name) {
        Role role = new Role();
        role.setId(id);
        role.setBusinessId(null);
        role.setRoleKey(key);
        role.setName(name);
        role.setSystem(true);
        roleRepository.save(role);
    }

    private void grant(String roleId, String permissionId) {
        RolePermission rp = new RolePermission();
        rp.setId(new RolePermission.Id(roleId, permissionId));
        rolePermissionRepository.save(rp);
    }

    private User user(String email, String tenant) {
        User u = new User();
        u.setBusinessId(tenant);
        u.setEmail(email);
        u.setName(email);
        u.setRoleId(ROLE_OWNER);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        return u;
    }
}
