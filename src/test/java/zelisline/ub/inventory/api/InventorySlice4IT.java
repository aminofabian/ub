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
import java.time.LocalDate;
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

    @Autowired
    private LedgerBootstrapService ledgerBootstrapService;

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

        ledgerBootstrapService.ensureStandardAccounts(TENANT);

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
                        null, null, null, null, null, null, null, null, null, null, false, null, null, null),
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
    void confirmLine_increasesStockUsingBatchAverageCost() throws Exception {
        patchCount("12");
        confirmLine(sessionId, takeLineId);

        Item item = itemRepository.findById(itemId).orElseThrow();
        assertThat(item.getCurrentStock().setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo("12");
    }

    @Test
    void confirmLine_decrementsStock() throws Exception {
        patchCount("7");
        confirmLine(sessionId, takeLineId);

        Item item = itemRepository.findById(itemId).orElseThrow();
        assertThat(item.getCurrentStock().setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo("7");
        InventoryBatch batch = inventoryBatchRepository.findById(batchId).orElseThrow();
        assertThat(batch.getQuantityRemaining().setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo("7");
    }

    @Test
    void confirmLine_withZeroVariance_doesNotChangeStock() throws Exception {
        patchCount("10"); // system qty is 10
        confirmLine(sessionId, takeLineId);

        Item item = itemRepository.findById(itemId).orElseThrow();
        assertThat(item.getCurrentStock().setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo("10");
    }

    @Test
    void managerCannotConfirm() throws Exception {
        patchCount("11");
        mockMvc.perform(post("/api/v1/inventory/stock-take/sessions/{sessionId}/lines/{lineId}/confirm", sessionId, takeLineId)
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
                        null, null, null, null, null, null, null, null, null, null, false, null, null, null),
                null
        ).body().id();
        Item emptyItem = itemRepository.findById(itemNoBatch).orElseThrow();
        emptyItem.setCurrentStock(BigDecimal.ZERO);
        itemRepository.save(emptyItem);

        String today = LocalDate.now().toString();
        MvcResult start = mockMvc.perform(post("/api/v1/inventory/stock-take/sessions")
                        .contentType(APPLICATION_JSON)
                        .content("{\"branchId\":\"" + branchId + "\",\"sessionType\":\"morning\",\"sessionDate\":\"" + today + "\"}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode session = objectMapper.readTree(start.getResponse().getContentAsString());
        String sid = session.get("id").asText();

        StringBuilder patchBody = new StringBuilder("{\"lines\":[");
        boolean first = true;
        String noBatchLineId = null;
        for (JsonNode line : session.get("lines")) {
            if (!first) {
                patchBody.append(",");
            }
            first = false;
            String lid = line.get("id").asText();
            String iid = line.get("itemId").asText();
            if (iid.equals(itemNoBatch)) {
                noBatchLineId = lid;
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

        // Confirm the no-batch line before closing (v2 flow)
        assertThat(noBatchLineId).isNotNull();
        mockMvc.perform(post("/api/v1/inventory/stock-take/sessions/{sessionId}/lines/{lineId}/confirm", sid, noBatchLineId)
                        .contentType(APPLICATION_JSON)
                        .content("{}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk());

        // Confirm remaining lines too so close succeeds
        for (JsonNode line : session.get("lines")) {
            String lid = line.get("id").asText();
            if (!lid.equals(noBatchLineId)) {
                mockMvc.perform(post("/api/v1/inventory/stock-take/sessions/{sessionId}/lines/{lineId}/confirm", sid, lid)
                                .contentType(APPLICATION_JSON)
                                .content("{}")
                                .header("X-Tenant-Id", TENANT)
                                .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                                .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                        .andExpect(status().isOk());
            }
        }

        Item updated = itemRepository.findById(itemNoBatch).orElseThrow();
        assertThat(updated.getCurrentStock().setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo("3");
    }

    @Test
    void startMorningAndEveningSessions_enforcesOnePerTypePerDay() throws Exception {
        String today = LocalDate.now().toString();
        // @BeforeEach already created a morning session

        // Second morning session on same day fails
        mockMvc.perform(post("/api/v1/inventory/stock-take/sessions")
                        .contentType(APPLICATION_JSON)
                        .content("{\"branchId\":\"" + branchId + "\",\"sessionType\":\"morning\",\"sessionDate\":\"" + today + "\"}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isConflict());

        // Evening session on same day succeeds
        mockMvc.perform(post("/api/v1/inventory/stock-take/sessions")
                        .contentType(APPLICATION_JSON)
                        .content("{\"branchId\":\"" + branchId + "\",\"sessionType\":\"evening\",\"sessionDate\":\"" + today + "\"}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated());
    }

    @Test
    void eveningSession_inheritsMorningConfirmedLines() throws Exception {
        String today = LocalDate.now().toString();
        // Use the morning session created by @BeforeEach
        String morningLineId = takeLineId;

        // Submit count for the morning line
        mockMvc.perform(patch("/api/v1/inventory/stock-take/sessions/{sessionId}/lines/{lineId}", sessionId, morningLineId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"countedQty\":8}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk());

        // Confirm the morning line
        mockMvc.perform(post("/api/v1/inventory/stock-take/sessions/{sessionId}/lines/{lineId}/confirm", sessionId, morningLineId)
                        .contentType(APPLICATION_JSON)
                        .content("{}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk());

        // Start evening session — should inherit the confirmed morning line
        MvcResult eveningResult = mockMvc.perform(post("/api/v1/inventory/stock-take/sessions")
                        .contentType(APPLICATION_JSON)
                        .content("{\"branchId\":\"" + branchId + "\",\"sessionType\":\"evening\",\"sessionDate\":\"" + today + "\"}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode evening = objectMapper.readTree(eveningResult.getResponse().getContentAsString());
        assertThat(evening.get("lines").size()).isEqualTo(1);
        assertThat(evening.get("lines").get(0).get("itemId").asText()).isEqualTo(itemId);
    }

    @Test
    void applySingleCount_andConfirmLine_appliesInventoryImmediately() throws Exception {
        String sid = sessionId;
        String lineId = takeLineId;

        // Apply single count via PATCH /lines/{lineId}
        mockMvc.perform(patch("/api/v1/inventory/stock-take/sessions/{sessionId}/lines/{lineId}", sid, lineId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"countedQty\":15}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk());

        // Confirm line — inventory should update immediately
        mockMvc.perform(post("/api/v1/inventory/stock-take/sessions/{sessionId}/lines/{lineId}/confirm", sid, lineId)
                        .contentType(APPLICATION_JSON)
                        .content("{}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk());

        Item item = itemRepository.findById(itemId).orElseThrow();
        assertThat(item.getCurrentStock().setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo("15");
    }

    @Test
    void confirmLine_preventsFurtherModification() throws Exception {
        String sid = sessionId;
        String lineId = takeLineId;

        // Submit and confirm
        mockMvc.perform(patch("/api/v1/inventory/stock-take/sessions/{sessionId}/lines/{lineId}", sid, lineId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"countedQty\":12}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/inventory/stock-take/sessions/{sessionId}/lines/{lineId}/confirm", sid, lineId)
                        .contentType(APPLICATION_JSON)
                        .content("{}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk());

        // Try to modify confirmed line
        mockMvc.perform(patch("/api/v1/inventory/stock-take/sessions/{sessionId}/lines/{lineId}", sid, lineId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"countedQty\":13}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isConflict());
    }

    @Test
    void closeSession_withoutForce_warnsIfUnconfirmed() throws Exception {
        String sid = sessionId;
        String lineId = takeLineId;

        // Submit count but do NOT confirm
        mockMvc.perform(patch("/api/v1/inventory/stock-take/sessions/{sessionId}/lines/{lineId}", sid, lineId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"countedQty\":11}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk());

        // Close without force should fail
        mockMvc.perform(post("/api/v1/inventory/stock-take/sessions/{sessionId}/close", sid)
                        .contentType(APPLICATION_JSON)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isConflict());

        // Close with force should succeed
        mockMvc.perform(post("/api/v1/inventory/stock-take/sessions/{sessionId}/close?force=true", sid)
                        .contentType(APPLICATION_JSON)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk());
    }

    @Test
    void managerCannotCloseSession() throws Exception {
        mockMvc.perform(post("/api/v1/inventory/stock-take/sessions/{sessionId}/close", sessionId)
                        .contentType(APPLICATION_JSON)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, manager.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_MANAGER))
                .andExpect(status().isForbidden());
    }

    @Test
    void getActiveSession_returnsSession() throws Exception {
        String sid = sessionId;

        MvcResult result = mockMvc.perform(get("/api/v1/inventory/stock-take/sessions/active")
                        .param("branchId", branchId)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(root.get("session").get("id").asText()).isEqualTo(sid);
        assertThat(root.get("hasStaleSession").asBoolean()).isFalse();
    }

    @Test
    void createItemAndAddLine_atomic() throws Exception {
        String sid = sessionId;
        String typeId = itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(TENANT).getFirst().getId();

        MvcResult result = mockMvc.perform(post("/api/v1/inventory/stock-take/sessions/{sessionId}/lines/with-product", sid)
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Atomic Cereal\",\"itemTypeId\":\"" + typeId + "\",\"countedQty\":42,\"aisle\":\"Aisle 9\"}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        // Should contain the original line plus the new one
        assertThat(root.get("lines").size()).isEqualTo(2);
        // Find the new line
        JsonNode newLine = null;
        for (JsonNode line : root.get("lines")) {
            if ("Atomic Cereal".equals(line.get("itemName").asText())) {
                newLine = line;
                break;
            }
        }
        assertThat(newLine).isNotNull();
        assertThat(newLine.get("countedQty").asText()).isIn("42", "42.0");
        assertThat(newLine.get("aisle").asText()).isEqualTo("Aisle 9");
        assertThat(newLine.get("status").asText()).isEqualTo("submitted");
    }

    private JsonNode startSessionJson() throws Exception {
        return startSessionJson("morning", java.time.LocalDate.now().toString());
    }

    private JsonNode startSessionJson(String sessionType, String sessionDate) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/inventory/stock-take/sessions")
                        .contentType(APPLICATION_JSON)
                        .content("{\"branchId\":\"" + branchId + "\",\"sessionType\":\"" + sessionType + "\",\"sessionDate\":\"" + sessionDate + "\"}")
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

    private void confirmLine(String sid, String lid) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/inventory/stock-take/sessions/{sessionId}/lines/{lineId}/confirm", sid, lid)
                        .contentType(APPLICATION_JSON)
                        .content("{}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andReturn();
        if (result.getResponse().getStatus() != 200) {
            throw new AssertionError("confirmLine failed: " + result.getResponse().getStatus()
                + " body=" + result.getResponse().getContentAsString());
        }
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
