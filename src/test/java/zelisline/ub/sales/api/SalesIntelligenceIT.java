package zelisline.ub.sales.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.catalog.api.dto.CreateItemRequest;
import zelisline.ub.catalog.application.CatalogBootstrapService;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.domain.Category;
import zelisline.ub.catalog.repository.CategoryRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.finance.repository.JournalEntryRepository;
import zelisline.ub.finance.repository.JournalLineRepository;
import zelisline.ub.finance.repository.LedgerAccountRepository;
import zelisline.ub.identity.domain.Permission;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.RolePermission;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.PermissionRepository;
import zelisline.ub.identity.repository.RolePermissionRepository;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.platform.security.TestAuthenticationFilter;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.purchasing.repository.StockMovementRepository;
import zelisline.ub.sales.api.dto.RevenueByCategoryRow;
import zelisline.ub.sales.repository.RefundLineRepository;
import zelisline.ub.sales.repository.RefundPaymentRepository;
import zelisline.ub.sales.repository.RefundRepository;
import zelisline.ub.sales.repository.SaleItemRepository;
import zelisline.ub.sales.repository.SalePaymentRepository;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.sales.repository.ShiftRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class SalesIntelligenceIT {

    private static final String TENANT = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    private static final String P_CAT_READ = "11111111-0000-0000-0000-000000000040";
    private static final String P_SO = "11111111-0000-0000-0000-000000000064";
    private static final String P_SC = "11111111-0000-0000-0000-000000000065";
    private static final String P_SR = "11111111-0000-0000-0000-000000000066";
    private static final String P_SELL = "11111111-0000-0000-0000-000000000067";
    private static final String P_REFUND = "11111111-0000-0000-0000-000000000070";
    private static final String P_INTEL = "11111111-0000-0000-0000-000000000073";
    private static final String ROLE_ID = "22222222-0000-0000-0000-0000000000ee";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private BranchRepository branchRepository;

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
    private CategoryRepository categoryRepository;

    @Autowired
    private InventoryBatchRepository inventoryBatchRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    @Autowired
    private SaleRepository saleRepository;

    @Autowired
    private SaleItemRepository saleItemRepository;

    @Autowired
    private SalePaymentRepository salePaymentRepository;

    @Autowired
    private RefundPaymentRepository refundPaymentRepository;

    @Autowired
    private RefundLineRepository refundLineRepository;

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private JournalLineRepository journalLineRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    private CatalogBootstrapService catalogBootstrapService;

    @Autowired
    private ItemCatalogService itemCatalogService;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User user;
    private String branchId;
    private String goodsTypeId;
    private String categoryDrinksId;
    private String categorySnacksId;
    private String itemDrinksId;
    private String itemSnacksId;

    @BeforeEach
    void seed() {
        refundPaymentRepository.deleteAll();
        refundLineRepository.deleteAll();
        refundRepository.deleteAll();
        salePaymentRepository.deleteAll();
        saleItemRepository.deleteAll();
        saleRepository.deleteAll();
        journalLineRepository.deleteAll();
        journalEntryRepository.deleteAll();
        ledgerAccountRepository.deleteAll();
        stockMovementRepository.deleteAll();
        shiftRepository.deleteAll();
        inventoryBatchRepository.deleteAll();
        itemRepository.deleteAll();
        categoryRepository.deleteAll();
        itemTypeRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        branchRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("Intel Sale Shop");
        b.setSlug("intel-sale-shop");
        businessRepository.save(b);

        Branch br = new Branch();
        br.setBusinessId(TENANT);
        br.setName("Main");
        branchRepository.save(br);
        branchId = br.getId();

        catalogBootstrapService.seedDefaultItemTypesIfMissing(TENANT);
        goodsTypeId = itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(TENANT).getFirst().getId();

        for (Permission p : List.of(
                perm(P_CAT_READ, "catalog.items.read", "cr"),
                perm(P_SO, "shifts.open", "so"),
                perm(P_SC, "shifts.close", "sc"),
                perm(P_SR, "shifts.read", "sr"),
                perm(P_SELL, "sales.sell", "ss"),
                perm(P_REFUND, "sales.refund.create", "srfd"),
                perm(P_INTEL, "sales.intelligence.read", "si"))) {
            permissionRepository.save(p);
        }

        Role r = new Role();
        r.setId(ROLE_ID);
        r.setBusinessId(null);
        r.setRoleKey("sales_intel_it");
        r.setName("Sales Intel IT");
        r.setSystem(true);
        roleRepository.save(r);

        for (String pid : List.of(P_CAT_READ, P_SO, P_SC, P_SR, P_SELL, P_REFUND, P_INTEL)) {
            RolePermission rp = new RolePermission();
            rp.setId(new RolePermission.Id(ROLE_ID, pid));
            rolePermissionRepository.save(rp);
        }

        user = new User();
        user.setBusinessId(TENANT);
        user.setEmail("sales-intel@test");
        user.setName("Reporter");
        user.setRoleId(ROLE_ID);
        user.setBranchId(branchId);
        user.setStatus(UserStatus.ACTIVE);
        user.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(user);

        Category drinks = new Category();
        drinks.setBusinessId(TENANT);
        drinks.setName("Drinks");
        drinks.setSlug("drinks");
        drinks.setPosition(0);
        categoryDrinksId = categoryRepository.save(drinks).getId();

        Category snacks = new Category();
        snacks.setBusinessId(TENANT);
        snacks.setName("Snacks");
        snacks.setSlug("snacks");
        snacks.setPosition(1);
        categorySnacksId = categoryRepository.save(snacks).getId();

        itemDrinksId = itemCatalogService.createItem(
                        TENANT,
                        new CreateItemRequest(
                                "SKU-DRINK", null, "Cola", null, goodsTypeId, categoryDrinksId, null, null,
                                false, true, true,
                                null, null, null, null, null, null, null, null, null, true, null),
                        null)
                .body()
                .id();

        itemSnacksId = itemCatalogService.createItem(
                        TENANT,
                        new CreateItemRequest(
                                "SKU-SNACK", null, "Chips", null, goodsTypeId, categorySnacksId, null, null,
                                false, true, true,
                                null, null, null, null, null, null, null, null, null, true, null),
                        null)
                .body()
                .id();

        Instant batchInstant = Instant.now().minusSeconds(3600);
        inventoryBatchRepository.save(batch("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1", itemDrinksId, batchInstant));
        inventoryBatchRepository.save(batch("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2", itemSnacksId, batchInstant));

        var drinkRow = itemRepository.findById(itemDrinksId).orElseThrow();
        drinkRow.setCurrentStock(new BigDecimal("50"));
        itemRepository.save(drinkRow);

        var snackRow = itemRepository.findById(itemSnacksId).orElseThrow();
        snackRow.setCurrentStock(new BigDecimal("50"));
        itemRepository.save(snackRow);
    }

    @Test
    void revenueByCategory_splitsSaleLinesByCatalogCategory() throws Exception {
        openShift(new BigDecimal("500.00"));

        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        String body = """
                {"branchId":"%s","lines":[\
                {"itemId":"%s","quantity":1,"unitPrice":60},\
                {"itemId":"%s","quantity":2,"unitPrice":20}],\
                "payments":[{"method":"cash","amount":100}]}\
                """
                .formatted(branchId, itemDrinksId, itemSnacksId);

        mockMvc.perform(post("/api/v1/sales")
                        .contentType(APPLICATION_JSON)
                        .content(body)
                        .header("Idempotency-Key", "idem-" + UUID.randomUUID())
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_ID))
                .andExpect(status().isCreated());

        MvcResult report = mockMvc.perform(get("/api/v1/sales/intelligence/revenue-by-category")
                        .param("from", today.minusDays(2).toString())
                        .param("to", today.plusDays(2).toString())
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_ID))
                .andExpect(status().isOk())
                .andReturn();

        List<RevenueByCategoryRow> rows =
                objectMapper.readValue(report.getResponse().getContentAsString(), new TypeReference<>() {});
        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().categoryName()).isEqualTo("Drinks");
        assertThat(rows.getFirst().netRevenue()).isEqualByComparingTo(new BigDecimal("60.00"));
        assertThat(rows.get(1).categoryName()).isEqualTo("Snacks");
        assertThat(rows.get(1).netRevenue()).isEqualByComparingTo(new BigDecimal("40.00"));
    }

    @Test
    void revenueByCategory_netSubtractsRefundsInWindow() throws Exception {
        openShift(new BigDecimal("200.00"));

        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        String saleBody = """
                {"branchId":"%s","lines":[{"itemId":"%s","quantity":10,"unitPrice":10}],\
                "payments":[{"method":"cash","amount":100}]}\
                """
                .formatted(branchId, itemDrinksId);

        MvcResult saleRes = mockMvc.perform(post("/api/v1/sales")
                        .contentType(APPLICATION_JSON)
                        .content(saleBody)
                        .header("Idempotency-Key", "idem-sale-" + UUID.randomUUID())
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_ID))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode saleJson = objectMapper.readTree(saleRes.getResponse().getContentAsString());
        String saleId = saleJson.get("id").asText();
        String saleItemId = saleJson.get("items").get(0).get("id").asText();

        String refundBody =
                """
                        {"lines":[{"saleItemId":"%s","quantity":4}],\
                        "payments":[{"method":"cash","amount":40}],"reason":"partial"}\
                        """
                        .formatted(saleItemId);

        mockMvc.perform(post("/api/v1/sales/{saleId}/refund", saleId)
                        .contentType(APPLICATION_JSON)
                        .content(refundBody)
                        .header("Idempotency-Key", "idem-refund-" + UUID.randomUUID())
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_ID))
                .andExpect(status().isOk());

        MvcResult report = mockMvc.perform(get("/api/v1/sales/intelligence/revenue-by-category")
                        .param("from", today.minusDays(2).toString())
                        .param("to", today.plusDays(2).toString())
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_ID))
                .andExpect(status().isOk())
                .andReturn();

        List<RevenueByCategoryRow> rows =
                objectMapper.readValue(report.getResponse().getContentAsString(), new TypeReference<>() {});
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().categoryId()).isEqualTo(categoryDrinksId);
        assertThat(rows.getFirst().netRevenue()).isEqualByComparingTo(new BigDecimal("60.00"));
    }

    private void openShift(BigDecimal openingCash) throws Exception {
        mockMvc.perform(post("/api/v1/shifts/open")
                        .contentType(APPLICATION_JSON)
                        .content("{\"branchId\":\"%s\",\"openingCash\":%s}".formatted(branchId, openingCash.toPlainString()))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_ID))
                .andExpect(status().isCreated());
    }

    private InventoryBatch batch(String id, String itemId, Instant receivedAt) {
        InventoryBatch b = new InventoryBatch();
        b.setId(id);
        b.setBusinessId(TENANT);
        b.setBranchId(branchId);
        b.setItemId(itemId);
        b.setSupplierId(null);
        b.setBatchNumber("BN-" + id.substring(0, 8));
        b.setSourceType("test");
        b.setSourceId(UUID.randomUUID().toString());
        BigDecimal qty = new BigDecimal("50");
        b.setInitialQuantity(qty);
        b.setQuantityRemaining(qty);
        b.setUnitCost(new BigDecimal("3.00"));
        b.setReceivedAt(receivedAt);
        b.setExpiryDate(LocalDate.of(2027, 1, 1));
        b.setStatus("active");
        return b;
    }

    private static Permission perm(String id, String key, String desc) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(desc);
        return p;
    }
}
