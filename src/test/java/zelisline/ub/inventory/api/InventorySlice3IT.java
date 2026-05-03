package zelisline.ub.inventory.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.http.MediaType.APPLICATION_JSON;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

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
import zelisline.ub.inventory.api.dto.PostStockTransferRequest;
import zelisline.ub.inventory.api.dto.StockTransferCreatedResponse;
import zelisline.ub.inventory.application.InventoryTransferService;
import zelisline.ub.inventory.repository.StockAdjustmentRequestRepository;
import zelisline.ub.inventory.repository.StockTakeSessionRepository;
import zelisline.ub.inventory.repository.StockTransferRepository;
import zelisline.ub.platform.security.TestAuthenticationFilter;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.domain.StockMovement;
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
class InventorySlice3IT {

    private static final String TENANT = "ffffffff-ffff-ffff-ffff-ffffffffffff";
    private static final String OTHER_TENANT = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    private static final String P_READ = "11111111-0000-0000-0000-000000000040";
    private static final String P_INV_T = "11111111-0000-0000-0000-000000000056";
    private static final String ROLE_OWNER = "22222222-0000-0000-0000-000000000077";

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
    private StockTransferRepository stockTransferRepository;
    @Autowired
    private InventoryTransferService inventoryTransferService;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User owner;
    private String branchAId;
    private String branchBId;
    private String itemId;
    private String sourceBatchId;

    @BeforeEach
    void seed() {
        stockMovementRepository.deleteAll();
        inventoryBatchRepository.deleteAll();
        stockAdjustmentRequestRepository.deleteAll();
        stockTakeSessionRepository.deleteAll();
        stockTransferRepository.deleteAll();
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
        b.setName("Transfer Shop");
        b.setSlug("transfer-shop");
        b.setSettings("{}");
        businessRepository.save(b);

        Branch a = new Branch();
        a.setBusinessId(TENANT);
        a.setName("Warehouse A");
        branchRepository.save(a);
        branchAId = a.getId();

        Branch brB = new Branch();
        brB.setBusinessId(TENANT);
        brB.setName("Store B");
        branchRepository.save(brB);
        branchBId = brB.getId();

        catalogBootstrapService.seedDefaultItemTypesIfMissing(TENANT);
        String goodsTypeId = itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(TENANT).getFirst().getId();

        permissionRepository.save(perm(P_READ, "catalog.items.read", "r"));
        permissionRepository.save(perm(P_INV_T, "inventory.transfer", "xfer"));

        Role ownerRole = new Role();
        ownerRole.setId(ROLE_OWNER);
        ownerRole.setBusinessId(null);
        ownerRole.setRoleKey("owner");
        ownerRole.setName("Owner");
        ownerRole.setSystem(true);
        roleRepository.save(ownerRole);
        for (String pid : List.of(P_READ, P_INV_T)) {
            RolePermission rp = new RolePermission();
            rp.setId(new RolePermission.Id(ROLE_OWNER, pid));
            rolePermissionRepository.save(rp);
        }

        owner = new User();
        owner.setBusinessId(TENANT);
        owner.setEmail("owner-xfer@test");
        owner.setName("Owner");
        owner.setRoleId(ROLE_OWNER);
        owner.setStatus(UserStatus.ACTIVE);
        owner.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(owner);

        itemId = itemCatalogService.createItem(
                TENANT,
                new CreateItemRequest(
                        "SKU-XFER", null, "Xfer Item", null, goodsTypeId, null, null, null,
                        false, true, true,
                        null, null, null, null, null, null, null, null, null, false, null
                ),
                null
        ).body().id();

        Item item = itemRepository.findById(itemId).orElseThrow();
        item.setCurrentStock(new BigDecimal("10"));
        itemRepository.save(item);

        sourceBatchId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
        inventoryBatchRepository.save(sourceBatch(sourceBatchId, branchAId, new BigDecimal("10")));
    }

    @Test
    void transfer_movesStockWithoutChangingItemTotal() throws Exception {
        String tid = createTransferViaApi(new BigDecimal("4"));

        mockMvc.perform(post("/api/v1/inventory/transfers/" + tid + "/complete")
                        .contentType(APPLICATION_JSON)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isNoContent());

        InventoryBatch src = inventoryBatchRepository.findById(sourceBatchId).orElseThrow();
        assertThat(src.getQuantityRemaining().setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo("6");

        List<InventoryBatch> atB = inventoryBatchRepository
                .findByBusinessIdAndItemIdAndBranchIdAndStatusAndQuantityRemainingGreaterThanOrderByIdAsc(
                        TENANT,
                        itemId,
                        branchBId,
                        InventoryConstants.BATCH_STATUS_ACTIVE,
                        BigDecimal.ZERO
                );
        assertThat(atB).hasSize(1);
        assertThat(atB.getFirst().getQuantityRemaining().setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo("4");

        Item item = itemRepository.findById(itemId).orElseThrow();
        assertThat(item.getCurrentStock().setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo("10");

        List<StockMovement> moves = stockMovementRepository.findAll();
        assertThat(moves.stream().filter(m -> InventoryConstants.MOVEMENT_TRANSFER_OUT.equals(m.getMovementType())))
                .hasSize(1);
        assertThat(moves.stream().filter(m -> InventoryConstants.MOVEMENT_TRANSFER_IN.equals(m.getMovementType())))
                .hasSize(1);
    }

    @Test
    void create_rejectsBranchFromAnotherTenant() throws Exception {
        Business other = new Business();
        other.setId(OTHER_TENANT);
        other.setName("Other");
        other.setSlug("other-shop");
        other.setSettings("{}");
        businessRepository.save(other);
        Branch foreign = new Branch();
        foreign.setBusinessId(OTHER_TENANT);
        foreign.setName("Elsewhere");
        branchRepository.save(foreign);

        PostStockTransferRequest body = new PostStockTransferRequest(
                branchAId,
                foreign.getId(),
                null,
                List.of(new PostStockTransferRequest.Line(itemId, new BigDecimal("1")))
        );

        mockMvc.perform(post("/api/v1/inventory/transfers")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isBadRequest());
    }

    @Test
    void completeRollsBackWhenLaterLineHasInsufficientStock() {
        String transferId = transactionTemplate.execute(status -> {
            StockTransferCreatedResponse r = inventoryTransferService.createDraft(
                    TENANT,
                    new PostStockTransferRequest(
                            branchAId,
                            branchBId,
                            null,
                            List.of(
                                    new PostStockTransferRequest.Line(itemId, new BigDecimal("10")),
                                    new PostStockTransferRequest.Line(itemId, new BigDecimal("1"))
                            )
                    ),
                    owner.getId()
            );
            return r.id();
        });

        Throwable thrown = catchThrowable(() ->
                transactionTemplate.executeWithoutResult(st ->
                        inventoryTransferService.completeTransfer(TENANT, transferId, owner.getId())
                ));

        assertThat(thrown).isNotNull();
        boolean found = false;
        for (Throwable c = thrown; c != null; c = c.getCause()) {
            if (c instanceof ResponseStatusException) {
                found = true;
                break;
            }
        }
        assertThat(found).isTrue();

        InventoryBatch src = inventoryBatchRepository.findById(sourceBatchId).orElseThrow();
        assertThat(src.getQuantityRemaining().setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo("10");
        assertThat(stockMovementRepository.count()).isZero();
    }

    @Test
    void secondCompleteReturnsConflict() throws Exception {
        String tid = createTransferViaApi(new BigDecimal("1"));

        mockMvc.perform(post("/api/v1/inventory/transfers/" + tid + "/complete")
                        .contentType(APPLICATION_JSON)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/inventory/transfers/" + tid + "/complete")
                        .contentType(APPLICATION_JSON)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isConflict());
    }

    private String createTransferViaApi(BigDecimal qty) throws Exception {
        PostStockTransferRequest body = new PostStockTransferRequest(
                branchAId,
                branchBId,
                null,
                List.of(new PostStockTransferRequest.Line(itemId, qty))
        );
        String json = mockMvc.perform(post("/api/v1/inventory/transfers")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readValue(json, StockTransferCreatedResponse.class).id();
    }

    private InventoryBatch sourceBatch(String id, String branchId, BigDecimal qty) {
        InventoryBatch b = new InventoryBatch();
        b.setId(id);
        b.setBusinessId(TENANT);
        b.setBranchId(branchId);
        b.setItemId(itemId);
        b.setSupplierId(null);
        b.setBatchNumber("SRC-" + id.substring(0, 8));
        b.setSourceType("test");
        b.setSourceId(UUID.randomUUID().toString());
        b.setInitialQuantity(qty);
        b.setQuantityRemaining(qty);
        b.setUnitCost(new BigDecimal("3.5000"));
        b.setReceivedAt(Instant.parse("2026-03-01T12:00:00Z"));
        b.setStatus(InventoryConstants.BATCH_STATUS_ACTIVE);
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
