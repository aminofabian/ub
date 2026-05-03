package zelisline.ub.inventory.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.catalog.api.dto.CreateItemRequest;
import zelisline.ub.catalog.application.CatalogBootstrapService;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.domain.Item;
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
import zelisline.ub.inventory.repository.StockAdjustmentRequestRepository;
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
class InventorySlice4IT {

    private static final String TENANT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String P_READ = "11111111-0000-0000-0000-000000000040";
    private static final String P_ST_R = "11111111-0000-0000-0000-000000000057";
    private static final String P_ST_RUN = "11111111-0000-0000-0000-000000000058";
    private static final String P_ST_APP = "11111111-0000-0000-0000-000000000059";
    private static final String ROLE_OWNER = "22222222-0000-0000-0000-000000000066";
    private static final String ROLE_MANAGER = "22222222-0000-0000-0000-000000000067";

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
    private CatalogBootstrapService catalogBootstrapService;
    @Autowired
    private ItemCatalogService itemCatalogService;
    @Autowired
    private InventoryBatchRepository inventoryBatchRepository;
    @Autowired
    private StockMovementRepository stockMovementRepository;
    @Autowired
    private StockAdjustmentRequestRepository stockAdjustmentRequestRepository;
    @Autowired
    private StockTakeSessionRepository stockTakeSessionRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User owner;
    private User manager;
    private String branchId;
    private String itemId;
    private String sessionId;
    private String takeLineId;
    private String batchId;

    @BeforeEach
    void seed() throws Exception {
        stockMovementRepository.deleteAll();
        inventoryBatchRepository.deleteAll();
        stockAdjustmentRequestRepository.deleteAll();
        stockTakeSessionRepository.deleteAll();
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
        b.setName("Stocktake Shop");
        b.setSlug("stocktake-shop");
        b.setSettings("{}");
        businessRepository.save(b);

        Branch br = new Branch();
        br.setBusinessId(TENANT);
        br.setName("Main");
        branchRepository.save(br);
        branchId = br.getId();

        catalogBootstrapService.seedDefaultItemTypesIfMissing(TENANT);
        String goodsTypeId = itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(TENANT).getFirst().getId();

        for (Permission p : List.of(
                perm(P_READ, "catalog.items.read", "cat"),
                perm(P_ST_R, "stocktake.read", "str"),
                perm(P_ST_RUN, "stocktake.run", "stn"),
                perm(P_ST_APP, "stocktake.approve", "sta")
        )) {
            permissionRepository.save(p);
        }

        Role ownerRole = role("Owner role", ROLE_OWNER, "owner");
        roleRepository.save(ownerRole);
        for (String pid : List.of(P_READ, P_ST_R, P_ST_RUN, P_ST_APP)) {
            grant(ROLE_OWNER, pid);
        }

        Role mgrRole = role("Manager role", ROLE_MANAGER, "manager");
        roleRepository.save(mgrRole);
        for (String pid : List.of(P_READ, P_ST_R, P_ST_RUN)) {
            grant(ROLE_MANAGER, pid);
        }

        owner = user("owner-st@test", ROLE_OWNER);
        manager = user("mgr-st@test", ROLE_MANAGER);

        itemId = itemCatalogService.createItem(
                TENANT,
                new CreateItemRequest(
                        "SKU-ST", null, "Stocktake Item", null, goodsTypeId, null, null, null,
                        false, true, true,
                        null, null, null, null, null, null, null, null, null, false, null
                ),
                null
        ).body().id();

        Item item = itemRepository.findById(itemId).orElseThrow();
        item.setCurrentStock(new BigDecimal("10"));
        itemRepository.save(item);

        batchId = "cccccccc-cccc-cccc-cccc-cccccccccca1";
        InventoryBatch batch = new InventoryBatch();
        batch.setId(batchId);
        batch.setBusinessId(TENANT);
        batch.setBranchId(branchId);
        batch.setItemId(itemId);
        batch.setSupplierId(null);
        batch.setBatchNumber("ST-B1");
        batch.setSourceType("test");
        batch.setSourceId(UUID.randomUUID().toString());
        BigDecimal q10 = new BigDecimal("10");
        batch.setInitialQuantity(q10);
        batch.setQuantityRemaining(q10);
        batch.setUnitCost(new BigDecimal("2.5000"));
        batch.setReceivedAt(Instant.parse("2026-04-01T12:00:00Z"));
        batch.setStatus(InventoryConstants.BATCH_STATUS_ACTIVE);
        inventoryBatchRepository.save(batch);

        JsonNode sessionJson = startSessionJson();
        sessionId = sessionJson.get("id").asText();
        takeLineId = sessionJson.get("lines").get(0).get("id").asText();
    }

    @Test
    void approveSurplus_increasesStockUsingBatchAverageCost() throws Exception {
        patchCount("12");
        String requestId = closeAndGetFirstRequestId();

        mockMvc.perform(post(
                        "/api/v1/inventory/stock-take/sessions/{sessionId}/adjustment-requests/{requestId}/approve",
                        sessionId,
                        requestId
                )
                        .contentType(APPLICATION_JSON)
                        .content("{}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isNoContent());

        Item item = itemRepository.findById(itemId).orElseThrow();
        assertThat(item.getCurrentStock().setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo("12");
        assertThat(stockMovementRepository.findAll().stream()
                .filter(m -> InventoryConstants.REF_STOCK_ADJUSTMENT_REQUEST.equals(m.getReferenceType()))
                .count()).isZero();
    }

    @Test
    void approveShortage_decrementsStock() throws Exception {
        patchCount("7");
        String requestId = closeAndGetFirstRequestId();

        mockMvc.perform(post(
                        "/api/v1/inventory/stock-take/sessions/{sessionId}/adjustment-requests/{requestId}/approve",
                        sessionId,
                        requestId
                )
                        .contentType(APPLICATION_JSON)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isNoContent());

        Item item = itemRepository.findById(itemId).orElseThrow();
        assertThat(item.getCurrentStock().setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo("7");
        InventoryBatch batch = inventoryBatchRepository.findById(batchId).orElseThrow();
        assertThat(batch.getQuantityRemaining().setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo("7");
        assertThat(stockMovementRepository.findAll().stream()
                .filter(m -> InventoryConstants.REF_STOCK_ADJUSTMENT_REQUEST.equals(m.getReferenceType()))
                .count()).isEqualTo(1);
    }

    @Test
    void rejectRequest_doesNotChangeStock() throws Exception {
        patchCount("9");
        String requestId = closeAndGetFirstRequestId();

        mockMvc.perform(post(
                        "/api/v1/inventory/stock-take/sessions/{sessionId}/adjustment-requests/{requestId}/reject",
                        sessionId,
                        requestId
                )
                        .contentType(APPLICATION_JSON)
                        .content("{\"notes\":\"no\"}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isNoContent());

        Item item = itemRepository.findById(itemId).orElseThrow();
        assertThat(item.getCurrentStock().setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo("10");
    }

    @Test
    void managerCannotApprove() throws Exception {
        patchCount("11");
        String requestId = closeAndGetFirstRequestId();

        mockMvc.perform(post(
                        "/api/v1/inventory/stock-take/sessions/{sessionId}/adjustment-requests/{requestId}/approve",
                        sessionId,
                        requestId
                )
                        .contentType(APPLICATION_JSON)
                        .content("{}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, manager.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_MANAGER))
                .andExpect(status().isForbidden());
    }

    @Test
    void surplusWithNoBatches_requiresUnitCostOnApprove() throws Exception {
        stockMovementRepository.deleteAll();
        inventoryBatchRepository.deleteAll();
        stockAdjustmentRequestRepository.deleteAll();
        stockTakeSessionRepository.deleteAll();

        String itemNoBatch = itemCatalogService.createItem(
                TENANT,
                new CreateItemRequest(
                        "SKU-EMPTY", null, "Empty stock", null,
                        itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(TENANT).getFirst().getId(),
                        null, null, null,
                        false, true, true,
                        null, null, null, null, null, null, null, null, null, false, null
                ),
                null
        ).body().id();
        Item emptyItem = itemRepository.findById(itemNoBatch).orElseThrow();
        emptyItem.setCurrentStock(BigDecimal.ZERO);
        itemRepository.save(emptyItem);

        MvcResult start = mockMvc.perform(post("/api/v1/inventory/stock-take/sessions")
                        .contentType(APPLICATION_JSON)
                        .content("{\"branchId\":\"" + branchId + "\"}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode session = objectMapper.readTree(start.getResponse().getContentAsString());
        String sid = session.get("id").asText();

        StringBuilder patchBody = new StringBuilder("{\"lines\":[");
        boolean first = true;
        for (JsonNode line : session.get("lines")) {
            if (!first) {
                patchBody.append(",");
            }
            first = false;
            String lid = line.get("id").asText();
            String iid = line.get("itemId").asText();
            if (iid.equals(itemNoBatch)) {
                patchBody.append("{\"lineId\":\"").append(lid).append("\",\"countedQty\":3}");
            } else {
                patchBody.append("{\"lineId\":\"").append(lid).append("\",\"countedQty\":")
                        .append(line.get("systemQtySnapshot").asText())
                        .append("}");
            }
        }
        patchBody.append("]}");

        mockMvc.perform(patch("/api/v1/inventory/stock-take/sessions/{sessionId}/lines", sid)
                        .contentType(APPLICATION_JSON)
                        .content(patchBody.toString())
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/inventory/stock-take/sessions/{sessionId}/close", sid)
                        .contentType(APPLICATION_JSON)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk());

        String rid = getFirstRequestId(sid);

        mockMvc.perform(post(
                        "/api/v1/inventory/stock-take/sessions/{sessionId}/adjustment-requests/{requestId}/approve",
                        sid,
                        rid
                )
                        .contentType(APPLICATION_JSON)
                        .content("{}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(
                        "/api/v1/inventory/stock-take/sessions/{sessionId}/adjustment-requests/{requestId}/approve",
                        sid,
                        rid
                )
                        .contentType(APPLICATION_JSON)
                        .content("{\"unitCost\":4.25}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isNoContent());

        Item updated = itemRepository.findById(itemNoBatch).orElseThrow();
        assertThat(updated.getCurrentStock().setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo("3");
    }

    private JsonNode startSessionJson() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/inventory/stock-take/sessions")
                        .contentType(APPLICATION_JSON)
                        .content("{\"branchId\":\"" + branchId + "\"}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void patchCount(String counted) throws Exception {
        mockMvc.perform(patch("/api/v1/inventory/stock-take/sessions/{sessionId}/lines", sessionId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"lines\":[{\"lineId\":\"" + takeLineId + "\",\"countedQty\":" + counted + "}]}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk());
    }

    private String closeAndGetFirstRequestId() throws Exception {
        mockMvc.perform(post("/api/v1/inventory/stock-take/sessions/{sessionId}/close", sessionId)
                        .contentType(APPLICATION_JSON)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk());
        return getFirstRequestId(sessionId);
    }

    private String getFirstRequestId(String sid) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/inventory/stock-take/sessions/{sessionId}", sid)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.get("adjustmentRequests").get(0).get("id").asText();
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

    private static Permission perm(String id, String key, String desc) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(desc);
        return p;
    }
}
