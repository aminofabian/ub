package zelisline.ub.inventory.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.catalog.application.CatalogBootstrapService;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.api.dto.CreateItemRequest;
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
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.repository.RestockRunRepository;
import zelisline.ub.inventory.repository.RestockSuggestionRepository;
import zelisline.ub.platform.security.TestAuthenticationFilter;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.domain.SaleItem;
import zelisline.ub.sales.repository.SaleItemRepository;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierProduct;
import zelisline.ub.suppliers.repository.SupplierProductRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * End-to-end nightly restock digest: seed a branch with one item + batch + a sale in
 * the window + a primary supplier link, generate the run, then verify the suggestion
 * line, counts, idempotency, and the read endpoints.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class RestockDigestIT {

    private static final String TENANT = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee1";
    private static final String P_PATH_A_WRITE = "11111111-0000-0000-0000-000000000050";
    private static final String P_PATH_A_READ = "11111111-0000-0000-0000-000000000049";
    private static final String ROLE_OWNER = "22222222-0000-0000-0000-0000000000a1";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private BusinessRepository businessRepository;
    @Autowired private BranchRepository branchRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private ItemCatalogService itemCatalogService;
    @Autowired private CatalogBootstrapService catalogBootstrapService;
    @Autowired private ItemTypeRepository itemTypeRepository;
    @Autowired private InventoryBatchRepository inventoryBatchRepository;
    @Autowired private SaleRepository saleRepository;
    @Autowired private SaleItemRepository saleItemRepository;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private SupplierProductRepository supplierProductRepository;
    @Autowired private RestockRunRepository restockRunRepository;
    @Autowired private RestockSuggestionRepository restockSuggestionRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private RolePermissionRepository rolePermissionRepository;
    @Autowired private UserRepository userRepository;

    private User owner;
    private String branchId;
    private String itemId;
    private String supplierId;
    private LocalDate runDate;

    @BeforeEach
    void seed() throws Exception {
        restockSuggestionRepository.deleteAll();
        restockRunRepository.deleteAll();
        saleItemRepository.deleteAll();
        saleRepository.deleteAll();
        supplierProductRepository.deleteAll();
        supplierRepository.deleteAll();
        inventoryBatchRepository.deleteAll();
        itemRepository.deleteAll();
        branchRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("Digest Shop");
        b.setSlug("digest-shop");
        b.setSettings("{}");
        businessRepository.save(b);

        Branch br = new Branch();
        br.setBusinessId(TENANT);
        br.setName("Main");
        br.setRestockCoverDays(3);
        branchRepository.save(br);
        branchId = br.getId();

        Permission writePerm = new Permission();
        writePerm.setId(P_PATH_A_WRITE);
        writePerm.setPermissionKey("purchasing.path_a.write");
        writePerm.setDescription("Path A write");
        permissionRepository.save(writePerm);

        Permission readPerm = new Permission();
        readPerm.setId(P_PATH_A_READ);
        readPerm.setPermissionKey("purchasing.path_a.read");
        readPerm.setDescription("Path A read");
        permissionRepository.save(readPerm);
        Role ownerRole = new Role();
        ownerRole.setId(ROLE_OWNER);
        ownerRole.setBusinessId(null);
        ownerRole.setRoleKey("owner");
        ownerRole.setName("Owner");
        ownerRole.setSystem(true);
        roleRepository.save(ownerRole);
        grant(ROLE_OWNER, P_PATH_A_WRITE);
        grant(ROLE_OWNER, P_PATH_A_READ);
        owner = user("owner-digest@test", ROLE_OWNER);

        catalogBootstrapService.seedDefaultItemTypesIfMissing(TENANT);
        String goodsTypeId =
                itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(TENANT).getFirst().getId();
        itemId = itemCatalogService
                .createItem(
                        TENANT,
                        new CreateItemRequest(
                                "SKU-DIGEST", null, "Digest Item", null, goodsTypeId, null, null,
                                null, false, true, true, null, null, null, null, null,
                                null, null, null, null, null, false, null, null, null, null),
                        null)
                .body()
                .id();

        var item = itemRepository.findById(itemId).orElseThrow();
        item.setMinStockLevel(new BigDecimal("5"));
        item.setReorderLevel(new BigDecimal("5"));
        item.setBuyingPrice(new BigDecimal("52.0000"));
        itemRepository.save(item);

        InventoryBatch batch = new InventoryBatch();
        batch.setId(UUID.randomUUID().toString());
        batch.setBusinessId(TENANT);
        batch.setBranchId(branchId);
        batch.setItemId(itemId);
        batch.setBatchNumber("DIGEST-B1");
        batch.setSourceType("test");
        batch.setSourceId("source-digest-1");
        BigDecimal qty = new BigDecimal("4");
        batch.setInitialQuantity(qty);
        batch.setQuantityRemaining(qty);
        batch.setUnitCost(new BigDecimal("52.0000"));
        batch.setReceivedAt(Instant.parse("2026-04-01T12:00:00Z"));
        batch.setStatus(InventoryConstants.BATCH_STATUS_ACTIVE);
        inventoryBatchRepository.save(batch);

        Supplier supplier = new Supplier();
        supplier.setBusinessId(TENANT);
        supplier.setName("Digest Supplier");
        supplier.setStatus("active");
        supplierRepository.save(supplier);
        supplierId = supplier.getId();

        SupplierProduct link = new SupplierProduct();
        link.setSupplierId(supplierId);
        link.setItemId(itemId);
        link.setPrimaryLink(true);
        link.setDefaultCostPrice(new BigDecimal("52.0000"));
        link.setLeadTimeDays(1);
        link.setActive(true);
        supplierProductRepository.save(link);

        runDate = LocalDate.now(ZoneOffset.UTC);
        seedSaleYesterday(itemId);
    }

    @Test
    void generate_createsRunWithSuggestion() throws Exception {
        MvcResult generated = mockMvc.perform(
                        post("/api/v1/inventory/restock/runs/generate")
                                .param("branchId", branchId)
                                .header("X-Tenant-Id", TENANT)
                                .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                                .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode run = objectMapper.readTree(generated.getResponse().getContentAsString());
        assertThat(run.get("branchId").asText()).isEqualTo(branchId);
        assertThat(run.get("status").asText()).isEqualTo("generated");
        assertThat(run.get("trigger").asText()).isEqualTo("manual");
        assertThat(run.get("lineCount").asInt()).isEqualTo(1);
        assertThat(run.get("poLineCount").asInt()).isEqualTo(1);
        assertThat(run.get("padLineCount").asInt()).isZero();
        assertThat(run.get("suggestions")).hasSize(1);

        JsonNode line = run.get("suggestions").get(0);
        assertThat(line.get("itemId").asText()).isEqualTo(itemId);
        assertThat(line.get("target").asText()).isEqualTo("po");
        assertThat(line.get("supplierId").asText()).isEqualTo(supplierId);
        assertThat(line.get("reasonCode").asText()).contains("BELOW_MIN");
        assertThat(line.get("confidence").asText()).isEqualTo("low");
        assertThat(line.get("status").asText()).isEqualTo("pending");
        // on-hand 4 <= reorder 5 → BELOW_MIN; thin history fallback par = reorder*2 = 10 → suggested 6
        assertThat(line.get("suggestedQty").decimalValue()).isEqualByComparingTo("6");
        assertThat(line.get("onHand").decimalValue()).isEqualByComparingTo("4");
        assertThat(line.get("unitCost").decimalValue()).isEqualByComparingTo("52.0000");

        String runId = run.get("id").asText();
        mockMvc.perform(get("/api/v1/inventory/restock/runs/" + runId)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lineCount").value(1));
    }

    @Test
    void generate_isIdempotentPerDay() throws Exception {
        String firstId = generate().get("id").asText();
        String secondId = generate().get("id").asText();
        assertThat(secondId).isEqualTo(firstId);
    }

    @Test
    void latest_returnsRun() throws Exception {
        String runId = generate().get("id").asText();
        mockMvc.perform(get("/api/v1/inventory/restock/runs/latest")
                        .param("branchId", branchId)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(runId));
    }

    @Test
    void list_returnsRunRow() throws Exception {
        generate();
        mockMvc.perform(get("/api/v1/inventory/restock/runs")
                        .param("branchId", branchId)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].branchId").value(branchId))
                .andExpect(jsonPath("$[0].lineCount").value(1));
    }

    private JsonNode generate() throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/api/v1/inventory/restock/runs/generate")
                                .param("branchId", branchId)
                                .header("X-Tenant-Id", TENANT)
                                .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                                .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void seedSaleYesterday(String soldItemId) {
        LocalDate soldOn = runDate.minusDays(1);
        Instant soldAt = soldOn.atTime(14, 30).toInstant(ZoneOffset.UTC);

        Sale sale = new Sale();
        sale.setBusinessId(TENANT);
        sale.setBranchId(branchId);
        sale.setShiftId("shift-digest");
        sale.setStatus(SalesConstants.SALE_STATUS_COMPLETED);
        sale.setIdempotencyKey("idem-digest-yesterday");
        sale.setGrandTotal(new BigDecimal("100.00"));
        sale.setSoldBy(owner.getId());
        sale.setSoldAt(soldAt);
        saleRepository.save(sale);

        SaleItem line = new SaleItem();
        line.setSaleId(sale.getId());
        line.setLineIndex(0);
        line.setItemId(soldItemId);
        line.setQuantity(new BigDecimal("2"));
        line.setUnitPrice(new BigDecimal("50"));
        line.setLineTotal(new BigDecimal("100"));
        line.setUnitCost(new BigDecimal("30"));
        line.setCostTotal(new BigDecimal("60"));
        line.setProfit(new BigDecimal("40"));
        saleItemRepository.save(line);
    }

    private void grant(String roleId, String permissionId) {
        RolePermission rp = new RolePermission();
        rp.setId(new RolePermission.Id(roleId, permissionId));
        rolePermissionRepository.save(rp);
    }

    private User user(String email, String roleId) {
        User u = new User();
        u.setBusinessId(TENANT);
        u.setEmail(email);
        u.setName("U");
        u.setRoleId(roleId);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(u);
        return u;
    }
}
