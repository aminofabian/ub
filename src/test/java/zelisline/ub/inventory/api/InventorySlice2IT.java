package zelisline.ub.inventory.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.type.TypeReference;
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
import zelisline.ub.inventory.CostMethod;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.api.dto.BatchAllocationLine;
import zelisline.ub.inventory.application.InventoryBatchPickerService;
import zelisline.ub.inventory.application.BusinessInventorySettingsReader;
import zelisline.ub.tenancy.application.BusinessInventorySettingsService;
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
class InventorySlice2IT {

    private static final String TENANT = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    private static final String P_READ = "11111111-0000-0000-0000-000000000040";
    private static final String P_INV_R = "11111111-0000-0000-0000-000000000054";
    private static final String P_INV_W = "11111111-0000-0000-0000-000000000055";
    private static final String ROLE_OWNER = "22222222-0000-0000-0000-000000000088";

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
    private InventoryBatchPickerService inventoryBatchPickerService;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private BusinessInventorySettingsReader businessInventorySettingsReader;
    @Autowired
    private BusinessInventorySettingsService businessInventorySettingsService;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User owner;
    private String branchId;
    private String itemId;
    private String goodsTypeId;
    private String batchEarlyExpiry;
    private String batchLateExpiry;

    @BeforeEach
    void seed() {
        stockMovementRepository.deleteAll();
        inventoryBatchRepository.deleteAll();
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
        b.setName("Picker Shop");
        b.setSlug("picker-shop");
        businessRepository.save(b);

        Branch br = new Branch();
        br.setBusinessId(TENANT);
        br.setName("Main");
        branchRepository.save(br);
        branchId = br.getId();

        catalogBootstrapService.seedDefaultItemTypesIfMissing(TENANT);
        goodsTypeId = itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(TENANT).getFirst().getId();

        permissionRepository.save(perm(P_READ, "catalog.items.read", "r"));
        permissionRepository.save(perm(P_INV_R, "inventory.read", "ir"));
        permissionRepository.save(perm(P_INV_W, "inventory.write", "iw"));

        Role ownerRole = new Role();
        ownerRole.setId(ROLE_OWNER);
        ownerRole.setBusinessId(null);
        ownerRole.setRoleKey("owner");
        ownerRole.setName("Owner");
        ownerRole.setSystem(true);
        roleRepository.save(ownerRole);
        for (String pid : List.of(P_READ, P_INV_R, P_INV_W)) {
            RolePermission rp = new RolePermission();
            rp.setId(new RolePermission.Id(ROLE_OWNER, pid));
            rolePermissionRepository.save(rp);
        }

        owner = new User();
        owner.setBusinessId(TENANT);
        owner.setEmail("owner-picker@test");
        owner.setName("Owner");
        owner.setRoleId(ROLE_OWNER);
        owner.setStatus(UserStatus.ACTIVE);
        owner.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(owner);

        itemId = itemCatalogService.createItem(
                TENANT,
                new CreateItemRequest(
                        "SKU-PICK", null, "Pick Item", null, goodsTypeId, null, null, null,
                        false, true, true,
                        null, null, null, null, null, null, null, null, null, null, true, null, null, null, null),
                null
        ).body().id();

        Item item = itemRepository.findById(itemId).orElseThrow();
        item.setCurrentStock(BigDecimal.ZERO);
        itemRepository.save(item);

        Instant base = Instant.parse("2026-01-10T12:00:00Z");
        batchEarlyExpiry = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1";
        batchLateExpiry = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2";
        LocalDate today = LocalDate.now();
        inventoryBatchRepository.save(batch(batchEarlyExpiry, itemId, base, today.plusDays(7), "5"));
        inventoryBatchRepository.save(batch(batchLateExpiry, itemId, base.plusSeconds(600), today.plusDays(90), "5"));

        item.setCurrentStock(new BigDecimal("10"));
        item.setHasExpiry(true);
        itemRepository.save(item);
    }

    @Test
    void allocationPreview_fefoOrdersByExpiry() throws Exception {
        String json = mockMvc.perform(get("/api/v1/inventory/allocation-preview")
                        .param("itemId", itemId)
                        .param("branchId", branchId)
                        .param("quantity", "2")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        List<BatchAllocationLine> lines = objectMapper.readValue(json, new TypeReference<>() {});
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().batchId()).isEqualTo(batchEarlyExpiry);
    }

    @Test
    void allocationPreview_lifoWhenBusinessSetting() {
        Item item = itemRepository.findById(itemId).orElseThrow();
        item.setHasExpiry(false);
        itemRepository.save(item);
        itemRepository.flush();

        Business biz = businessRepository.findById(TENANT).orElseThrow();
        biz.setSettings("{\"inventory\":{\"costMethod\":\"LIFO\"}}");
        businessRepository.save(biz);
        businessRepository.flush();

        assertThat(itemRepository.findById(itemId).orElseThrow().isHasExpiry()).isFalse();
        String rawSettings = businessRepository.findById(TENANT).orElseThrow().getSettings();
        assertThat(rawSettings).contains("LIFO");
        assertThat(businessInventorySettingsReader.costMethodFromSettingsJson(rawSettings))
                .isEqualTo(CostMethod.LIFO);

        List<BatchAllocationLine> lines = inventoryBatchPickerService.previewAllocation(
                TENANT,
                itemId,
                branchId,
                new BigDecimal("2")
        );
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().batchId()).isEqualTo(batchLateExpiry);
    }

    @Test
    void pickAndApply_decrementsBatchesAndStock() {
        String ref = inventoryBatchPickerService.newPickReferenceId();
        transactionTemplate.executeWithoutResult(status -> inventoryBatchPickerService.pickAndApplyPhysicalDecrement(
                TENANT,
                itemId,
                branchId,
                new BigDecimal("7"),
                InventoryConstants.REF_OPERATION,
                ref,
                owner.getId()
        ));

        InventoryBatch early = inventoryBatchRepository.findById(batchEarlyExpiry).orElseThrow();
        InventoryBatch late = inventoryBatchRepository.findById(batchLateExpiry).orElseThrow();
        assertThat(early.getQuantityRemaining().setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo("0");
        assertThat(late.getQuantityRemaining().setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo("3");

        Item item = itemRepository.findById(itemId).orElseThrow();
        assertThat(item.getCurrentStock().setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo("3");
        assertThat(stockMovementRepository.count()).isEqualTo(2);
    }

    @Test
    void secondPickFailsWhenStockExhausted() {
        Item item = itemRepository.findById(itemId).orElseThrow();
        item.setHasExpiry(false);
        itemRepository.save(item);
        inventoryBatchRepository.deleteAll();
        stockMovementRepository.deleteAll();

        String sole = UUID.randomUUID().toString();
        inventoryBatchRepository.save(batch(sole, itemId, Instant.parse("2026-02-01T12:00:00Z"), null, "1"));
        Item stocked = itemRepository.findById(itemId).orElseThrow();
        stocked.setCurrentStock(new BigDecimal("1"));
        itemRepository.save(stocked);

        transactionTemplate.executeWithoutResult(st ->
                inventoryBatchPickerService.pickAndApplyPhysicalDecrement(
                        TENANT,
                        itemId,
                        branchId,
                        BigDecimal.ONE,
                        InventoryConstants.REF_OPERATION,
                        inventoryBatchPickerService.newPickReferenceId(),
                        owner.getId()
                ));

        Throwable thrown = catchThrowable(() ->
                transactionTemplate.executeWithoutResult(st ->
                        inventoryBatchPickerService.pickAndApplyPhysicalDecrement(
                                TENANT,
                                itemId,
                                branchId,
                                BigDecimal.ONE,
                                InventoryConstants.REF_OPERATION,
                                inventoryBatchPickerService.newPickReferenceId(),
                                owner.getId()
                        )));
        assertThat(thrown).isNotNull();
        boolean foundRse = false;
        for (Throwable c = thrown; c != null; c = c.getCause()) {
            if (c instanceof ResponseStatusException) {
                foundRse = true;
                break;
            }
        }
        assertThat(foundRse).isTrue();
    }

    @Test
    void salePickAllowsNegativeStockWhenConfigured() {
        Business business = businessRepository.findById(TENANT).orElseThrow();
        business.setSettings("{\"inventory\":{\"stockLevels\":{\"allowNegativeStock\":true}}}");
        businessRepository.save(business);
        businessRepository.flush();

        String rawSettings = businessRepository.findById(TENANT).orElseThrow().getSettings();
        assertThat(rawSettings).contains("allowNegativeStock");
        assertThat(businessInventorySettingsService.readFromSettingsJson(rawSettings)
                .stockLevels().allowNegativeStock()).isTrue();

        Item item = itemRepository.findById(itemId).orElseThrow();
        item.setHasExpiry(false);
        itemRepository.save(item);
        inventoryBatchRepository.deleteAll();
        stockMovementRepository.deleteAll();

        String sole = UUID.randomUUID().toString();
        inventoryBatchRepository.save(batch(sole, itemId, Instant.parse("2026-02-01T12:00:00Z"), null, "0"));
        Item stocked = itemRepository.findById(itemId).orElseThrow();
        stocked.setCurrentStock(BigDecimal.ZERO);
        itemRepository.save(stocked);

        transactionTemplate.executeWithoutResult(st -> {
            List<BatchAllocationLine> lines = inventoryBatchPickerService.pickAndApplyPhysicalDecrement(
                    TENANT,
                    itemId,
                    branchId,
                    BigDecimal.ONE,
                    InventoryConstants.REF_OPERATION,
                    inventoryBatchPickerService.newPickReferenceId(),
                    InventoryConstants.MOVEMENT_SALE,
                    owner.getId()
            );
            assertThat(lines).hasSize(1);
            assertThat(lines.getFirst().quantity()).isEqualByComparingTo("1");
        });

        Item after = itemRepository.findById(itemId).orElseThrow();
        assertThat(after.getCurrentStock().setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo("-1");
    }

    @Test
    void salePickAllowsNegativeStockAfterBatchesDepleted() {
        Business business = businessRepository.findById(TENANT).orElseThrow();
        business.setSettings("{\"inventory\":{\"stockLevels\":{\"allowNegativeStock\":true}}}");
        businessRepository.save(business);

        Item item = itemRepository.findById(itemId).orElseThrow();
        item.setHasExpiry(false);
        itemRepository.save(item);
        inventoryBatchRepository.deleteAll();
        stockMovementRepository.deleteAll();

        String depletedBatchId = UUID.randomUUID().toString();
        InventoryBatch depleted = batch(
                depletedBatchId, itemId, Instant.parse("2026-02-01T12:00:00Z"), null, "0");
        depleted.setStatus(InventoryConstants.BATCH_STATUS_DEPLETED);
        inventoryBatchRepository.save(depleted);

        Item stocked = itemRepository.findById(itemId).orElseThrow();
        stocked.setCurrentStock(BigDecimal.ZERO);
        itemRepository.save(stocked);

        transactionTemplate.executeWithoutResult(st -> {
            List<BatchAllocationLine> lines = inventoryBatchPickerService.pickAndApplyPhysicalDecrement(
                    TENANT,
                    itemId,
                    branchId,
                    BigDecimal.ONE,
                    InventoryConstants.REF_OPERATION,
                    inventoryBatchPickerService.newPickReferenceId(),
                    InventoryConstants.MOVEMENT_SALE,
                    owner.getId()
            );
            assertThat(lines).hasSize(1);
            assertThat(lines.getFirst().batchId()).isEqualTo(depletedBatchId);
            assertThat(lines.getFirst().quantity()).isEqualByComparingTo("1");
        });

        Item after = itemRepository.findById(itemId).orElseThrow();
        assertThat(after.getCurrentStock().setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo("-1");
    }

    @Test
    void salePickRejectsExpiredOnlyStock() {
        Item item = itemRepository.findById(itemId).orElseThrow();
        item.setHasExpiry(true);
        itemRepository.save(item);
        inventoryBatchRepository.deleteAll();
        stockMovementRepository.deleteAll();

        String expiredId = UUID.randomUUID().toString();
        inventoryBatchRepository.save(batch(
                expiredId,
                itemId,
                Instant.parse("2026-01-01T12:00:00Z"),
                LocalDate.now().minusDays(3),
                "5"
        ));
        Item stocked = itemRepository.findById(itemId).orElseThrow();
        stocked.setCurrentStock(new BigDecimal("5"));
        itemRepository.save(stocked);

        Throwable thrown = catchThrowable(() ->
                transactionTemplate.executeWithoutResult(st ->
                        inventoryBatchPickerService.pickAndApplyPhysicalDecrement(
                                TENANT,
                                itemId,
                                branchId,
                                new BigDecimal("2"),
                                InventoryConstants.REF_OPERATION,
                                inventoryBatchPickerService.newPickReferenceId(),
                                owner.getId()
                        )));
        assertThat(thrown).isNotNull();
        ResponseStatusException rse = null;
        for (Throwable c = thrown; c != null; c = c.getCause()) {
            if (c instanceof ResponseStatusException ex) {
                rse = ex;
                break;
            }
        }
        assertThat(rse).isNotNull();
        assertThat(rse.getReason()).contains("No non-expired stock available");
    }

    @Test
    void stockTakeWriteDownCanConsumeExpiredStock() {
        Item item = itemRepository.findById(itemId).orElseThrow();
        item.setHasExpiry(true);
        itemRepository.save(item);
        inventoryBatchRepository.deleteAll();
        stockMovementRepository.deleteAll();

        String expiredId = UUID.randomUUID().toString();
        inventoryBatchRepository.save(batch(
                expiredId,
                itemId,
                Instant.parse("2026-01-01T12:00:00Z"),
                LocalDate.now().minusDays(3),
                "5"
        ));
        Item stocked = itemRepository.findById(itemId).orElseThrow();
        stocked.setCurrentStock(new BigDecimal("5"));
        itemRepository.save(stocked);

        transactionTemplate.executeWithoutResult(st ->
                inventoryBatchPickerService.pickAndApplyStockTakeDecrement(
                        TENANT,
                        itemId,
                        branchId,
                        new BigDecimal("2"),
                        InventoryConstants.REF_STOCK_ADJUSTMENT_REQUEST,
                        inventoryBatchPickerService.newPickReferenceId(),
                        owner.getId()
                ));

        InventoryBatch expired = inventoryBatchRepository.findById(expiredId).orElseThrow();
        assertThat(expired.getQuantityRemaining().setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo("3");
        Item after = itemRepository.findById(itemId).orElseThrow();
        assertThat(after.getCurrentStock().setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo("3");
    }

    @Test
    void stockTakeWriteDownPrefersNonExpiredBeforeExpired() {
        Item item = itemRepository.findById(itemId).orElseThrow();
        item.setHasExpiry(true);
        itemRepository.save(item);
        inventoryBatchRepository.deleteAll();
        stockMovementRepository.deleteAll();

        String freshId = UUID.randomUUID().toString();
        String expiredId = UUID.randomUUID().toString();
        inventoryBatchRepository.save(batch(
                freshId,
                itemId,
                Instant.parse("2026-02-01T12:00:00Z"),
                LocalDate.now().plusDays(30),
                "2"
        ));
        inventoryBatchRepository.save(batch(
                expiredId,
                itemId,
                Instant.parse("2026-01-01T12:00:00Z"),
                LocalDate.now().minusDays(1),
                "5"
        ));
        Item stocked = itemRepository.findById(itemId).orElseThrow();
        stocked.setCurrentStock(new BigDecimal("7"));
        itemRepository.save(stocked);

        transactionTemplate.executeWithoutResult(st ->
                inventoryBatchPickerService.pickAndApplyStockTakeDecrement(
                        TENANT,
                        itemId,
                        branchId,
                        new BigDecimal("3"),
                        InventoryConstants.REF_STOCK_ADJUSTMENT_REQUEST,
                        inventoryBatchPickerService.newPickReferenceId(),
                        owner.getId()
                ));

        InventoryBatch fresh = inventoryBatchRepository.findById(freshId).orElseThrow();
        InventoryBatch expired = inventoryBatchRepository.findById(expiredId).orElseThrow();
        assertThat(fresh.getQuantityRemaining().setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo("0");
        assertThat(expired.getQuantityRemaining().setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo("4");
    }

    private InventoryBatch batch(String id, String itId, Instant received, LocalDate expiry, String qty) {
        InventoryBatch b = new InventoryBatch();
        b.setId(id);
        b.setBusinessId(TENANT);
        b.setBranchId(branchId);
        b.setItemId(itId);
        b.setSupplierId(null);
        b.setBatchNumber("BN-" + id.substring(0, 8));
        b.setSourceType("test");
        b.setSourceId(UUID.randomUUID().toString());
        BigDecimal q = new BigDecimal(qty);
        b.setInitialQuantity(q);
        b.setQuantityRemaining(q);
        b.setUnitCost(new BigDecimal("2.00"));
        b.setReceivedAt(received);
        b.setExpiryDate(expiry);
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
