package zelisline.ub.purchasing.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
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
import zelisline.ub.catalog.repository.IdempotencyKeyRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.finance.domain.JournalLine;
import zelisline.ub.finance.repository.JournalLineRepository;
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
import zelisline.ub.purchasing.repository.RawPurchaseLineRepository;
import zelisline.ub.purchasing.repository.RawPurchaseSessionRepository;
import zelisline.ub.purchasing.repository.StockMovementRepository;
import zelisline.ub.purchasing.repository.SupplierInvoiceLineRepository;
import zelisline.ub.purchasing.repository.SupplierInvoiceRepository;
import zelisline.ub.finance.repository.JournalEntryRepository;
import zelisline.ub.finance.repository.LedgerAccountRepository;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.repository.SupplierContactRepository;
import zelisline.ub.suppliers.repository.SupplierProductRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class PathBPurchaseIT {

    private static final String TENANT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String P_READ = "11111111-0000-0000-0000-000000000040";
    private static final String P_WRITE = "11111111-0000-0000-0000-000000000041";
    private static final String P_PATH_R = "11111111-0000-0000-0000-000000000047";
    private static final String P_PATH_W = "11111111-0000-0000-0000-000000000048";
    private static final String ROLE_OWNER = "22222222-0000-0000-0000-000000000001";

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
    private SupplierRepository supplierRepository;
    @Autowired
    private SupplierContactRepository supplierContactRepository;
    @Autowired
    private SupplierProductRepository supplierProductRepository;
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
    private SupplierInvoiceRepository supplierInvoiceRepository;
    @Autowired
    private SupplierInvoiceLineRepository supplierInvoiceLineRepository;
    @Autowired
    private StockMovementRepository stockMovementRepository;
    @Autowired
    private InventoryBatchRepository inventoryBatchRepository;
    @Autowired
    private RawPurchaseLineRepository rawPurchaseLineRepository;
    @Autowired
    private RawPurchaseSessionRepository rawPurchaseSessionRepository;
    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User owner;
    private String branchId;
    private String supplierId;
    private String itemId;
    private String goodsTypeId;

    @BeforeEach
    void seed() {
        journalLineRepository.deleteAll();
        journalEntryRepository.deleteAll();
        supplierInvoiceLineRepository.deleteAll();
        supplierInvoiceRepository.deleteAll();
        stockMovementRepository.deleteAll();
        inventoryBatchRepository.deleteAll();
        rawPurchaseLineRepository.deleteAll();
        rawPurchaseSessionRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();

        supplierProductRepository.deleteAll();
        supplierContactRepository.deleteAll();
        supplierRepository.deleteAll();
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
        b.setName("Path B Shop");
        b.setSlug("path-b-shop");
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
        permissionRepository.save(perm(P_PATH_R, "purchasing.path_b.read", "pr"));
        permissionRepository.save(perm(P_PATH_W, "purchasing.path_b.write", "pw"));

        Role ownerRole = new Role();
        ownerRole.setId(ROLE_OWNER);
        ownerRole.setBusinessId(null);
        ownerRole.setRoleKey("owner");
        ownerRole.setName("Owner");
        ownerRole.setSystem(true);
        roleRepository.save(ownerRole);
        for (String pid : List.of(P_READ, P_WRITE, P_PATH_R, P_PATH_W)) {
            RolePermission rp = new RolePermission();
            rp.setId(new RolePermission.Id(ROLE_OWNER, pid));
            rolePermissionRepository.save(rp);
        }

        owner = new User();
        owner.setBusinessId(TENANT);
        owner.setEmail("owner-pathb@test");
        owner.setName("Owner");
        owner.setRoleId(ROLE_OWNER);
        owner.setStatus(UserStatus.ACTIVE);
        owner.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(owner);

        Supplier sup = new Supplier();
        sup.setBusinessId(TENANT);
        sup.setName("Produce Ltd");
        sup.setSupplierType("distributor");
        sup.setStatus("active");
        supplierRepository.save(sup);
        supplierId = sup.getId();

        itemId = itemCatalogService.createItem(
                TENANT,
                new CreateItemRequest(
                        "SKU-TOM", null, "Tomatoes", null, goodsTypeId, null, null, null,
                        false, true, true,
                        null, null, null, null, null, null, null, null, null, null, null
                ),
                null
        ).body().id();
    }

    @Test
    void postPathBCreatesStockInvoiceAndBalancedJournal() throws Exception {
        String sessionId = createSession();
        String lineId = addLine(sessionId, "Crate tomatoes", "100.00");

        String postBody = """
                {"lines":[{"lineId":"%s","itemId":"%s","usableQty":90,"wastageQty":10}]}
                """.formatted(lineId, itemId);

        MvcResult r = mockMvc.perform(post("/api/v1/purchasing/path-b/sessions/" + sessionId + "/post")
                        .contentType(APPLICATION_JSON)
                        .content(postBody)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(r.getResponse().getContentAsString());
        String journalId = json.get("journalEntryId").asText();
        assertThat(json.get("grandTotal").decimalValue()).isEqualByComparingTo(new BigDecimal("100.00"));

        var item = itemRepository.findById(itemId).orElseThrow();
        assertThat(item.getCurrentStock().setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo(new BigDecimal("90.00"));

        assertThat(inventoryBatchRepository.count()).isEqualTo(1);
        assertThat(stockMovementRepository.count()).isEqualTo(2);

        List<JournalLine> lines = journalLineRepository.findByJournalEntryId(journalId);
        BigDecimal dr = BigDecimal.ZERO;
        BigDecimal cr = BigDecimal.ZERO;
        for (JournalLine jl : lines) {
            dr = dr.add(jl.getDebit());
            cr = cr.add(jl.getCredit());
        }
        assertThat(dr).isEqualByComparingTo(cr);

        BigDecimal invDr = lines.stream().map(JournalLine::getDebit).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(invDr).isEqualByComparingTo(new BigDecimal("100.00"));

        assertThat(supplierInvoiceRepository.count()).isEqualTo(1);
    }

    @Test
    void postPathBIdempotentReplayReturnsSamePayload() throws Exception {
        String sessionId = createSession();
        String lineId = addLine(sessionId, "Milk", "50.00");
        String postBody = """
                {"lines":[{"lineId":"%s","itemId":"%s","usableQty":50,"wastageQty":0}]}
                """.formatted(lineId, itemId);

        String key = "idem-path-b-1";
        MvcResult first = mockMvc.perform(post("/api/v1/purchasing/path-b/sessions/" + sessionId + "/post")
                        .contentType(APPLICATION_JSON)
                        .content(postBody)
                        .header("Idempotency-Key", key)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult second = mockMvc.perform(post("/api/v1/purchasing/path-b/sessions/" + sessionId + "/post")
                        .contentType(APPLICATION_JSON)
                        .content(postBody)
                        .header("Idempotency-Key", key)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(second.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(supplierInvoiceRepository.count()).isEqualTo(1);
    }

    private String createSession() throws Exception {
        String body = """
                {"supplierId":"%s","branchId":"%s","receivedAt":"%s","notes":"trip"}
                """.formatted(supplierId, branchId, Instant.parse("2026-05-01T10:00:00Z").toString());
        MvcResult r = mockMvc.perform(post("/api/v1/purchasing/path-b/sessions")
                        .contentType(APPLICATION_JSON)
                        .content(body)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText();
    }

    private String addLine(String sessionId, String description, String amount) throws Exception {
        String body = """
                {"description":"%s","amountMoney":%s}
                """.formatted(description, amount);
        MvcResult r = mockMvc.perform(post("/api/v1/purchasing/path-b/sessions/" + sessionId + "/lines")
                        .contentType(APPLICATION_JSON)
                        .content(body)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText();
    }

    private static Permission perm(String id, String key, String desc) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(desc);
        return p;
    }
}
