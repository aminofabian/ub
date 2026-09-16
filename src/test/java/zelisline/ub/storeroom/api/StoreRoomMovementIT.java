package zelisline.ub.storeroom.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.catalog.application.CatalogBootstrapService;
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
import zelisline.ub.platform.security.TestAuthenticationFilter;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.domain.StockMovement;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.purchasing.repository.StockMovementRepository;
import zelisline.ub.purchasing.domain.PurchaseOrder;
import zelisline.ub.purchasing.domain.PurchaseOrderLine;
import zelisline.ub.purchasing.repository.PurchaseOrderLineRepository;
import zelisline.ub.purchasing.repository.PurchaseOrderRepository;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.storeroom.domain.StoreItem;
import zelisline.ub.storeroom.domain.StoreRoomDirection;
import zelisline.ub.storeroom.domain.StoreRoomMode;
import zelisline.ub.storeroom.domain.StoreRoomMovement;
import zelisline.ub.storeroom.domain.StoreRoomMovementStatus;
import zelisline.ub.storeroom.domain.StoreRoomSettings;
import zelisline.ub.storeroom.domain.StoreRoomStockEffect;
import zelisline.ub.storeroom.repository.StoreItemRepository;
import zelisline.ub.storeroom.repository.StoreRoomMovementRepository;
import zelisline.ub.storeroom.repository.StoreRoomSettingsRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * Store-room take-outs and put-ins.
 *
 * <p>The test that matters most is {@link #linkedTakeOut_movesStockExactlyOnce}:
 * a linked take-out must reduce on-hand by the taken quantity and no more. Get the
 * reason classes wrong and that drifts silently — the count falls every time
 * somebody restocks a shelf.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class StoreRoomMovementIT {

    private static final String TENANT = "ffffffff-ffff-ffff-ffff-fffffffffffa";
    private static final String P_CAT_READ = "11111111-0000-0000-0000-000000000040";
    private static final String P_CAT_WRITE = "11111111-0000-0000-0000-000000000041";
    private static final String P_INV_WRITE = "11111111-0000-0000-0000-000000000055";
    private static final String ROLE_OWNER = "22222222-0000-0000-0000-0000000000a1";
    private static final String ROLE_STOCK_MANAGER = "22222222-0000-0000-0000-0000000000a2";
    private static final String BATCH_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1";
    private static final String MOVEMENTS_PATH = "/api/v1/store-room/movements";
    private static final String DELEGATION_ON =
            "{\"inventory\":{\"stockLevels\":{\"allowStockEditForStockManager\":true}}}";

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
    private InventoryBatchRepository inventoryBatchRepository;
    @Autowired
    private StockMovementRepository stockMovementRepository;
    @Autowired
    private StoreItemRepository storeItemRepository;
    @Autowired
    private StoreRoomSettingsRepository storeRoomSettingsRepository;
    @Autowired
    private StoreRoomMovementRepository storeRoomMovementRepository;
    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;
    @Autowired
    private PurchaseOrderLineRepository purchaseOrderLineRepository;
    @Autowired
    private SupplierRepository supplierRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User owner;
    /** A second owner-role user — holds inventory.write, so can approve someone else's take-out. */
    private User boss;
    private User stockManager;
    private String branchId;
    private String itemId;
    /** A store-room row that mirrors the catalogue product. */
    private String linkedRowId;
    /** A store-room row that stands alone, with its own count. */
    private String standaloneRowId;

    @BeforeEach
    void seed() {
        // Children before parents — H2 enforces the FKs create-drop generates.
        storeRoomMovementRepository.deleteAll();
        storeItemRepository.deleteAll();
        storeRoomSettingsRepository.deleteAll();
        stockMovementRepository.deleteAll();
        inventoryBatchRepository.deleteAll();
        purchaseOrderLineRepository.deleteAll();
        purchaseOrderRepository.deleteAll();
        supplierRepository.deleteAll();
        itemRepository.deleteAll();
        itemTypeRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        branchRepository.deleteAll();
        businessRepository.deleteAll();

        Business business = new Business();
        business.setId(TENANT);
        business.setName("Store Room Shop");
        business.setSlug("store-room-shop");
        // Deliberately without the stock-manager delegation toggle; the access tests
        // turn it on to prove the delegated write path.
        business.setSettings("{}");
        businessRepository.save(business);

        Branch branch = new Branch();
        branch.setBusinessId(TENANT);
        branch.setName("Main");
        branchRepository.save(branch);
        branchId = branch.getId();

        catalogBootstrapService.seedDefaultItemTypesIfMissing(TENANT);
        String itemTypeId = itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(TENANT)
                .getFirst().getId();

        permissionRepository.save(perm(P_CAT_READ, "catalog.items.read", "read catalogue"));
        permissionRepository.save(perm(P_CAT_WRITE, "catalog.items.write", "write catalogue"));
        permissionRepository.save(perm(P_INV_WRITE, "inventory.write", "write inventory"));

        roleRepository.save(role(ROLE_OWNER, "owner", "Owner"));
        for (String pid : List.of(P_CAT_READ, P_CAT_WRITE, P_INV_WRITE)) {
            grant(ROLE_OWNER, pid);
        }

        // A stock manager can see the store room (catalog.items.read) but holds no
        // write capability until an owner delegates it.
        roleRepository.save(role(ROLE_STOCK_MANAGER, "stock_manager", "Stock Manager"));
        grant(ROLE_STOCK_MANAGER, P_CAT_READ);

        owner = user("owner@test", "Owner", ROLE_OWNER, branchId);
        boss = user("boss@test", "Boss", ROLE_OWNER, branchId);
        stockManager = user("manager@test", "Stock Manager", ROLE_STOCK_MANAGER, branchId);

        Item item = new Item();
        item.setId(UUID.randomUUID().toString());
        item.setBusinessId(TENANT);
        item.setSku("SRV-1");
        item.setName("Milk 500ml");
        item.setBarcode("6001000000017");
        item.setItemTypeId(itemTypeId);
        item.setWeighed(false);
        item.setSellable(true);
        item.setStocked(true);
        item.setActive(true);
        item.setCurrentStock(new BigDecimal("20.0000"));
        itemRepository.save(item);
        itemId = item.getId();

        inventoryBatchRepository.save(batch(BATCH_ID, new BigDecimal("20.0000")));

        StoreRoomSettings settings = new StoreRoomSettings();
        settings.setBusinessId(TENANT);
        settings.setMode(StoreRoomMode.CONNECTED);
        settings.setConnectedAt(Instant.now());
        storeRoomSettingsRepository.save(settings);

        linkedRowId = storeRow("Cartons of milk", 0, itemId);
        standaloneRowId = storeRow("Cleaning cloths", 5, null);
    }

    // ------------------------------------------------------------------
    // The stock-effect rule
    // ------------------------------------------------------------------

    @Test
    void linkedTakeOut_movesStockExactlyOnce() throws Exception {
        record(owner, ROLE_OWNER, linkedRowId, "out", "spoilage", "3", "crate leaked")
                .andExpect(status().isCreated());

        // The ledger is the stock of record: 20 - 3, and no more.
        assertThat(currentStock()).isEqualByComparingTo("17");
        assertThat(batchRemaining()).isEqualByComparingTo("17");

        List<StoreRoomMovement> log = storeRoomMovementRepository.findAll();
        assertThat(log).hasSize(1);
        StoreRoomMovement entry = log.getFirst();
        assertThat(entry.getStockEffect()).isEqualTo(StoreRoomStockEffect.DECREASE);
        assertThat(entry.getDirection()).isEqualTo(StoreRoomDirection.OUT);
        assertThat(entry.getQuantity()).isEqualByComparingTo("3");
        assertThat(entry.getItemId()).isEqualTo(itemId);
        // The branch the ledger actually posted against must be recorded.
        assertThat(entry.getBranchId()).isEqualTo(branchId);
        assertThat(entry.getMovementId()).isNotBlank();
        assertThat(entry.getMovementCount()).isEqualTo(1);

        // One ledger row, and it is traceable back to the store room.
        List<StockMovement> movements = stockMovementRepository.findAll();
        assertThat(movements).hasSize(1);
        StockMovement movement = movements.getFirst();
        assertThat(movement.getQuantityDelta()).isEqualByComparingTo("-3");
        assertThat(movement.getBranchId()).isEqualTo(branchId);
        assertThat(movement.getId()).isEqualTo(entry.getMovementId());
        // These ledger paths put the caller's text in `notes` — `reason` is left unset.
        assertThat(movement.getNotes()).contains("Store room");
        assertThat(movement.getCreatedBy()).isEqualTo(owner.getId());
    }

    @Test
    void classAReason_logsButLeavesStockAlone() throws Exception {
        // "Restocked the shelf" is a move within the shop, not a loss. Booking it as a
        // decrement is how a back room quietly drains the stock count.
        record(owner, ROLE_OWNER, linkedRowId, "out", "restock_to_shelf", "4", null)
                .andExpect(status().isCreated());

        assertThat(currentStock()).isEqualByComparingTo("20");
        assertThat(batchRemaining()).isEqualByComparingTo("20");
        assertThat(stockMovementRepository.findAll()).isEmpty();

        List<StoreRoomMovement> log = storeRoomMovementRepository.findAll();
        assertThat(log).hasSize(1);
        assertThat(log.getFirst().getStockEffect()).isEqualTo(StoreRoomStockEffect.NONE);
        assertThat(log.getFirst().getMovementId()).isNull();
        assertThat(log.getFirst().getMovementCount()).isZero();
    }

    @Test
    void standaloneTakeOut_touchesOnlyTheLocalCount() throws Exception {
        record(owner, ROLE_OWNER, standaloneRowId, "out", "staff_use", "2", null)
                .andExpect(status().isCreated());

        assertThat(storeItemRepository.findById(standaloneRowId).orElseThrow().getQuantity())
                .isEqualTo(3);
        // Nothing in the catalogue moved, and no ledger row was written.
        assertThat(currentStock()).isEqualByComparingTo("20");
        assertThat(stockMovementRepository.findAll()).isEmpty();
        assertThat(storeRoomMovementRepository.findAll().getFirst().getItemId()).isNull();
    }

    @Test
    void putIn_logsWithoutChangingStock() throws Exception {
        record(owner, ROLE_OWNER, standaloneRowId, "in", "received_into_room", "5", "returned")
                .andExpect(status().isCreated());

        assertThat(storeItemRepository.findById(standaloneRowId).orElseThrow().getQuantity())
                .isEqualTo(5);
        assertThat(currentStock()).isEqualByComparingTo("20");
        assertThat(storeRoomMovementRepository.findAll().getFirst().getDirection())
                .isEqualTo(StoreRoomDirection.IN);
    }

    @Test
    void inheritOrder_logsPutInWithoutMovingInventory() throws Exception {
        String poId = sentPurchaseOrder("PO-STORE-1", "4");

        mockMvc.perform(post("/api/v1/store-room/inherit-order")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "purchaseOrderId", poId,
                                "lines", List.of(Map.of(
                                        "purchaseOrderLineId",
                                        purchaseOrderLineRepository
                                                .findByPurchaseOrderIdOrderBySortOrderAscIdAsc(poId)
                                                .getFirst()
                                                .getId(),
                                        "quantity", "4")))))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .header(TestAuthenticationFilter.HEADER_BRANCH_ID, owner.getBranchId()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.movements").value(1))
                .andExpect(jsonPath("$.poNumber").value("PO-STORE-1"));

        assertThat(currentStock()).isEqualByComparingTo("20");
        assertThat(stockMovementRepository.findAll()).isEmpty();
        List<StoreRoomMovement> log = storeRoomMovementRepository.findAll();
        assertThat(log).hasSize(1);
        assertThat(log.getFirst().getDirection()).isEqualTo(StoreRoomDirection.IN);
        assertThat(log.getFirst().getStockEffect()).isEqualTo(StoreRoomStockEffect.NONE);
        assertThat(log.getFirst().getNote()).contains(poId);
        assertThat(log.getFirst().getItemId()).isEqualTo(itemId);
    }

    @Test
    void inheritOrder_previewSeedsRemainingWithoutUnpacking() throws Exception {
        String poId = sentPurchaseOrder("PO-STORE-0", "6");
        String lineId = purchaseOrderLineRepository
                .findByPurchaseOrderIdOrderBySortOrderAscIdAsc(poId)
                .getFirst()
                .getId();

        mockMvc.perform(get("/api/v1/store-room/inherit-order")
                        .param("purchaseOrderId", poId)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.poNumber").value("PO-STORE-0"))
                .andExpect(jsonPath("$.alreadyInherited").value(false))
                .andExpect(jsonPath("$.unpacked").value(false))
                .andExpect(jsonPath("$.lines[0].purchaseOrderLineId").value(lineId))
                .andExpect(jsonPath("$.lines[0].remaining").value(6.0))
                .andExpect(jsonPath("$.lines[0].onList").value(true));

        assertThat(currentStock()).isEqualByComparingTo("20");
        assertThat(stockMovementRepository.findAll()).isEmpty();
    }

    @Test
    void inheritOrder_againNeedsConfirmation() throws Exception {
        String poId = sentPurchaseOrder("PO-STORE-2", "2");
        String lineId = purchaseOrderLineRepository
                .findByPurchaseOrderIdOrderBySortOrderAscIdAsc(poId)
                .getFirst()
                .getId();
        Map<String, Object> body = Map.of(
                "purchaseOrderId", poId,
                "lines", List.of(Map.of("purchaseOrderLineId", lineId, "quantity", "2")));

        mockMvc.perform(post("/api/v1/store-room/inherit-order")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .header(TestAuthenticationFilter.HEADER_BRANCH_ID, owner.getBranchId()))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/store-room/inherit-order")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .header(TestAuthenticationFilter.HEADER_BRANCH_ID, owner.getBranchId()))
                .andExpect(status().isConflict());

        Map<String, Object> again = new LinkedHashMap<>(body);
        again.put("confirmDuplicate", true);
        mockMvc.perform(post("/api/v1/store-room/inherit-order")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(again))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .header(TestAuthenticationFilter.HEADER_BRANCH_ID, owner.getBranchId()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.movements").value(1));

        assertThat(currentStock()).isEqualByComparingTo("20");
        assertThat(storeRoomMovementRepository.findAll()).hasSize(2);
    }

    @Test
    void inheritOrder_standaloneAddsToTheLocalCount() throws Exception {
        StoreRoomSettings settings = storeRoomSettingsRepository.findById(TENANT).orElseThrow();
        settings.setMode(StoreRoomMode.STANDALONE);
        storeRoomSettingsRepository.save(settings);

        String poId = sentPurchaseOrder("PO-STORE-3", "3");
        String lineId = purchaseOrderLineRepository
                .findByPurchaseOrderIdOrderBySortOrderAscIdAsc(poId)
                .getFirst()
                .getId();

        mockMvc.perform(post("/api/v1/store-room/inherit-order")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "purchaseOrderId", poId,
                                "lines", List.of(Map.of(
                                        "purchaseOrderLineId", lineId,
                                        "quantity", "3")))))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .header(TestAuthenticationFilter.HEADER_BRANCH_ID, owner.getBranchId()))
                .andExpect(status().isCreated());

        assertThat(storeItemRepository.findById(linkedRowId).orElseThrow().getQuantity())
                .isEqualTo(3);
        assertThat(currentStock()).isEqualByComparingTo("20");
        assertThat(stockMovementRepository.findAll()).isEmpty();
    }

    // ------------------------------------------------------------------
    // Rejections
    // ------------------------------------------------------------------

    @Test
    void reasonThatDoesNotSuitTheDirection_isRejected() throws Exception {
        record(owner, ROLE_OWNER, standaloneRowId, "in", "spoilage", "1", null)
                .andExpect(status().isBadRequest());
    }

    @Test
    void otherReasonWithoutANote_isRejected() throws Exception {
        record(owner, ROLE_OWNER, standaloneRowId, "out", "other", "1", null)
                .andExpect(status().isBadRequest());

        assertThat(storeRoomMovementRepository.findAll()).isEmpty();
    }

    @Test
    void standaloneTakeOut_rejectsAFractionalQuantity() throws Exception {
        // store_items.quantity is INT; refuse rather than silently round.
        record(owner, ROLE_OWNER, standaloneRowId, "out", "staff_use", "1.5", null)
                .andExpect(status().isBadRequest());

        assertThat(storeItemRepository.findById(standaloneRowId).orElseThrow().getQuantity())
                .isEqualTo(5);
    }

    @Test
    void standaloneTakeOut_rejectsMoreThanIsOnTheList() throws Exception {
        record(owner, ROLE_OWNER, standaloneRowId, "out", "staff_use", "99", null)
                .andExpect(status().isBadRequest());

        assertThat(storeItemRepository.findById(standaloneRowId).orElseThrow().getQuantity())
                .isEqualTo(5);
    }

    @Test
    void linkedTakeOut_rejectsMoreThanTheShopHas() throws Exception {
        record(owner, ROLE_OWNER, linkedRowId, "out", "spoilage", "999", null)
                .andExpect(status().isBadRequest());

        // Nothing moved, and the rejected attempt left no trace.
        assertThat(currentStock()).isEqualByComparingTo("20");
        assertThat(storeRoomMovementRepository.findAll()).isEmpty();
    }

    // ------------------------------------------------------------------
    // Access — the D1 decision, proved rather than asserted
    // ------------------------------------------------------------------

    @Test
    void stockManagerWithoutDelegation_cannotTakeOut() throws Exception {
        // This is why the nav item is gated on the same setting: without it the page
        // would be visible and every take-out would 403.
        record(stockManager, ROLE_STOCK_MANAGER, linkedRowId, "out", "spoilage", "1", null)
                .andExpect(status().isForbidden());

        assertThat(currentStock()).isEqualByComparingTo("20");
    }

    @Test
    void stockManagerWithDelegatedWrite_canTakeOut() throws Exception {
        businessRepository.save(withDelegationOn());

        record(stockManager, ROLE_STOCK_MANAGER, linkedRowId, "out", "spoilage", "2", null)
                .andExpect(status().isCreated());

        assertThat(currentStock()).isEqualByComparingTo("18");
        assertThat(storeRoomMovementRepository.findAll().getFirst().getBranchId())
                .isEqualTo(branchId);
    }

    @Test
    void stockManagerCanMoveAStandaloneRow_whenDelegated() throws Exception {
        // A delegated stock manager has inventory.write but never catalog.items.write;
        // the local case must accept either, or the back room is half-usable.
        businessRepository.save(withDelegationOn());

        record(stockManager, ROLE_STOCK_MANAGER, standaloneRowId, "out", "staff_use", "1", null)
                .andExpect(status().isCreated());

        assertThat(storeItemRepository.findById(standaloneRowId).orElseThrow().getQuantity())
                .isEqualTo(4);
    }

    // ------------------------------------------------------------------
    // The trail
    // ------------------------------------------------------------------

    @Test
    void activityAnswersWhatLeftToday() throws Exception {
        record(owner, ROLE_OWNER, linkedRowId, "out", "spoilage", "3", "crate leaked")
                .andExpect(status().isCreated());
        // A shelf restock is a take-out too, but it is not a loss.
        record(owner, ROLE_OWNER, linkedRowId, "out", "restock_to_shelf", "4", null)
                .andExpect(status().isCreated());

        String from = Instant.now().minusSeconds(3600).toString();
        String to = Instant.now().plusSeconds(3600).toString();

        mockMvc.perform(get(MOVEMENTS_PATH)
                        .param("from", from)
                        .param("to", to)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.total").value(2))
                .andExpect(jsonPath("$.summary.takeOuts").value(2))
                .andExpect(jsonPath("$.summary.putIns").value(0))
                // Only the spoilage counts as stock that left the shop.
                .andExpect(jsonPath("$.summary.stockLossQuantity").value(3))
                .andExpect(jsonPath("$.movements.length()").value(2))
                .andExpect(jsonPath("$.movements[0].reason").value("restock_to_shelf"))
                .andExpect(jsonPath("$.movements[0].storeItemName").value("Cartons of milk"))
                // "Who" is part of the answer, so the feed must name the actor.
                .andExpect(jsonPath("$.movements[0].createdByName").value("Owner"))
                .andExpect(jsonPath("$.movements[1].note").value("crate leaked"));
    }

    @Test
    void deletingTheRegisterRowKeepsItsHistory() throws Exception {
        record(owner, ROLE_OWNER, linkedRowId, "out", "spoilage", "1", null)
                .andExpect(status().isCreated());

        storeItemRepository.deleteById(linkedRowId);

        StoreRoomMovement surviving = storeRoomMovementRepository.findAll().getFirst();
        assertThat(surviving.getItemId()).isEqualTo(itemId);
        assertThat(surviving.getQuantity()).isEqualByComparingTo("1");
    }

    // ------------------------------------------------------------------
    // Approval for large decreases
    // ------------------------------------------------------------------

    @Test
    void largeTakeOutAboveThreshold_waitsForApprovalAndMovesNothing() throws Exception {
        setThreshold("5");

        String id = recordAndGetId(owner, ROLE_OWNER, linkedRowId, "spoilage", "6");

        // The whole point of pending: no stock, no ledger row.
        assertThat(currentStock()).isEqualByComparingTo("20");
        assertThat(batchRemaining()).isEqualByComparingTo("20");
        assertThat(stockMovementRepository.findAll()).isEmpty();

        StoreRoomMovement row = storeRoomMovementRepository.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(StoreRoomMovementStatus.PENDING);
        assertThat(row.getMovementId()).isNull();
        assertThat(row.getMovementCount()).isZero();
    }

    @Test
    void approvingAPendingTakeOut_movesStockExactlyOnce() throws Exception {
        setThreshold("5");
        String id = recordAndGetId(owner, ROLE_OWNER, linkedRowId, "spoilage", "6");

        decide(owner, ROLE_OWNER, id, true, "checked the crate")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("applied"))
                .andExpect(jsonPath("$.decidedByName").value("Owner"))
                .andExpect(jsonPath("$.decisionNote").value("checked the crate"));

        assertThat(currentStock()).isEqualByComparingTo("14");
        assertThat(stockMovementRepository.findAll()).hasSize(1);
        assertThat(storeRoomMovementRepository.findById(id).orElseThrow().getMovementId())
                .isNotBlank();
    }

    @Test
    void decidingTwice_isRefused() throws Exception {
        setThreshold("5");
        String id = recordAndGetId(owner, ROLE_OWNER, linkedRowId, "spoilage", "6");

        decide(owner, ROLE_OWNER, id, true, null).andExpect(status().isOk());
        // Re-approving must not take another 6 off the shelf.
        decide(owner, ROLE_OWNER, id, true, null).andExpect(status().isConflict());

        assertThat(currentStock()).isEqualByComparingTo("14");
        assertThat(stockMovementRepository.findAll()).hasSize(1);
    }

    @Test
    void rejectingAPendingTakeOut_leavesStockAlone() throws Exception {
        setThreshold("5");
        String id = recordAndGetId(owner, ROLE_OWNER, linkedRowId, "spoilage", "6");

        decide(owner, ROLE_OWNER, id, false, "count was wrong")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("rejected"));

        assertThat(currentStock()).isEqualByComparingTo("20");
        assertThat(stockMovementRepository.findAll()).isEmpty();
        assertThat(storeRoomMovementRepository.findById(id).orElseThrow().getDecisionNote())
                .isEqualTo("count was wrong");
    }

    @Test
    void takeOutAtTheThreshold_appliesImmediately() throws Exception {
        // "More than" N needs approval, so exactly N does not.
        setThreshold("5");

        recordAndGetId(owner, ROLE_OWNER, linkedRowId, "spoilage", "5");

        assertThat(currentStock()).isEqualByComparingTo("15");
        assertThat(storeRoomMovementRepository.findAll().getFirst().getStatus())
                .isEqualTo(StoreRoomMovementStatus.APPLIED);
    }

    @Test
    void thresholdDoesNotGateLocalCounts() throws Exception {
        // A back-room count is low stakes; the threshold is about stock.
        setThreshold("1");

        recordAndGetId(owner, ROLE_OWNER, standaloneRowId, "staff_use", "3");

        assertThat(storeItemRepository.findById(standaloneRowId).orElseThrow().getQuantity())
                .isEqualTo(2);
        assertThat(storeRoomMovementRepository.findAll().getFirst().getStatus())
                .isEqualTo(StoreRoomMovementStatus.APPLIED);
    }

    @Test
    void decidingNeedsInventoryWrite() throws Exception {
        setThreshold("1");
        String id = recordAndGetId(owner, ROLE_OWNER, linkedRowId, "spoilage", "2");

        // A stock manager without the delegation toggle cannot approve either.
        decide(stockManager, ROLE_STOCK_MANAGER, id, true, null)
                .andExpect(status().isForbidden());

        assertThat(currentStock()).isEqualByComparingTo("20");
    }

    // ------------------------------------------------------------------
    // Filters
    // ------------------------------------------------------------------

    @Test
    void filtersNarrowTheListButNotTheHeadline() throws Exception {
        setThreshold("5");
        record(owner, ROLE_OWNER, linkedRowId, "out", "spoilage", "3", null)
                .andExpect(status().isCreated());
        record(owner, ROLE_OWNER, linkedRowId, "out", "theft", "6", null)
                .andExpect(status().isCreated());

        String from = Instant.now().minusSeconds(3600).toString();
        String to = Instant.now().plusSeconds(3600).toString();

        mockMvc.perform(get(MOVEMENTS_PATH)
                        .param("from", from)
                        .param("to", to)
                        .param("reason", "theft")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                // One row in the list...
                .andExpect(jsonPath("$.movements.length()").value(1))
                .andExpect(jsonPath("$.movements[0].reason").value("theft"))
                // ...but the window's headline still counts both.
                .andExpect(jsonPath("$.summary.total").value(2))
                // Only the applied spoilage has actually left the shop.
                .andExpect(jsonPath("$.summary.stockLossQuantity").value(3))
                .andExpect(jsonPath("$.summary.pending").value(1))
                // Facets cover the window, so the other reason is still offered.
                .andExpect(jsonPath("$.facets.reasons.length()").value(2))
                .andExpect(jsonPath("$.facets.actors[0].name").value("Owner"));
    }

    @Test
    void statusFilterIsolatesWhatNeedsDeciding() throws Exception {
        setThreshold("5");
        recordAndGetId(owner, ROLE_OWNER, linkedRowId, "spoilage", "6");
        record(owner, ROLE_OWNER, linkedRowId, "out", "spoilage", "1", null)
                .andExpect(status().isCreated());

        safeGet("pending")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movements.length()").value(1))
                .andExpect(jsonPath("$.movements[0].status").value("pending"))
                .andExpect(jsonPath("$.movements[0].quantity").value(6));
    }

    // ------------------------------------------------------------------
    // Separation of duties (§10 D7)
    // ------------------------------------------------------------------

    @Test
    void approvingYourOwnTakeOutIsAllowedByDefault() throws Exception {
        // The default must not change behaviour for a one-person shop.
        setThreshold("5");
        String id = recordAndGetId(owner, ROLE_OWNER, linkedRowId, "spoilage", "6");

        decide(owner, ROLE_OWNER, id, true, null).andExpect(status().isOk());

        assertThat(currentStock()).isEqualByComparingTo("14");
    }

    @Test
    void separateApproverPolicyBlocksApprovingYourOwn() throws Exception {
        setThreshold("5");
        requireSeparateApprover();
        String id = recordAndGetId(owner, ROLE_OWNER, linkedRowId, "spoilage", "6");

        decide(owner, ROLE_OWNER, id, true, null)
                .andExpect(status().isForbidden());

        // Nothing moved, and it is still waiting for somebody else.
        assertThat(currentStock()).isEqualByComparingTo("20");
        assertThat(stockMovementRepository.findAll()).isEmpty();
        assertThat(storeRoomMovementRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(StoreRoomMovementStatus.PENDING);

        // But you may still withdraw your own request — that moves no stock.
        decide(owner, ROLE_OWNER, id, false, "never mind")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("rejected"));
        assertThat(currentStock()).isEqualByComparingTo("20");
    }

    @Test
    void separateApproverPolicyLetsSomeoneElseApprove() throws Exception {
        setThreshold("5");
        requireSeparateApprover();
        String id = recordAndGetId(owner, ROLE_OWNER, linkedRowId, "spoilage", "6");

        decide(boss, ROLE_OWNER, id, true, "counted it myself")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("applied"))
                .andExpect(jsonPath("$.decidedByName").value("Boss"));

        assertThat(currentStock()).isEqualByComparingTo("14");
    }

    /**
     * The policy has to be a switch, not a one-way door. Turning it off must work, the
     * read must report it so the settings dialog can render the current state, and
     * {@code false} on its own must count as a real update rather than a 400 — the
     * guard tests the field for null, not for truth.
     */
    @Test
    void separateApproverPolicyCanBeTurnedBackOff() throws Exception {
        setThreshold("5");
        requireSeparateApprover();

        fetchSettings()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requireSeparateApprover").value(true))
                .andExpect(jsonPath("$.approvalThreshold").exists());

        String id = recordAndGetId(owner, ROLE_OWNER, linkedRowId, "spoilage", "6");
        decide(owner, ROLE_OWNER, id, true, null).andExpect(status().isForbidden());

        putSettings("{\"requireSeparateApprover\":false}");
        fetchSettings().andExpect(jsonPath("$.requireSeparateApprover").value(false));

        // With the policy off again, the same person may approve what they raised.
        decide(owner, ROLE_OWNER, id, true, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("applied"));
        assertThat(currentStock()).isEqualByComparingTo("14");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private ResultActions record(
            User actor,
            String roleId,
            String storeItemId,
            String direction,
            String reason,
            String quantity,
            String note
    ) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("storeItemId", storeItemId);
        body.put("direction", direction);
        body.put("reason", reason);
        body.put("quantity", quantity);
        if (note != null) {
            body.put("note", note);
        }
        return mockMvc.perform(post(MOVEMENTS_PATH)
                .contentType(APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
                .header("X-Tenant-Id", TENANT)
                .header(TestAuthenticationFilter.HEADER_USER_ID, actor.getId())
                .header(TestAuthenticationFilter.HEADER_ROLE_ID, roleId)
                .header(TestAuthenticationFilter.HEADER_BRANCH_ID, actor.getBranchId()));
    }

    private void putSettings(String json) throws Exception {
        mockMvc.perform(put("/api/v1/store-items/settings")
                        .contentType(APPLICATION_JSON)
                        .content(json)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .header(TestAuthenticationFilter.HEADER_BRANCH_ID, owner.getBranchId()))
                .andExpect(status().isOk());
    }

    private ResultActions fetchSettings() throws Exception {
        return mockMvc.perform(get("/api/v1/store-items/settings")
                .header("X-Tenant-Id", TENANT)
                .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                .header(TestAuthenticationFilter.HEADER_BRANCH_ID, owner.getBranchId()));
    }

    private void setThreshold(String value) throws Exception {
        putSettings("{\"approvalThreshold\":" + value + "}");
    }

    /** §10 D7: nobody may approve a take-out they raised themselves. */
    private void requireSeparateApprover() throws Exception {
        putSettings("{\"requireSeparateApprover\":true}");
    }

    /** Records a take-out that is expected to succeed, and returns its movement id. */
    private String recordAndGetId(
            User actor,
            String roleId,
            String storeItemId,
            String reason,
            String quantity
    ) throws Exception {
        record(actor, roleId, storeItemId, "out", reason, quantity, null)
                .andExpect(status().isCreated());
        return storeRoomMovementRepository.findAll().getFirst().getId();
    }

    private ResultActions decide(
            User actor,
            String roleId,
            String movementId,
            boolean approve,
            String note
    ) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        if (note != null) {
            body.put("note", note);
        }
        return mockMvc.perform(post(MOVEMENTS_PATH + "/" + movementId + "/"
                        + (approve ? "approve" : "reject"))
                .contentType(APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
                .header("X-Tenant-Id", TENANT)
                .header(TestAuthenticationFilter.HEADER_USER_ID, actor.getId())
                .header(TestAuthenticationFilter.HEADER_ROLE_ID, roleId)
                .header(TestAuthenticationFilter.HEADER_BRANCH_ID, actor.getBranchId()));
    }

    /** The activity read for the surrounding hour, filtered by status. */
    private ResultActions safeGet(String status) throws Exception {
        return mockMvc.perform(get(MOVEMENTS_PATH)
                .param("from", Instant.now().minusSeconds(3600).toString())
                .param("to", Instant.now().plusSeconds(3600).toString())
                .param("status", status)
                .header("X-Tenant-Id", TENANT)
                .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER));
    }

    private Business withDelegationOn() {
        Business business = businessRepository.findById(TENANT).orElseThrow();
        business.setSettings(DELEGATION_ON);
        return business;
    }

    private BigDecimal currentStock() {
        return itemRepository.findById(itemId).orElseThrow().getCurrentStock();
    }

    private BigDecimal batchRemaining() {
        return inventoryBatchRepository.findById(BATCH_ID).orElseThrow().getQuantityRemaining();
    }

    private String sentPurchaseOrder(String poNumber, String qty) {
        Supplier supplier = new Supplier();
        supplier.setBusinessId(TENANT);
        supplier.setName("Store Foods");
        supplier.setSupplierType("distributor");
        supplier.setStatus("active");
        supplierRepository.save(supplier);

        PurchaseOrder po = new PurchaseOrder();
        po.setBusinessId(TENANT);
        po.setSupplierId(supplier.getId());
        po.setBranchId(branchId);
        po.setPoNumber(poNumber);
        po.setStatus("sent");
        po.setSource("manual");
        po.setDeliveryStatus("delivered");
        purchaseOrderRepository.save(po);

        PurchaseOrderLine line = new PurchaseOrderLine();
        line.setPurchaseOrderId(po.getId());
        line.setSortOrder(0);
        line.setItemId(itemId);
        line.setQtyOrdered(new BigDecimal(qty));
        line.setQtyReceived(BigDecimal.ZERO);
        line.setUnitEstimatedCost(new BigDecimal("10.0000"));
        purchaseOrderLineRepository.save(line);
        return po.getId();
    }

    private String storeRow(String name, int quantity, String linkedItemId) {
        StoreItem row = new StoreItem();
        row.setId(UUID.randomUUID().toString());
        row.setBusinessId(TENANT);
        row.setName(name);
        row.setQuantity(quantity);
        row.setItemId(linkedItemId);
        storeItemRepository.save(row);
        return row.getId();
    }

    private InventoryBatch batch(String id, BigDecimal qty) {
        InventoryBatch b = new InventoryBatch();
        b.setId(id);
        b.setBusinessId(TENANT);
        b.setBranchId(branchId);
        b.setItemId(itemId);
        b.setSupplierId(null);
        b.setBatchNumber("SRC-1");
        b.setSourceType("test");
        b.setSourceId(UUID.randomUUID().toString());
        b.setInitialQuantity(qty);
        b.setQuantityRemaining(qty);
        b.setUnitCost(new BigDecimal("3.5000"));
        b.setReceivedAt(Instant.parse("2026-03-01T12:00:00Z"));
        b.setStatus(InventoryConstants.BATCH_STATUS_ACTIVE);
        return b;
    }

    private User user(String email, String name, String roleId, String userBranchId) {
        User u = new User();
        u.setBusinessId(TENANT);
        u.setEmail(email);
        u.setName(name);
        u.setRoleId(roleId);
        u.setBranchId(userBranchId);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        return userRepository.save(u);
    }

    private static Role role(String id, String key, String name) {
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

    private static Permission perm(String id, String key, String description) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(description);
        return p;
    }
}
