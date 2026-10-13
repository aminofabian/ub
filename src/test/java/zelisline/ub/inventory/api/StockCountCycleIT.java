package zelisline.ub.inventory.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.catalog.api.dto.CreateItemRequest;
import zelisline.ub.catalog.application.CatalogBootstrapService;
import zelisline.ub.catalog.application.ItemCatalogService;
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
import zelisline.ub.inventory.repository.StockAdjustmentRequestRepository;
import zelisline.ub.inventory.repository.StockCountCycleRepository;
import zelisline.ub.inventory.repository.StockTakeLineRepository;
import zelisline.ub.inventory.repository.StockTakeSessionRepository;
import zelisline.ub.platform.security.TestAuthenticationFilter;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.purchasing.repository.StockMovementRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class StockCountCycleIT {

    private static final String TENANT = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    private static final String BASE = "/api/v1/inventory/stock-take/count-cycles";
    private static final String P_ST_R = "11111111-0000-0000-0000-000000000157";
    private static final String P_ST_RUN = "11111111-0000-0000-0000-000000000158";
    private static final String P_ST_APP = "11111111-0000-0000-0000-000000000159";
    private static final String ROLE_OWNER = "22222222-0000-0000-0000-000000000166";
    private static final String ROLE_MANAGER = "22222222-0000-0000-0000-000000000167";
    private static final String STOCKED_BATCH_ID = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeea1";
    private static final int CATALOG_SIZE = 12;
    private static final int BATCH_SIZE = 10;

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
    @Autowired private StockCountCycleRepository stockCountCycleRepository;
    @Autowired private StockAdjustmentRequestRepository stockAdjustmentRequestRepository;
    @Autowired private LedgerBootstrapService ledgerBootstrapService;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User owner;
    private User manager;
    private String branchId;
    private final List<String> itemIds = new ArrayList<>();

    @BeforeEach
    void seed() {
        clearTables();
        Business b = new Business();
        b.setId(TENANT);
        b.setName("Full Count Shop");
        b.setSlug("full-count-shop");
        b.setTimezone("Africa/Nairobi");
        businessRepository.save(b);
        ledgerBootstrapService.ensureStandardAccounts(TENANT);

        Branch br = new Branch();
        br.setBusinessId(TENANT);
        br.setName("Main");
        branchRepository.save(br);
        branchId = br.getId();

        seedRoles();
        owner = user("owner-fc@test", ROLE_OWNER);
        manager = user("mgr-fc@test", ROLE_MANAGER);
        seedCatalog();
    }

    @Test
    void cycle_countsInBatches_returnsSkippedItems_andAppliesApprovedCounts() throws Exception {
        JsonNode cycle = send(post(BASE).contentType(APPLICATION_JSON)
                .content("{\"branchId\":\"" + branchId + "\",\"batchSize\":" + BATCH_SIZE + "}"), owner, ROLE_OWNER, 201);
        assertThat(cycle.get("totalItems").asInt()).isEqualTo(CATALOG_SIZE);
        String cycleId = cycle.get("cycleId").asText();

        JsonNode batch = send(post(BASE + "/{id}/batches", cycleId), manager, ROLE_MANAGER, 200);
        assertThat(batch.get("lines")).hasSize(BATCH_SIZE);
        assertThat(batch.get("lines").get(0).get("systemQty").isNull()).isTrue();
        String sessionId = batch.get("sessionId").asText();

        JsonNode resumed = send(post(BASE + "/{id}/batches", cycleId), manager, ROLE_MANAGER, 200);
        assertThat(resumed.get("sessionId").asText()).isEqualTo(sessionId);

        String skippedItemId = countAllButLast(batch, sessionId);
        JsonNode afterFinish = send(post(BASE + "/batches/{id}/finish", sessionId), manager, ROLE_MANAGER, 200);
        assertThat(afterFinish.get("countedItems").asInt()).isEqualTo(BATCH_SIZE - 1);
        assertThat(afterFinish.get("remainingItems").asInt()).isEqualTo(CATALOG_SIZE - BATCH_SIZE + 1);
        assertThat(afterFinish.get("activeBatch").isNull()).isTrue();
        assertThat(afterFinish.get("countedToday").asBoolean()).isTrue();

        JsonNode second = send(post(BASE + "/{id}/batches", cycleId), manager, ROLE_MANAGER, 200);
        assertThat(second.get("batchNumber").asInt()).isEqualTo(2);
        assertThat(second.get("lines")).hasSize(CATALOG_SIZE - BATCH_SIZE + 1);
        assertThat(second.get("lines").get(0).get("itemId").asText()).isEqualTo(skippedItemId);

        JsonNode applied = send(post(BASE + "/batches/{id}/apply", sessionId), owner, ROLE_OWNER, 200);
        assertThat(applied.get("lines").get(0).get("status").asText())
                .isEqualTo(InventoryConstants.STOCKTAKE_LINE_CONFIRMED);
        BigDecimal remaining = inventoryBatchRepository.findById(STOCKED_BATCH_ID).orElseThrow()
                .getQuantityRemaining();
        assertThat(remaining).isEqualByComparingTo("7");
    }

    @Test
    void home_reportsCatalogSizeBeforeAnyCycle() throws Exception {
        JsonNode home = send(get(BASE + "/home").param("branchId", branchId), manager, ROLE_MANAGER, 200);
        assertThat(home.get("cycle").isNull()).isTrue();
        assertThat(home.get("catalogSize").asInt()).isEqualTo(CATALOG_SIZE);
    }

    @Test
    void startCycle_requiresApprovePermission() throws Exception {
        mockMvc.perform(authed(post(BASE).contentType(APPLICATION_JSON)
                        .content("{\"branchId\":\"" + branchId + "\"}"), manager, ROLE_MANAGER))
                .andExpect(status().isForbidden());
    }

    /** Counts every line except the last; the first line (stocked item) is counted as 7. */
    private String countAllButLast(JsonNode batch, String sessionId) throws Exception {
        JsonNode lines = batch.get("lines");
        for (int i = 0; i < lines.size() - 1; i++) {
            String qty = lines.get(i).get("itemId").asText().equals(itemIds.getFirst()) ? "7" : "0";
            send(patch(BASE + "/batches/{s}/lines/{l}", sessionId, lines.get(i).get("lineId").asText())
                    .contentType(APPLICATION_JSON)
                    .content("{\"countedQty\":" + qty + "}"), manager, ROLE_MANAGER, 200);
        }
        return lines.get(lines.size() - 1).get("itemId").asText();
    }

    private JsonNode send(MockHttpServletRequestBuilder req, User user, String roleId, int expected)
            throws Exception {
        String body = mockMvc.perform(authed(req, user, roleId))
                .andExpect(status().is(expected))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body);
    }

    private static MockHttpServletRequestBuilder authed(
            MockHttpServletRequestBuilder req, User user, String roleId) {
        return req.header("X-Tenant-Id", TENANT)
                .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                .header(TestAuthenticationFilter.HEADER_ROLE_ID, roleId);
    }

    private void clearTables() {
        stockMovementRepository.deleteAll();
        inventoryBatchRepository.deleteAll();
        stockAdjustmentRequestRepository.deleteAll();
        stockTakeLineRepository.deleteAll();
        stockTakeSessionRepository.deleteAll();
        stockCountCycleRepository.deleteAll();
        itemRepository.deleteAll();
        itemTypeRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        branchRepository.deleteAll();
        businessRepository.deleteAll();
        itemIds.clear();
    }

    private void seedRoles() {
        for (Permission p : List.of(
                perm(P_ST_R, "stocktake.read"),
                perm(P_ST_RUN, "stocktake.run"),
                perm(P_ST_APP, "stocktake.approve"))) {
            permissionRepository.save(p);
        }
        roleRepository.save(role("Owner", ROLE_OWNER, "owner"));
        roleRepository.save(role("Manager", ROLE_MANAGER, "manager"));
        List.of(P_ST_R, P_ST_RUN, P_ST_APP).forEach(pid -> grant(ROLE_OWNER, pid));
        List.of(P_ST_R, P_ST_RUN).forEach(pid -> grant(ROLE_MANAGER, pid));
    }

    private void seedCatalog() {
        catalogBootstrapService.seedDefaultItemTypesIfMissing(TENANT);
        String goodsTypeId =
                itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(TENANT).getFirst().getId();
        for (int i = 0; i < CATALOG_SIZE; i++) {
            itemIds.add(createItem(goodsTypeId, String.format("Item %02d", i), "SKU-FC-" + i));
        }
        InventoryBatch batch = new InventoryBatch();
        batch.setId(STOCKED_BATCH_ID);
        batch.setBusinessId(TENANT);
        batch.setBranchId(branchId);
        batch.setItemId(itemIds.getFirst());
        batch.setBatchNumber("FC-B1");
        batch.setSourceType("test");
        batch.setSourceId("source-fc-1");
        batch.setInitialQuantity(new BigDecimal("10"));
        batch.setQuantityRemaining(new BigDecimal("10"));
        batch.setUnitCost(new BigDecimal("2.5000"));
        batch.setReceivedAt(Instant.parse("2026-04-01T12:00:00Z"));
        batch.setStatus(InventoryConstants.BATCH_STATUS_ACTIVE);
        inventoryBatchRepository.save(batch);
    }

    private String createItem(String goodsTypeId, String name, String sku) {
        return itemCatalogService
                .createItem(
                        TENANT,
                        new CreateItemRequest(
                                sku, null, name, null, goodsTypeId, null, null, null,
                                false, true, true, null, null, null, null, null, null,
                                null, null, null, null, false, null, null, null, null),
                        null)
                .body()
                .id();
    }

    private static Permission perm(String id, String key) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(key);
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
