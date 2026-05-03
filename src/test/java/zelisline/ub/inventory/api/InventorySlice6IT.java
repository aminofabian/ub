package zelisline.ub.inventory.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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

import com.fasterxml.jackson.databind.ObjectMapper;

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
import zelisline.ub.inventory.api.dto.BranchValuationLine;
import zelisline.ub.inventory.api.dto.InventoryValuationResponse;
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
class InventorySlice6IT {

    private static final String TENANT = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String P_READ = "11111111-0000-0000-0000-000000000041";
    private static final String P_INV_R = "11111111-0000-0000-0000-000000000057";
    private static final String ROLE_OWNER = "22222222-0000-0000-0000-000000000099";

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

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User owner;
    private String branchAId;
    private String branchBId;
    private String itemId;

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
        b.setName("Valuation Shop");
        b.setSlug("valuation-shop");
        businessRepository.save(b);

        Branch a = new Branch();
        a.setBusinessId(TENANT);
        a.setName("Alpha Branch");
        branchRepository.save(a);
        branchAId = a.getId();

        Branch brB = new Branch();
        brB.setBusinessId(TENANT);
        brB.setName("Beta Branch");
        branchRepository.save(brB);
        branchBId = brB.getId();

        catalogBootstrapService.seedDefaultItemTypesIfMissing(TENANT);
        String goodsTypeId = itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(TENANT).getFirst()
                .getId();

        permissionRepository.save(perm(P_READ, "catalog.items.read", "r"));
        permissionRepository.save(perm(P_INV_R, "inventory.read", "ir"));

        Role ownerRole = new Role();
        ownerRole.setId(ROLE_OWNER);
        ownerRole.setBusinessId(null);
        ownerRole.setRoleKey("owner");
        ownerRole.setName("Owner");
        ownerRole.setSystem(true);
        roleRepository.save(ownerRole);
        for (String pid : List.of(P_READ, P_INV_R)) {
            RolePermission rp = new RolePermission();
            rp.setId(new RolePermission.Id(ROLE_OWNER, pid));
            rolePermissionRepository.save(rp);
        }

        owner = new User();
        owner.setBusinessId(TENANT);
        owner.setEmail("owner-val@test");
        owner.setName("Owner");
        owner.setRoleId(ROLE_OWNER);
        owner.setStatus(UserStatus.ACTIVE);
        owner.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(owner);

        itemId = itemCatalogService.createItem(
                TENANT,
                new CreateItemRequest(
                        "SKU-VAL", null, "Val Item", null, goodsTypeId, null, null, null,
                        false, true, true,
                        null, null, null, null, null, null, null, null, null, false, null
                ),
                null
        ).body().id();

        Instant received = Instant.parse("2026-03-01T12:00:00Z");
        inventoryBatchRepository.save(batch("cccccccc-cccc-cccc-cccc-ccccccccaaa",
                branchAId, received, new BigDecimal("2"), new BigDecimal("5.00")));
        inventoryBatchRepository.save(batch("cccccccc-cccc-cccc-cccc-ccccccccbbb",
                branchBId, received, new BigDecimal("3"), new BigDecimal("4.00")));
    }

    @Test
    void valuation_sumsByBranchAndTotal() throws Exception {
        String json = mockMvc.perform(get("/api/v1/inventory/valuation")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        InventoryValuationResponse body = objectMapper.readValue(json, InventoryValuationResponse.class);
        assertThat(body.businessId()).isEqualTo(TENANT);
        assertThat(body.byBranch()).hasSize(2);
        assertThat(body.totalExtensionValue()).isEqualByComparingTo(new BigDecimal("22.00"));

        BranchValuationLine lineA = lineFor(body, branchAId);
        assertThat(lineA.branchName()).isEqualTo("Alpha Branch");
        assertThat(lineA.extensionValue()).isEqualByComparingTo(new BigDecimal("10.00"));

        BranchValuationLine lineB = lineFor(body, branchBId);
        assertThat(lineB.branchName()).isEqualTo("Beta Branch");
        assertThat(lineB.extensionValue()).isEqualByComparingTo(new BigDecimal("12.00"));
    }

    @Test
    void valuation_filteredByBranch() throws Exception {
        String json = mockMvc.perform(get("/api/v1/inventory/valuation")
                        .param("branchId", branchAId)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        InventoryValuationResponse body = objectMapper.readValue(json, InventoryValuationResponse.class);
        assertThat(body.byBranch()).hasSize(1);
        assertThat(body.totalExtensionValue()).isEqualByComparingTo(new BigDecimal("10.00"));
    }

    @Test
    void valuation_unknownBranch_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/valuation")
                        .param("branchId", UUID.randomUUID().toString())
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isBadRequest());
    }

    private static BranchValuationLine lineFor(InventoryValuationResponse body, String branchId) {
        return body.byBranch().stream()
                .filter(l -> l.branchId().equals(branchId))
                .findFirst()
                .orElseThrow();
    }

    private InventoryBatch batch(
            String id,
            String branchId,
            Instant receivedAt,
            BigDecimal qtyRemaining,
            BigDecimal unitCost
    ) {
        InventoryBatch b = new InventoryBatch();
        b.setId(id);
        b.setBusinessId(TENANT);
        b.setBranchId(branchId);
        b.setItemId(itemId);
        b.setSupplierId(null);
        b.setBatchNumber("BN-" + id.substring(0, 8));
        b.setSourceType("test");
        b.setSourceId(UUID.randomUUID().toString());
        b.setInitialQuantity(qtyRemaining);
        b.setQuantityRemaining(qtyRemaining);
        b.setUnitCost(unitCost);
        b.setReceivedAt(receivedAt);
        b.setExpiryDate(null);
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
