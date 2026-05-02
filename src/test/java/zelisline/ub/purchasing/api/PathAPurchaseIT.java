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

import jakarta.persistence.EntityManager;

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
import zelisline.ub.purchasing.repository.GoodsReceiptLineRepository;
import zelisline.ub.purchasing.repository.GoodsReceiptRepository;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.purchasing.repository.PurchaseOrderRepository;
import zelisline.ub.purchasing.repository.StockMovementRepository;
import zelisline.ub.purchasing.repository.SupplierInvoiceLineRepository;
import zelisline.ub.purchasing.repository.SupplierInvoiceRepository;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.repository.SupplierContactRepository;
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
class PathAPurchaseIT {

    private static final String TENANT = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    private static final String P_READ = "11111111-0000-0000-0000-000000000040";
    private static final String P_WRITE = "11111111-0000-0000-0000-000000000041";
    private static final String P_PATH_AR = "11111111-0000-0000-0000-000000000049";
    private static final String P_PATH_AW = "11111111-0000-0000-0000-000000000050";
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
    private GoodsReceiptLineRepository goodsReceiptLineRepository;
    @Autowired
    private GoodsReceiptRepository goodsReceiptRepository;
    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;
    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private EntityManager entityManager;

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
        goodsReceiptLineRepository.deleteAll();
        goodsReceiptRepository.deleteAll();
        inventoryBatchRepository.deleteAll();
        purchaseOrderRepository.deleteAll();
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
        b.setName("Path A Shop");
        b.setSlug("path-a-shop");
        b.setSettings("{}");
        businessRepository.save(b);

        Branch br = new Branch();
        br.setBusinessId(TENANT);
        br.setName("Warehouse");
        branchRepository.save(br);
        branchId = br.getId();

        catalogBootstrapService.seedDefaultItemTypesIfMissing(TENANT);
        goodsTypeId = itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(TENANT).getFirst().getId();

        permissionRepository.save(perm(P_READ, "catalog.items.read", "r"));
        permissionRepository.save(perm(P_WRITE, "catalog.items.write", "w"));
        permissionRepository.save(perm(P_PATH_AR, "purchasing.path_a.read", "par"));
        permissionRepository.save(perm(P_PATH_AW, "purchasing.path_a.write", "paw"));

        Role ownerRole = new Role();
        ownerRole.setId(ROLE_OWNER);
        ownerRole.setBusinessId(null);
        ownerRole.setRoleKey("owner");
        ownerRole.setName("Owner");
        ownerRole.setSystem(true);
        roleRepository.save(ownerRole);
        for (String pid : List.of(P_READ, P_WRITE, P_PATH_AR, P_PATH_AW)) {
            RolePermission rp = new RolePermission();
            rp.setId(new RolePermission.Id(ROLE_OWNER, pid));
            rolePermissionRepository.save(rp);
        }

        owner = new User();
        owner.setBusinessId(TENANT);
        owner.setEmail("owner-patha@test");
        owner.setName("Owner");
        owner.setRoleId(ROLE_OWNER);
        owner.setStatus(UserStatus.ACTIVE);
        owner.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(owner);

        Supplier sup = new Supplier();
        sup.setBusinessId(TENANT);
        sup.setName("Formal Foods");
        sup.setSupplierType("distributor");
        sup.setStatus("active");
        supplierRepository.save(sup);
        supplierId = sup.getId();

        itemId = itemCatalogService.createItem(
                TENANT,
                new CreateItemRequest(
                        "SKU-RICE", null, "Rice 50kg", null, goodsTypeId, null, null, null,
                        false, true, true,
                        null, null, null, null, null, null, null, null, null, null, null
                ),
                null
        ).body().id();
    }

    @Test
    void partialReceiveThenInvoice_balancedJournalAndStock() throws Exception {
        String poId = createPo();
        String poLineId = addPoLine(poId, "100", "10");
        sendPo(poId);

        String grnBody = """
                {"purchaseOrderId":"%s","branchId":"%s","receivedAt":"%s","lines":[
                  {"purchaseOrderLineId":"%s","qtyReceived":40}
                ]}
                """.formatted(poId, branchId, Instant.parse("2026-05-10T08:00:00Z"), poLineId);

        MvcResult grnRes = mockMvc.perform(post("/api/v1/purchasing/path-a/goods-receipts")
                        .contentType(APPLICATION_JSON)
                        .content(grnBody)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode grnJson = objectMapper.readTree(grnRes.getResponse().getContentAsString());
        String grnId = grnJson.get("goodsReceiptId").asText();
        assertThat(grnJson.get("grniAmount").decimalValue()).isEqualByComparingTo(new BigDecimal("400.00"));

        assertThat(itemRepository.findById(itemId).orElseThrow().getCurrentStock())
                .isEqualByComparingTo(new BigDecimal("40.0000"));

        List<JournalLine> grnJ = journalLineRepository.findByJournalEntryId(
                journalEntryRepository.findAll().stream()
                        .filter(e -> grnId.equals(e.getSourceId()))
                        .findFirst()
                        .orElseThrow()
                        .getId());
        assertDebitCreditEqual(grnJ);

        String invBody = """
                {"invoiceNumber":"INV-PA-1","invoiceDate":"2026-05-10","lines":[
                  {"itemId":"%s","qty":40,"unitCost":10,"lineTotal":400.00}
                ]}
                """.formatted(itemId);

        MvcResult invRes = mockMvc.perform(post(
                "/api/v1/purchasing/path-a/goods-receipts/" + grnId + "/supplier-invoice")
                        .contentType(APPLICATION_JSON)
                        .content(invBody)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode invJson = objectMapper.readTree(invRes.getResponse().getContentAsString());
        String jeId = invJson.get("journalEntryId").asText();
        List<JournalLine> invJ = journalLineRepository.findByJournalEntryId(jeId);
        assertDebitCreditEqual(invJ);

        String grnBody2 = """
                {"purchaseOrderId":"%s","branchId":"%s","receivedAt":"%s","lines":[
                  {"purchaseOrderLineId":"%s","qtyReceived":60}
                ]}
                """.formatted(poId, branchId, Instant.parse("2026-05-11T08:00:00Z"), poLineId);
        mockMvc.perform(post("/api/v1/purchasing/path-a/goods-receipts")
                        .contentType(APPLICATION_JSON)
                        .content(grnBody2)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk());

        assertThat(itemRepository.findById(itemId).orElseThrow().getCurrentStock().setScale(2, RoundingMode.HALF_UP))
                .isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void threeWayBlock_rejectsInvoiceTotalMismatch() throws Exception {
        Business b = businessRepository.findById(TENANT).orElseThrow();
        b.setSettings("{\"threeWayMatchMode\":\"block\"}");
        businessRepository.saveAndFlush(b);
        entityManager.clear();

        String poId = createPo();
        String poLineId = addPoLine(poId, "50", "20");
        sendPo(poId);

        String grnBody = """
                {"purchaseOrderId":"%s","branchId":"%s","receivedAt":"%s","lines":[
                  {"purchaseOrderLineId":"%s","qtyReceived":10}
                ]}
                """.formatted(poId, branchId, Instant.parse("2026-06-01T10:00:00Z"), poLineId);

        MvcResult grnRes = mockMvc.perform(post("/api/v1/purchasing/path-a/goods-receipts")
                        .contentType(APPLICATION_JSON)
                        .content(grnBody)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andReturn();
        String grnId = objectMapper.readTree(grnRes.getResponse().getContentAsString()).get("goodsReceiptId").asText();

        String invBody = """
                {"invoiceNumber":"INV-BAD","invoiceDate":"2026-06-01","lines":[
                  {"itemId":"%s","qty":10,"unitCost":25,"lineTotal":250.00}
                ]}
                """.formatted(itemId);

        mockMvc.perform(post("/api/v1/purchasing/path-a/goods-receipts/" + grnId + "/supplier-invoice")
                        .contentType(APPLICATION_JSON)
                        .content(invBody)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cancelledPurchaseOrder_cannotReceive() throws Exception {
        String poId = createPo();
        addPoLine(poId, "10", "5");
        sendPo(poId);

        mockMvc.perform(post("/api/v1/purchasing/path-a/purchase-orders/" + poId + "/cancel")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk());

        String poLineId = objectMapper.readTree(
                mockMvc.perform(get("/api/v1/purchasing/path-a/purchase-orders/" + poId)
                                        .header("X-Tenant-Id", TENANT)
                                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString()
        ).get("lines").get(0).get("id").asText();

        String grnBody = """
                {"purchaseOrderId":"%s","branchId":"%s","receivedAt":"%s","lines":[
                  {"purchaseOrderLineId":"%s","qtyReceived":2}
                ]}
                """.formatted(poId, branchId, Instant.parse("2026-06-02T10:00:00Z"), poLineId);

        mockMvc.perform(post("/api/v1/purchasing/path-a/goods-receipts")
                        .contentType(APPLICATION_JSON)
                        .content(grnBody)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isConflict());
    }

    @Test
    void sendPurchaseOrderWithoutLinesIsBadRequest() throws Exception {
        String poId = createPo();
        mockMvc.perform(post("/api/v1/purchasing/path-a/purchase-orders/" + poId + "/send")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isBadRequest());
    }

    private String createPo() throws Exception {
        String body = """
                {"supplierId":"%s","branchId":"%s","expectedDate":"2026-05-01"}
                """.formatted(supplierId, branchId);
        MvcResult r = mockMvc.perform(post("/api/v1/purchasing/path-a/purchase-orders")
                        .contentType(APPLICATION_JSON)
                        .content(body)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText();
    }

    private String addPoLine(String poId, String qtyOrdered, String unitCost) throws Exception {
        String body = """
                {"itemId":"%s","qtyOrdered":%s,"unitEstimatedCost":%s}
                """.formatted(itemId, qtyOrdered, unitCost);
        MvcResult r = mockMvc.perform(post("/api/v1/purchasing/path-a/purchase-orders/" + poId + "/lines")
                        .contentType(APPLICATION_JSON)
                        .content(body)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText();
    }

    private void sendPo(String poId) throws Exception {
        mockMvc.perform(post("/api/v1/purchasing/path-a/purchase-orders/" + poId + "/send")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk());
    }

    private static void assertDebitCreditEqual(List<JournalLine> lines) {
        BigDecimal dr = BigDecimal.ZERO;
        BigDecimal cr = BigDecimal.ZERO;
        for (JournalLine jl : lines) {
            dr = dr.add(jl.getDebit());
            cr = cr.add(jl.getCredit());
        }
        assertThat(dr).isEqualByComparingTo(cr);
    }

    private static Permission perm(String id, String key, String desc) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(desc);
        return p;
    }
}
