package zelisline.ub.purchasing.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
import org.springframework.test.web.servlet.ResultActions;

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
import zelisline.ub.purchasing.PurchasingConstants;
import zelisline.ub.purchasing.domain.SupplierInvoice;
import zelisline.ub.purchasing.repository.SupplierInvoiceRepository;
import zelisline.ub.purchasing.repository.SupplierPaymentAllocationRepository;
import zelisline.ub.purchasing.repository.SupplierPaymentRepository;
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
class SupplierPaymentIT {

    private static final String TENANT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String P_READ = "11111111-0000-0000-0000-000000000040";
    private static final String P_WRITE = "11111111-0000-0000-0000-000000000041";
    private static final String P_PAY_R = "11111111-0000-0000-0000-000000000051";
    private static final String P_PAY_W = "11111111-0000-0000-0000-000000000052";
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
    private SupplierPaymentRepository supplierPaymentRepository;
    @Autowired
    private SupplierPaymentAllocationRepository supplierPaymentAllocationRepository;
    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User owner;
    private String supplierId;
    private String invoiceIdA;
    private String invoiceIdB;

    @BeforeEach
    void seed() {
        journalLineRepository.deleteAll();
        journalEntryRepository.deleteAll();
        supplierPaymentAllocationRepository.deleteAll();
        supplierPaymentRepository.deleteAll();
        supplierInvoiceRepository.deleteAll();
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
        b.setName("Payment Shop");
        b.setSlug("payment-shop");
        businessRepository.save(b);

        Branch br = new Branch();
        br.setBusinessId(TENANT);
        br.setName("HQ");
        branchRepository.save(br);

        catalogBootstrapService.seedDefaultItemTypesIfMissing(TENANT);
        var goodsTypeId = itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(TENANT).getFirst().getId();

        permissionRepository.save(perm(P_READ, "catalog.items.read", "r"));
        permissionRepository.save(perm(P_WRITE, "catalog.items.write", "w"));
        permissionRepository.save(perm(P_PAY_R, "purchasing.payment.read", "pr"));
        permissionRepository.save(perm(P_PAY_W, "purchasing.payment.write", "pw"));

        Role ownerRole = new Role();
        ownerRole.setId(ROLE_OWNER);
        ownerRole.setBusinessId(null);
        ownerRole.setRoleKey("owner");
        ownerRole.setName("Owner");
        ownerRole.setSystem(true);
        roleRepository.save(ownerRole);
        for (String pid : List.of(P_READ, P_WRITE, P_PAY_R, P_PAY_W)) {
            RolePermission rp = new RolePermission();
            rp.setId(new RolePermission.Id(ROLE_OWNER, pid));
            rolePermissionRepository.save(rp);
        }

        owner = new User();
        owner.setBusinessId(TENANT);
        owner.setEmail("owner-pay@test");
        owner.setName("Owner");
        owner.setRoleId(ROLE_OWNER);
        owner.setStatus(UserStatus.ACTIVE);
        owner.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(owner);

        Supplier sup = new Supplier();
        sup.setBusinessId(TENANT);
        sup.setName("PayCo");
        sup.setSupplierType("distributor");
        sup.setStatus("active");
        supplierRepository.save(sup);
        supplierId = sup.getId();

        itemCatalogService.createItem(
                TENANT,
                new CreateItemRequest(
                        "X-1", null, "Item", null, goodsTypeId, null, null, null,
                        false, true, true, null, null, null, null, null, null, null, null, null, null, null
                ),
                null
        );

        invoiceIdA = newInvoice("INV-A", "300.00", LocalDate.of(2026, 6, 1));
        invoiceIdB = newInvoice("INV-B", "400.00", LocalDate.of(2026, 6, 15));
    }

    @Test
    void partialPaymentAcrossTwoInvoices() throws Exception {
        String body = paymentJson(500, BigDecimal.ZERO, List.of(
                new Alloc(invoiceIdA, "300.00"),
                new Alloc(invoiceIdB, "200.00")));
        postPayment(body).andExpect(status().isCreated());
        assertThat(supplierPaymentRepository.count()).isEqualTo(1);
        assertThat(supplierPaymentAllocationRepository.count()).isEqualTo(2);
        Supplier s = supplierRepository.findById(supplierId).orElseThrow();
        assertThat(s.getPrepaymentBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(openOn(invoiceIdA)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(openOn(invoiceIdB)).isEqualByComparingTo(new BigDecimal("200.00"));
    }

    @Test
    void overpaymentThenCreditConsumesPrepayment() throws Exception {
        supplierInvoiceRepository.deleteAll();
        String invC = newInvoice("INV-C", "400.00", LocalDate.of(2026, 7, 1));
        String invD = newInvoice("INV-D", "200.00", LocalDate.of(2026, 7, 10));

        postPayment(paymentJson(500, BigDecimal.ZERO, List.of(new Alloc(invC, "400.00")))).andExpect(status().isCreated());
        assertThat(supplierRepository.findById(supplierId).orElseThrow().getPrepaymentBalance())
                .isEqualByComparingTo(new BigDecimal("100.00"));

        postPayment(paymentJson(100, new BigDecimal("100"), List.of(new Alloc(invD, "200.00"))))
                .andExpect(status().isCreated());
        assertThat(supplierRepository.findById(supplierId).orElseThrow().getPrepaymentBalance())
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(openOn(invD)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void postPaymentIdempotentReplay() throws Exception {
        String body = paymentJson(100, BigDecimal.ZERO, List.of(new Alloc(invoiceIdA, "100.00")));
        String key = "pay-idem-1";
        postPayment(body, key).andExpect(status().isCreated());
        postPayment(body, key).andExpect(status().isCreated());
        assertThat(supplierPaymentRepository.count()).isEqualTo(1);
    }

    @Test
    void apAgingBucketsOpenBalances() throws Exception {
        supplierInvoiceRepository.deleteAll();
        newInvoice("OLD", "50.00", LocalDate.of(2025, 1, 1));
        newInvoice("NEW", "25.00", LocalDate.of(2027, 1, 1));

        MvcResult r = mockMvc.perform(get("/api/v1/purchasing/ap-aging")
                        .param("asOf", "2026-05-02")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode j = objectMapper.readTree(r.getResponse().getContentAsString());
        assertThat(j.get("totalOpen").decimalValue()).isEqualByComparingTo(new BigDecimal("75.00"));
        assertThat(j.get("buckets").get("daysOver90").decimalValue()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(j.get("buckets").get("current").decimalValue()).isEqualByComparingTo(new BigDecimal("25.00"));
    }

    @Test
    void openSupplierInvoicesListsOpenBalancesForSupplier() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/v1/purchasing/open-supplier-invoices")
                        .param("supplierId", supplierId)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode arr = objectMapper.readTree(r.getResponse().getContentAsString());
        assertThat(arr.isArray()).isTrue();
        assertThat(arr.size()).isEqualTo(2);
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode n : arr) {
            sum = sum.add(n.get("openBalance").decimalValue());
        }
        assertThat(sum).isEqualByComparingTo(new BigDecimal("700.00"));
    }

    private BigDecimal openOn(String invoiceId) {
        var inv = supplierInvoiceRepository.findById(invoiceId).orElseThrow();
        BigDecimal paid = supplierPaymentAllocationRepository.sumAmountBySupplierInvoiceId(invoiceId);
        return inv.getGrandTotal().subtract(paid);
    }

    private String newInvoice(String number, String total, LocalDate due) {
        SupplierInvoice inv = new SupplierInvoice();
        inv.setBusinessId(TENANT);
        inv.setSupplierId(supplierId);
        inv.setInvoiceNumber(number + "-" + System.nanoTime());
        inv.setInvoiceDate(LocalDate.of(2026, 4, 1));
        inv.setDueDate(due);
        BigDecimal t = new BigDecimal(total);
        inv.setSubtotal(t);
        inv.setTaxTotal(BigDecimal.ZERO);
        inv.setGrandTotal(t);
        inv.setStatus(PurchasingConstants.INVOICE_POSTED);
        supplierInvoiceRepository.save(inv);
        return inv.getId();
    }

    private String paymentJson(int cash, BigDecimal credit, List<Alloc> lines) {
        StringBuilder alloc = new StringBuilder("[");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                alloc.append(",");
            }
            alloc.append("{\"supplierInvoiceId\":\"").append(lines.get(i).invId).append("\",\"amount\":")
                    .append(lines.get(i).amt).append("}");
        }
        alloc.append("]");
        String creditStr = credit == null ? "0" : credit.toPlainString();
        return """
                {"supplierId":"%s","paidAt":"%s","paymentMethod":"cash","paymentAmount":%s,"creditApplied":%s,"allocations":%s}
                """.formatted(supplierId, Instant.parse("2026-05-15T12:00:00Z"), cash, creditStr, alloc);
    }

    private ResultActions postPayment(String body) throws Exception {
        return postPayment(body, null);
    }

    private ResultActions postPayment(String body, String idemKey) throws Exception {
        var req = post("/api/v1/purchasing/supplier-payments")
                .contentType(APPLICATION_JSON)
                .content(body)
                .header("X-Tenant-Id", TENANT)
                .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER);
        if (idemKey != null) {
            req = req.header("Idempotency-Key", idemKey);
        }
        return mockMvc.perform(req);
    }

    private record Alloc(String invId, String amt) {
    }

    @Test
    void journalBalancesOnPayment() throws Exception {
        String body = paymentJson(300, BigDecimal.ZERO, List.of(new Alloc(invoiceIdA, "300.00")));
        MvcResult r = postPayment(body).andExpect(status().isCreated()).andReturn();
        String jeId = objectMapper.readTree(r.getResponse().getContentAsString()).get("journalEntryId").asText();
        List<JournalLine> jl = journalLineRepository.findByJournalEntryId(jeId);
        BigDecimal dr = jl.stream().map(JournalLine::getDebit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cr = jl.stream().map(JournalLine::getCredit).reduce(BigDecimal.ZERO, BigDecimal::add);
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
