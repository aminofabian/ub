package zelisline.ub.inventory.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.domain.JournalLine;
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
class InventorySlice1IT {

    private static final String TENANT = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String P_READ = "11111111-0000-0000-0000-000000000040";
    private static final String P_WRITE = "11111111-0000-0000-0000-000000000041";
    private static final String P_INV_R = "11111111-0000-0000-0000-000000000054";
    private static final String P_INV_W = "11111111-0000-0000-0000-000000000055";
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
    private JournalLineRepository journalLineRepository;
    @Autowired
    private JournalEntryRepository journalEntryRepository;
    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;
    @Autowired
    private StockMovementRepository stockMovementRepository;
    @Autowired
    private InventoryBatchRepository inventoryBatchRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User owner;
    private String branchId;
    private String itemId;
    private String goodsTypeId;

    @BeforeEach
    void seed() {
        journalLineRepository.deleteAll();
        journalEntryRepository.deleteAll();
        stockMovementRepository.deleteAll();
        inventoryBatchRepository.deleteAll();
        itemRepository.deleteAll();
        itemTypeRepository.deleteAll();
        ledgerAccountRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        branchRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("Inv Shop");
        b.setSlug("inv-shop");
        businessRepository.save(b);

        Branch br = new Branch();
        br.setBusinessId(TENANT);
        br.setName("Main");
        branchRepository.save(br);
        branchId = br.getId();

        catalogBootstrapService.seedDefaultItemTypesIfMissing(TENANT);
        goodsTypeId = itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(TENANT).getFirst().getId();

        permissionRepository.save(perm(P_READ, "catalog.items.read", "r"));
        permissionRepository.save(perm(P_WRITE, "catalog.items.write", "w"));
        permissionRepository.save(perm(P_INV_R, "inventory.read", "ir"));
        permissionRepository.save(perm(P_INV_W, "inventory.write", "iw"));

        Role ownerRole = new Role();
        ownerRole.setId(ROLE_OWNER);
        ownerRole.setBusinessId(null);
        ownerRole.setRoleKey("owner");
        ownerRole.setName("Owner");
        ownerRole.setSystem(true);
        roleRepository.save(ownerRole);
        for (String pid : List.of(P_READ, P_WRITE, P_INV_R, P_INV_W)) {
            RolePermission rp = new RolePermission();
            rp.setId(new RolePermission.Id(ROLE_OWNER, pid));
            rolePermissionRepository.save(rp);
        }

        owner = new User();
        owner.setBusinessId(TENANT);
        owner.setEmail("owner-inv@test");
        owner.setName("Owner");
        owner.setRoleId(ROLE_OWNER);
        owner.setStatus(UserStatus.ACTIVE);
        owner.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(owner);

        itemId = itemCatalogService.createItem(
                TENANT,
                new CreateItemRequest(
                        "SKU-RICE", null, "Rice", null, goodsTypeId, null, null, null,
                        false, true, true,
                        null, null, null, null, null, null, null, null, null, null, null
                ),
                null
        ).body().id();

        seedLedger(TENANT);
    }

    @Test
    void openingBalanceThenBatchDecreaseAndWastage_balanceBooksAndStock() throws Exception {
        MvcResult openR = mockMvc.perform(post("/api/v1/inventory/opening-balance")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"branchId":"%s","itemId":"%s","quantity":10,"unitCost":2.50,"notes":"init"}
                                """.formatted(branchId, itemId))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode openJson = objectMapper.readTree(openR.getResponse().getContentAsString());
        String openJe = openJson.get("journalEntryId").asText();
        List<JournalLine> openLines = journalLineRepository.findByJournalEntryId(openJe);
        assertJournalBalanced(openLines);
        assertThat(sumDebit(openLines)).isEqualByComparingTo(new BigDecimal("25.00"));

        var itemAfterOpen = itemRepository.findById(itemId).orElseThrow();
        assertThat(itemAfterOpen.getCurrentStock().setScale(2, RoundingMode.HALF_UP))
                .isEqualByComparingTo("10.00");

        String batchId = inventoryBatchRepository.findAll().getFirst().getId();

        mockMvc.perform(post("/api/v1/inventory/batch-decrease")
                        .contentType(APPLICATION_JSON)
                        .content(
                                """
                                {"batchId":"%s","quantity":3,"reason":"sample"}
                                """.formatted(batchId))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated());

        var batchAfter = inventoryBatchRepository.findById(batchId).orElseThrow();
        assertThat(batchAfter.getQuantityRemaining().setScale(2, RoundingMode.HALF_UP))
                .isEqualByComparingTo("7.00");

        var itemAfterDec = itemRepository.findById(itemId).orElseThrow();
        assertThat(itemAfterDec.getCurrentStock().setScale(2, RoundingMode.HALF_UP))
                .isEqualByComparingTo("7.00");

        mockMvc.perform(post("/api/v1/inventory/wastage")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"branchId":"%s","itemId":"%s","quantity":2,"unitCost":2.50,"reason":"spoil"}
                                """.formatted(branchId, itemId))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated());

        var itemFinal = itemRepository.findById(itemId).orElseThrow();
        assertThat(itemFinal.getCurrentStock().setScale(2, RoundingMode.HALF_UP))
                .isEqualByComparingTo("5.00");

        assertThat(stockMovementRepository.count()).isEqualTo(3);
    }

    private void seedLedger(String businessId) {
        String[][] rows = {
                {LedgerAccountCodes.OPERATING_CASH, "Operating cash", "asset"},
                {LedgerAccountCodes.INVENTORY, "Inventory", "asset"},
                {LedgerAccountCodes.SUPPLIER_ADVANCES, "Supplier advances", "asset"},
                {LedgerAccountCodes.ACCOUNTS_PAYABLE, "AP", "liability"},
                {LedgerAccountCodes.GOODS_RECEIVED_NOT_INVOICED, "GRNI", "liability"},
                {LedgerAccountCodes.INVENTORY_SHRINKAGE, "Shrink", "expense"},
                {LedgerAccountCodes.PURCHASE_PRICE_VARIANCE, "PPV", "expense"},
                {LedgerAccountCodes.OPENING_BALANCE_EQUITY, "OBE", "equity"},
        };
        for (String[] row : rows) {
            var a = new zelisline.ub.finance.domain.LedgerAccount();
            a.setBusinessId(businessId);
            a.setCode(row[0]);
            a.setName(row[1]);
            a.setAccountType(row[2]);
            ledgerAccountRepository.save(a);
        }
    }

    private static Permission perm(String id, String key, String desc) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(desc);
        return p;
    }

    private static void assertJournalBalanced(List<JournalLine> lines) {
        BigDecimal dr = sumDebit(lines);
        BigDecimal cr = BigDecimal.ZERO;
        for (JournalLine jl : lines) {
            cr = cr.add(jl.getCredit());
        }
        assertThat(dr).isEqualByComparingTo(cr);
    }

    private static BigDecimal sumDebit(List<JournalLine> lines) {
        BigDecimal dr = BigDecimal.ZERO;
        for (JournalLine jl : lines) {
            dr = dr.add(jl.getDebit());
        }
        return dr;
    }
}
