package zelisline.ub.inventory.api;

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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.catalog.api.dto.CreateItemRequest;
import zelisline.ub.catalog.application.CatalogBootstrapService;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.finance.application.LedgerBootstrapService;
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
import zelisline.ub.inventory.application.DailyStockAuditService;
import zelisline.ub.inventory.repository.DailyStockAuditItemRepository;
import zelisline.ub.inventory.repository.DailyStockAuditRepository;
import zelisline.ub.inventory.repository.StockTakeLineRepository;
import zelisline.ub.inventory.repository.StockTakeRestockItemRepository;
import zelisline.ub.inventory.repository.StockTakeSessionRepository;
import zelisline.ub.platform.security.TestAuthenticationFilter;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.purchasing.repository.StockMovementRepository;
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
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class StockTakeRestockIT {

    private static final String TENANT = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    private static final String P_ST_R = "11111111-0000-0000-0000-000000000057";
    private static final String P_ST_RUN = "11111111-0000-0000-0000-000000000058";
    private static final String P_ST_APP = "11111111-0000-0000-0000-000000000059";
    private static final String ROLE_OWNER = "22222222-0000-0000-0000-000000000066";
    private static final String ROLE_MANAGER = "22222222-0000-0000-0000-000000000067";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private BusinessRepository businessRepository;
    @Autowired private BranchRepository branchRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private RolePermissionRepository rolePermissionRepository;
    @Autowired private ItemTypeRepository itemTypeRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private CatalogBootstrapService catalogBootstrapService;
    @Autowired private ItemCatalogService itemCatalogService;
    @Autowired private InventoryBatchRepository inventoryBatchRepository;
    @Autowired private StockMovementRepository stockMovementRepository;
    @Autowired private StockTakeSessionRepository stockTakeSessionRepository;
    @Autowired private StockTakeLineRepository stockTakeLineRepository;
    @Autowired private StockTakeRestockItemRepository stockTakeRestockItemRepository;
    @Autowired private DailyStockAuditItemRepository dailyStockAuditItemRepository;
    @Autowired private DailyStockAuditRepository dailyStockAuditRepository;
    @Autowired private SaleRepository saleRepository;
    @Autowired private SaleItemRepository saleItemRepository;
    @Autowired private DailyStockAuditService dailyStockAuditService;
    @Autowired private LedgerBootstrapService ledgerBootstrapService;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private SupplierProductRepository supplierProductRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User owner;
    private User manager;
    private String branchId;
    private String itemId;
    private String supplierId;
    private LocalDate auditDate;

    @BeforeEach
    void seed() throws Exception {
        auditDate = LocalDate.now(ZoneOffset.UTC);
        stockTakeRestockItemRepository.deleteAll();
        stockMovementRepository.deleteAll();
        inventoryBatchRepository.deleteAll();
        stockTakeLineRepository.deleteAll();
        stockTakeSessionRepository.deleteAll();
        dailyStockAuditItemRepository.deleteAllInBatch();
        dailyStockAuditRepository.deleteAllInBatch();
        supplierProductRepository.deleteAll();
        supplierRepository.deleteAll();
        saleItemRepository.deleteAll();
        saleRepository.deleteAll();
        itemRepository.deleteAll();
        itemTypeRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        branchRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("Restock Shop");
        b.setSlug("restock-shop");
        b.setSettings("{}");
        businessRepository.save(b);
        ledgerBootstrapService.ensureStandardAccounts(TENANT);

        Branch br = new Branch();
        br.setBusinessId(TENANT);
        br.setName("Main");
        branchRepository.save(br);
        branchId = br.getId();

        catalogBootstrapService.seedDefaultItemTypesIfMissing(TENANT);
        String goodsTypeId =
                itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(TENANT).getFirst().getId();

        for (Permission p :
                List.of(
                        perm(P_ST_R, "stocktake.read", "str"),
                        perm(P_ST_RUN, "stocktake.run", "stn"),
                        perm(P_ST_APP, "stocktake.approve", "sta"))) {
            permissionRepository.save(p);
        }

        Role ownerRole = role("Owner", ROLE_OWNER, "owner");
        roleRepository.save(ownerRole);
        for (String pid : List.of(P_ST_R, P_ST_RUN, P_ST_APP)) {
            grant(ROLE_OWNER, pid);
        }

        Role mgrRole = role("Manager", ROLE_MANAGER, "manager");
        roleRepository.save(mgrRole);
        for (String pid : List.of(P_ST_R, P_ST_RUN)) {
            grant(ROLE_MANAGER, pid);
        }

        owner = user("owner-rst@test", ROLE_OWNER);
        manager = user("mgr-rst@test", ROLE_MANAGER);

        itemId =
                itemCatalogService
                        .createItem(
                                TENANT,
                                new CreateItemRequest(
                                        "SKU-RST",
                                        null,
                                        "Restock Item",
                                        null,
                                        goodsTypeId,
                                        null,
                                        null,
                                        null,
                                        false,
                                        true,
                                        true,
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
                                        false,
                                        null,
                                        null,
                                        null,
                                        null),
                                null)
                        .body()
                        .id();

        Item item = itemRepository.findById(itemId).orElseThrow();
        item.setCurrentStock(new BigDecimal("10"));
        itemRepository.save(item);

        InventoryBatch batch = new InventoryBatch();
        batch.setId("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeea1");
        batch.setBusinessId(TENANT);
        batch.setBranchId(branchId);
        batch.setItemId(itemId);
        batch.setBatchNumber("RST-B1");
        batch.setSourceType("test");
        batch.setSourceId("source-rst-1");
        BigDecimal q10 = new BigDecimal("10");
        batch.setInitialQuantity(q10);
        batch.setQuantityRemaining(q10);
        batch.setUnitCost(new BigDecimal("2.5000"));
        batch.setReceivedAt(Instant.parse("2026-04-01T12:00:00Z"));
        batch.setStatus(InventoryConstants.BATCH_STATUS_ACTIVE);
        inventoryBatchRepository.save(batch);

        Supplier supplier = new Supplier();
        supplier.setBusinessId(TENANT);
        supplier.setName("Restock Supplier");
        supplier.setStatus("active");
        supplierRepository.save(supplier);
        supplierId = supplier.getId();

        SupplierProduct link = new SupplierProduct();
        link.setSupplierId(supplierId);
        link.setItemId(itemId);
        link.setPrimaryLink(true);
        link.setDefaultCostPrice(new BigDecimal("52.0000"));
        link.setActive(true);
        supplierProductRepository.save(link);

        seedSaleYesterday(itemId);
        dailyStockAuditService.generateForBranchIfAbsent(TENANT, branchId, auditDate, "system");
    }

    @Test
    void restockSuggestion_upsertAndReview() throws Exception {
        JsonNode session = startMorningSession(manager, ROLE_MANAGER);
        String sessionId = session.get("sessionId").asText();
        String lineId = session.get("lines").get(0).get("lineId").asText();

        mockMvc.perform(
                        get(
                                        "/api/v1/inventory/stock-take/restock-items/daily-audit/sessions/"
                                                + sessionId
                                                + "/lines/"
                                                + lineId
                                                + "/supplier-options")
                                .header("X-Tenant-Id", TENANT)
                                .header(TestAuthenticationFilter.HEADER_USER_ID, manager.getId())
                                .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_MANAGER))
                .andExpect(status().isOk());

        MvcResult create =
                mockMvc.perform(
                                post(
                                                "/api/v1/inventory/stock-take/restock-items/daily-audit/sessions/"
                                                        + sessionId)
                                        .contentType(APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "lineId": "%s",
                                                  "supplierId": "%s",
                                                  "suggestedQty": 12,
                                                  "note": "Low shelf"
                                                }
                                                """
                                                        .formatted(lineId, supplierId))
                                        .header("X-Tenant-Id", TENANT)
                                        .header(TestAuthenticationFilter.HEADER_USER_ID, manager.getId())
                                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_MANAGER))
                        .andExpect(status().isCreated())
                        .andReturn();

        JsonNode created = objectMapper.readTree(create.getResponse().getContentAsString());
        assertThat(created.get("status").asText()).isEqualTo("pending");
        assertThat(created.get("buyingPrice").decimalValue()).isEqualByComparingTo("52.0000");
        String restockItemId = created.get("id").asText();

        MvcResult review =
                mockMvc.perform(
                                get("/api/v1/inventory/stock-take/restock-items/review")
                                        .param("branchId", branchId)
                                        .param("auditDate", auditDate.toString())
                                        .param("status", "pending")
                                        .header("X-Tenant-Id", TENANT)
                                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                        .andExpect(status().isOk())
                        .andReturn();

        JsonNode reviewBody = objectMapper.readTree(review.getResponse().getContentAsString());
        assertThat(reviewBody.get("groups")).isNotEmpty();
        assertThat(reviewBody.get("groups").get(0).get("items").get(0).get("id").asText())
                .isEqualTo(restockItemId);

        mockMvc.perform(
                        post(
                                        "/api/v1/inventory/stock-take/restock-items/"
                                                + restockItemId
                                                + "/approve")
                                .header("X-Tenant-Id", TENANT)
                                .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                                .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk());
    }

    private JsonNode startMorningSession(User user, String roleId) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/inventory/stock-take/daily-audits/sessions")
                                        .contentType(APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "branchId": "%s",
                                                  "sessionType": "morning",
                                                  "auditDate": "%s"
                                                }
                                                """
                                                        .formatted(branchId, auditDate))
                                        .header("X-Tenant-Id", TENANT)
                                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, roleId))
                        .andExpect(status().isCreated())
                        .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void seedSaleYesterday(String soldItemId) {
        LocalDate soldOn = auditDate.minusDays(1);
        Instant soldAt = soldOn.atTime(14, 30).toInstant(ZoneOffset.UTC);

        Sale sale = new Sale();
        sale.setBusinessId(TENANT);
        sale.setBranchId(branchId);
        sale.setShiftId("shift-rst");
        sale.setStatus(SalesConstants.SALE_STATUS_COMPLETED);
        sale.setIdempotencyKey("idem-rst-yesterday");
        sale.setGrandTotal(new BigDecimal("100.00"));
        sale.setSoldBy(owner.getId());
        sale.setSoldAt(soldAt);
        saleRepository.save(sale);

        SaleItem line = new SaleItem();
        line.setSaleId(sale.getId());
        line.setLineIndex(0);
        line.setItemId(soldItemId);
        line.setBatchId("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeea1");
        line.setQuantity(new BigDecimal("2"));
        line.setUnitPrice(new BigDecimal("50"));
        line.setLineTotal(new BigDecimal("100"));
        line.setUnitCost(new BigDecimal("30"));
        line.setCostTotal(new BigDecimal("60"));
        line.setProfit(new BigDecimal("40"));
        saleItemRepository.save(line);
    }

    private static Permission perm(String id, String key, String desc) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(desc);
        return p;
    }

    private static Role role(String name, String id, String key) {
        Role r = new Role();
        r.setId(id);
        r.setBusinessId(null);
        r.setRoleKey(key);
        r.setName(name);
        r.setSystem(true);
        return r;
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
