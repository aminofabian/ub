package zelisline.ub.sales.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.repository.BusinessCreditSettingsRepository;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CreditTransactionRepository;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.credits.repository.LoyaltyTransactionRepository;
import zelisline.ub.credits.repository.MpesaStkIntentRepository;
import zelisline.ub.credits.repository.PublicPaymentClaimRepository;
import zelisline.ub.credits.repository.WalletTransactionRepository;
import zelisline.ub.catalog.api.dto.CreateItemRequest;
import zelisline.ub.catalog.application.CatalogBootstrapService;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.finance.domain.JournalLine;
import zelisline.ub.finance.domain.LedgerAccount;
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
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.platform.security.TestAuthenticationFilter;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.domain.StockMovement;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.purchasing.repository.StockMovementRepository;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.domain.SalePayment;
import zelisline.ub.sales.repository.RefundLineRepository;
import zelisline.ub.sales.repository.RefundPaymentRepository;
import zelisline.ub.sales.repository.RefundRepository;
import zelisline.ub.sales.repository.SaleItemRepository;
import zelisline.ub.sales.repository.SalePaymentRepository;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.sales.repository.ShiftRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class SaleSlice2IT {

    private static final String TENANT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String P_SO = "11111111-0000-0000-0000-000000000064";
    private static final String P_SC = "11111111-0000-0000-0000-000000000065";
    private static final String P_SR = "11111111-0000-0000-0000-000000000066";
    private static final String P_SELL = "11111111-0000-0000-0000-000000000067";
    private static final String P_VOID_OWN = "11111111-0000-0000-0000-000000000068";
    private static final String P_VOID_ANY = "11111111-0000-0000-0000-000000000069";
    private static final String P_REFUND = "11111111-0000-0000-0000-000000000070";
    private static final String ROLE_POS = "22222222-0000-0000-0000-0000000000bb";
    private static final String ROLE_POS_OWNONLY = "22222222-0000-0000-0000-0000000000cc";

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
    private ShiftRepository shiftRepository;
    @Autowired
    private SaleRepository saleRepository;
    @Autowired
    private SaleItemRepository saleItemRepository;
    @Autowired
    private SalePaymentRepository salePaymentRepository;
    @Autowired
    private RefundPaymentRepository refundPaymentRepository;
    @Autowired
    private RefundLineRepository refundLineRepository;
    @Autowired
    private RefundRepository refundRepository;
    @Autowired
    private JournalLineRepository journalLineRepository;
    @Autowired
    private JournalEntryRepository journalEntryRepository;
    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private CustomerPhoneRepository customerPhoneRepository;
    @Autowired
    private CreditAccountRepository creditAccountRepository;
    @Autowired
    private CreditTransactionRepository creditTransactionRepository;
    @Autowired
    private WalletTransactionRepository walletTransactionRepository;
    @Autowired
    private LoyaltyTransactionRepository loyaltyTransactionRepository;
    @Autowired
    private MpesaStkIntentRepository mpesaStkIntentRepository;
    @Autowired
    private PublicPaymentClaimRepository publicPaymentClaimRepository;
    @Autowired
    private BusinessCreditSettingsRepository businessCreditSettingsRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User cashier;
    private String branchId;
    private String itemId;
    private String goodsTypeId;

    @BeforeEach
    void seed() {
        refundPaymentRepository.deleteAll();
        refundLineRepository.deleteAll();
        refundRepository.deleteAll();
        mpesaStkIntentRepository.deleteAll();
        publicPaymentClaimRepository.deleteAll();
        loyaltyTransactionRepository.deleteAll();
        walletTransactionRepository.deleteAll();
        creditTransactionRepository.deleteAll();
        salePaymentRepository.deleteAll();
        saleItemRepository.deleteAll();
        saleRepository.deleteAll();
        creditAccountRepository.deleteAll();
        customerPhoneRepository.deleteAll();
        customerRepository.deleteAll();
        journalLineRepository.deleteAll();
        journalEntryRepository.deleteAll();
        ledgerAccountRepository.deleteAll();
        stockMovementRepository.deleteAll();
        shiftRepository.deleteAll();
        inventoryBatchRepository.deleteAll();
        itemRepository.deleteAll();
        itemTypeRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        branchRepository.deleteAll();
        businessCreditSettingsRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("Sale Shop");
        b.setSlug("sale-shop");
        businessRepository.save(b);

        Branch br = new Branch();
        br.setBusinessId(TENANT);
        br.setName("Till");
        branchRepository.save(br);
        branchId = br.getId();

        catalogBootstrapService.seedDefaultItemTypesIfMissing(TENANT);
        goodsTypeId = itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(TENANT).getFirst().getId();

        permissionRepository.save(perm(P_SO, "shifts.open", "o"));
        permissionRepository.save(perm(P_SC, "shifts.close", "c"));
        permissionRepository.save(perm(P_SR, "shifts.read", "r"));
        permissionRepository.save(perm(P_SELL, "sales.sell", "s"));
        permissionRepository.save(perm(P_VOID_OWN, "sales.void.own", "vo"));
        permissionRepository.save(perm(P_VOID_ANY, "sales.void.any", "va"));
        permissionRepository.save(perm(P_REFUND, "sales.refund.create", "rf"));

        Role r = new Role();
        r.setId(ROLE_POS);
        r.setBusinessId(null);
        r.setRoleKey("pos_sale_test");
        r.setName("POS Sale Test");
        r.setSystem(true);
        roleRepository.save(r);
        for (String pid : List.of(P_SO, P_SC, P_SR, P_SELL, P_VOID_OWN, P_VOID_ANY, P_REFUND)) {
            RolePermission rp = new RolePermission();
            rp.setId(new RolePermission.Id(ROLE_POS, pid));
            rolePermissionRepository.save(rp);
        }

        Role ownOnly = new Role();
        ownOnly.setId(ROLE_POS_OWNONLY);
        ownOnly.setBusinessId(null);
        ownOnly.setRoleKey("pos_void_own_only");
        ownOnly.setName("POS Void Own Only");
        ownOnly.setSystem(true);
        roleRepository.save(ownOnly);
        for (String pid : List.of(P_SO, P_SC, P_SR, P_SELL, P_VOID_OWN)) {
            RolePermission rp = new RolePermission();
            rp.setId(new RolePermission.Id(ROLE_POS_OWNONLY, pid));
            rolePermissionRepository.save(rp);
        }

        cashier = new User();
        cashier.setBusinessId(TENANT);
        cashier.setEmail("cashier-sale@test");
        cashier.setName("Cashier");
        cashier.setRoleId(ROLE_POS);
        cashier.setBranchId(branchId);
        cashier.setStatus(UserStatus.ACTIVE);
        cashier.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(cashier);

        itemId = itemCatalogService.createItem(
                TENANT,
                new CreateItemRequest(
                        "SKU-SALE", null, "Sale Item", null, goodsTypeId, null, null, null,
                        false, true, true,
                        null, null, null, null, null, null, null, null, null, true, null
                ),
                null
        ).body().id();

        var item = itemRepository.findById(itemId).orElseThrow();
        item.setCurrentStock(BigDecimal.ZERO);
        item.setHasExpiry(true);
        itemRepository.save(item);

        Instant base = Instant.parse("2026-02-10T12:00:00Z");
        inventoryBatchRepository.save(batch("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1", itemId, base, LocalDate.of(2026, 4, 1), "5"));
        inventoryBatchRepository.save(batch("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2", itemId, base.plusSeconds(600), LocalDate.of(2026, 9, 1), "5"));
        item.setCurrentStock(new BigDecimal("10"));
        itemRepository.save(item);
    }

    @Test
    void postSale_cash_updatesDrawerStockAndIdempotentReplay() throws Exception {
        openShift(new BigDecimal("100.00"));

        String body = """
                {"branchId":"%s","lines":[{"itemId":"%s","quantity":2,"unitPrice":5}],"payments":[{"method":"cash","amount":10}]}
                """.formatted(branchId, itemId);
        String idemKey = "idem-" + UUID.randomUUID();

        MvcResult first = mockMvc.perform(post("/api/v1/sales")
                        .contentType(APPLICATION_JSON)
                        .content(body)
                        .header("Idempotency-Key", idemKey)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode firstJson = objectMapper.readTree(first.getResponse().getContentAsString());
        String saleId = firstJson.get("id").asText();
        assertThat(firstJson.get("grandTotal").decimalValue()).isEqualByComparingTo(new BigDecimal("10.00"));

        mockMvc.perform(post("/api/v1/sales")
                        .contentType(APPLICATION_JSON)
                        .content(body)
                        .header("Idempotency-Key", idemKey)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isOk());

        assertThat(saleRepository.count()).isEqualTo(1);
        Sale sale = saleRepository.findById(saleId).orElseThrow();
        List<JournalLine> lines = journalLineRepository.findByJournalEntryId(sale.getJournalEntryId());
        assertJournalBalanced(lines);
        assertThat(lines).hasSize(4);

        StockMovement sm = stockMovementRepository.findAll().stream()
                .filter(m -> InventoryConstants.MOVEMENT_SALE.equals(m.getMovementType()))
                .findFirst()
                .orElseThrow();
        assertThat(sm.getReferenceId()).isEqualTo(saleId);

        MvcResult cur = mockMvc.perform(get("/api/v1/shifts/current")
                        .param("branchId", branchId)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(cur.getResponse().getContentAsString())
                .get("expectedClosingCash")
                .decimalValue()).isEqualByComparingTo(new BigDecimal("110.00"));

        InventoryBatch early = inventoryBatchRepository.findById("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1").orElseThrow();
        assertThat(early.getQuantityRemaining()).isEqualByComparingTo(new BigDecimal("3"));
    }

    @Test
    void postSale_customerCredit_raisesBalanceAndPostsArDebit() throws Exception {
        String customerId = seedCustomerWithCreditAccount(new BigDecimal("1000.00"));
        openShift(new BigDecimal("50.00"));

        String body = """
                {"branchId":"%s","customerId":"%s","lines":[{"itemId":"%s","quantity":1,"unitPrice":10}],"payments":[{"method":"customer_credit","amount":10}]}
                """.formatted(branchId, customerId, itemId);

        MvcResult r = mockMvc.perform(post("/api/v1/sales")
                        .contentType(APPLICATION_JSON)
                        .content(body)
                        .header("Idempotency-Key", "tab-" + UUID.randomUUID())
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isCreated())
                .andReturn();

        String saleId = objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText();
        CreditAccount acc = creditAccountRepository.findByCustomerIdAndBusinessId(customerId, TENANT).orElseThrow();
        assertThat(acc.getBalanceOwed()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(creditTransactionRepository.findAll()).hasSize(1);

        Sale sale = saleRepository.findById(saleId).orElseThrow();
        assertThat(sale.getCustomerId()).isEqualTo(customerId);
        LedgerAccount ar = ledgerAccountRepository.findByBusinessIdAndCode(TENANT, "1100").orElseThrow();
        List<JournalLine> lines = journalLineRepository.findByJournalEntryId(sale.getJournalEntryId());
        assertJournalBalanced(lines);
        assertThat(lines).hasSize(4);
        boolean hasArDebit = lines.stream().anyMatch(l -> ar.getId().equals(l.getLedgerAccountId())
                && l.getDebit().compareTo(new BigDecimal("10.00")) == 0);
        assertThat(hasArDebit).isTrue();
    }

    @Test
    void voidSale_reversesCustomerCreditDebt() throws Exception {
        String customerId = seedCustomerWithCreditAccount(new BigDecimal("1000.00"));
        openShift(new BigDecimal("50.00"));

        String body = """
                {"branchId":"%s","customerId":"%s","lines":[{"itemId":"%s","quantity":1,"unitPrice":10}],"payments":[{"method":"customer_credit","amount":10}]}
                """.formatted(branchId, customerId, itemId);

        MvcResult r = mockMvc.perform(post("/api/v1/sales")
                        .contentType(APPLICATION_JSON)
                        .content(body)
                        .header("Idempotency-Key", "tab-void-" + UUID.randomUUID())
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isCreated())
                .andReturn();

        String saleId = objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post("/api/v1/sales/{saleId}/void", saleId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"notes\":\"wrong items\"}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isOk());

        CreditAccount acc = creditAccountRepository.findByCustomerIdAndBusinessId(customerId, TENANT).orElseThrow();
        assertThat(acc.getBalanceOwed()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(creditTransactionRepository.findAll()).hasSize(2);
    }

    @Test
    void postRefund_fullTabSale_reducesDebtAndKeepsDrawer() throws Exception {
        String customerId = seedCustomerWithCreditAccount(new BigDecimal("1000.00"));
        openShift(new BigDecimal("100.00"));

        String saleBody = """
                {"branchId":"%s","customerId":"%s","lines":[{"itemId":"%s","quantity":2,"unitPrice":5}],"payments":[{"method":"customer_credit","amount":10}]}
                """.formatted(branchId, customerId, itemId);

        MvcResult saleRes = mockMvc.perform(post("/api/v1/sales")
                        .contentType(APPLICATION_JSON)
                        .content(saleBody)
                        .header("Idempotency-Key", "tab-before-refund-" + UUID.randomUUID())
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode saleJson = objectMapper.readTree(saleRes.getResponse().getContentAsString());
        String saleId = saleJson.get("id").asText();
        String saleItemId = saleJson.get("items").get(0).get("id").asText();

        assertThat(creditAccountRepository.findByCustomerIdAndBusinessId(customerId, TENANT).orElseThrow().getBalanceOwed())
                .isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(creditTransactionRepository.findAll()).hasSize(1);

        String idem = "refund-tab-" + UUID.randomUUID();
        String refundBody = """
                {"lines":[{"saleItemId":"%s","quantity":2.0}],"payments":[{"method":"customer_credit","amount":10}],"reason":"return"}
                """.formatted(saleItemId);

        mockMvc.perform(post("/api/v1/sales/{saleId}/refund", saleId)
                        .contentType(APPLICATION_JSON)
                        .content(refundBody)
                        .header("Idempotency-Key", idem)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isOk());

        CreditAccount acc = creditAccountRepository.findByCustomerIdAndBusinessId(customerId, TENANT).orElseThrow();
        assertThat(acc.getBalanceOwed()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(creditTransactionRepository.findAll()).hasSize(2);

        Sale sale = saleRepository.findById(saleId).orElseThrow();
        assertThat(sale.getStatus()).isEqualTo(SalesConstants.SALE_STATUS_REFUNDED);
        assertThat(sale.getRefundedTotal()).isEqualByComparingTo(new BigDecimal("10.00"));

        MvcResult cur = mockMvc.perform(get("/api/v1/shifts/current")
                        .param("branchId", branchId)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(cur.getResponse().getContentAsString())
                .get("expectedClosingCash")
                .decimalValue()).isEqualByComparingTo(new BigDecimal("100.00"));

        var ref = refundRepository.findByBusinessIdAndIdempotencyKey(TENANT, idem).orElseThrow();
        assertJournalBalanced(journalLineRepository.findByJournalEntryId(ref.getJournalEntryId()));
    }

    private String seedCustomerWithCreditAccount(BigDecimal creditLimit) {
        Customer c = new Customer();
        c.setBusinessId(TENANT);
        c.setName("Credit buyer");
        customerRepository.save(c);

        CustomerPhone ph = new CustomerPhone();
        ph.setBusinessId(TENANT);
        ph.setCustomerId(c.getId());
        ph.setPhone("0700990001");
        ph.setPrimary(true);
        customerPhoneRepository.save(ph);

        CreditAccount a = new CreditAccount();
        a.setBusinessId(TENANT);
        a.setCustomerId(c.getId());
        a.setCreditLimit(creditLimit);
        creditAccountRepository.save(a);
        return c.getId();
    }

    @Test
    void postSale_mpesaManual_doesNotIncreaseDrawerExpectation() throws Exception {
        openShift(new BigDecimal("50.00"));

        String body = """
                {"branchId":"%s","lines":[{"itemId":"%s","quantity":1,"unitPrice":4}],"payments":[{"method":"mpesa_manual","amount":4,"reference":"ABC123"}]}
                """.formatted(branchId, itemId);

        mockMvc.perform(post("/api/v1/sales")
                        .contentType(APPLICATION_JSON)
                        .content(body)
                        .header("Idempotency-Key", "mpesa-" + UUID.randomUUID())
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isCreated());

        MvcResult cur = mockMvc.perform(get("/api/v1/shifts/current")
                        .param("branchId", branchId)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(cur.getResponse().getContentAsString())
                .get("expectedClosingCash")
                .decimalValue()).isEqualByComparingTo(new BigDecimal("50.00"));
    }

    @Test
    void postSale_splitCashAndMpesa_drawerIncrementsByCashPortionOnly() throws Exception {
        openShift(new BigDecimal("100.00"));

        String body = """
                {"branchId":"%s","lines":[{"itemId":"%s","quantity":2,"unitPrice":5}],"payments":[{"method":"cash","amount":6},{"method":"mpesa_manual","amount":4,"reference":"MPX1"}]}
                """.formatted(branchId, itemId);

        mockMvc.perform(post("/api/v1/sales")
                        .contentType(APPLICATION_JSON)
                        .content(body)
                        .header("Idempotency-Key", "split-" + UUID.randomUUID())
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isCreated());

        MvcResult cur = mockMvc.perform(get("/api/v1/shifts/current")
                        .param("branchId", branchId)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(cur.getResponse().getContentAsString())
                .get("expectedClosingCash")
                .decimalValue()).isEqualByComparingTo(new BigDecimal("106.00"));

        List<Sale> sales = saleRepository.findAll();
        assertThat(sales).hasSize(1);
        List<SalePayment> pays = salePaymentRepository.findBySaleIdOrderBySortOrderAsc(sales.getFirst().getId());
        assertThat(pays).hasSize(2);
        assertThat(pays.getFirst().getSortOrder()).isZero();
        assertThat(pays.get(1).getSortOrder()).isEqualTo(1);
    }

    @Test
    void postVoidSale_restoresInventoryAndDrawer_secondCallIdempotent() throws Exception {
        openShift(new BigDecimal("100.00"));

        String body = """
                {"branchId":"%s","lines":[{"itemId":"%s","quantity":2,"unitPrice":5}],"payments":[{"method":"cash","amount":10}]}
                """.formatted(branchId, itemId);

        MvcResult saleRes = mockMvc.perform(post("/api/v1/sales")
                        .contentType(APPLICATION_JSON)
                        .content(body)
                        .header("Idempotency-Key", "void-idem-" + UUID.randomUUID())
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode saleJson = objectMapper.readTree(saleRes.getResponse().getContentAsString());
        String saleId = saleJson.get("id").asText();

        InventoryBatch early = inventoryBatchRepository.findById("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1").orElseThrow();
        assertThat(early.getQuantityRemaining()).isEqualByComparingTo(new BigDecimal("3"));

        mockMvc.perform(post("/api/v1/sales/{saleId}/void", saleId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"notes\":\"wrong line\"}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isOk());

        Sale sale = saleRepository.findById(saleId).orElseThrow();
        assertThat(sale.getStatus()).isEqualTo(SalesConstants.SALE_STATUS_VOIDED);
        assertThat(sale.getVoidJournalEntryId()).isNotBlank();
        assertThat(sale.getVoidNotes()).isEqualTo("wrong line");

        InventoryBatch earlyAfterVoid = inventoryBatchRepository
                .findById("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1")
                .orElseThrow();
        assertThat(earlyAfterVoid.getQuantityRemaining()).isEqualByComparingTo(new BigDecimal("5"));

        var itemAfter = itemRepository.findById(itemId).orElseThrow();
        assertThat(itemAfter.getCurrentStock()).isEqualByComparingTo(new BigDecimal("10"));

        MvcResult cur = mockMvc.perform(get("/api/v1/shifts/current")
                        .param("branchId", branchId)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(cur.getResponse().getContentAsString())
                .get("expectedClosingCash")
                .decimalValue()).isEqualByComparingTo(new BigDecimal("100.00"));

        long voidMovements = stockMovementRepository.findAll().stream()
                .filter(m -> InventoryConstants.MOVEMENT_SALE_VOID.equals(m.getMovementType()))
                .count();
        assertThat(voidMovements).isEqualTo(1);

        List<JournalLine> voidLines = journalLineRepository.findByJournalEntryId(sale.getVoidJournalEntryId());
        assertJournalBalanced(voidLines);

        MvcResult second = mockMvc.perform(post("/api/v1/sales/{saleId}/void", saleId)
                        .contentType(APPLICATION_JSON)
                        .content("{}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode secondJson = objectMapper.readTree(second.getResponse().getContentAsString());
        assertThat(secondJson.get("voidedAt").isNull()).isFalse();
        assertThat(secondJson.get("voidJournalEntryId").asText()).isEqualTo(sale.getVoidJournalEntryId());

        assertThat(stockMovementRepository.findAll().stream()
                .filter(m -> InventoryConstants.MOVEMENT_SALE_VOID.equals(m.getMovementType()))
                .count()).isEqualTo(1);
    }

    @Test
    void postVoidSale_afterShiftClosed_returnsConflict() throws Exception {
        openShift(new BigDecimal("50.00"));

        String body = """
                {"branchId":"%s","lines":[{"itemId":"%s","quantity":1,"unitPrice":4}],"payments":[{"method":"cash","amount":4}]}
                """.formatted(branchId, itemId);

        MvcResult saleRes = mockMvc.perform(post("/api/v1/sales")
                        .contentType(APPLICATION_JSON)
                        .content(body)
                        .header("Idempotency-Key", "close-void-" + UUID.randomUUID())
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isCreated())
                .andReturn();

        String saleId = objectMapper.readTree(saleRes.getResponse().getContentAsString()).get("id").asText();

        MvcResult shiftRes = mockMvc.perform(get("/api/v1/shifts/current")
                        .param("branchId", branchId)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode shiftJson = objectMapper.readTree(shiftRes.getResponse().getContentAsString());
        String shiftId = shiftJson.get("id").asText();
        BigDecimal expected = shiftJson.get("expectedClosingCash").decimalValue();

        mockMvc.perform(post("/api/v1/shifts/{shiftId}/close", shiftId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"countedClosingCash\":%s}".formatted(expected.toPlainString()))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/sales/{saleId}/void", saleId)
                        .contentType(APPLICATION_JSON)
                        .content("{}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isConflict());
    }

    @Test
    void postVoidSale_ownOnly_forbidsVoidingAnotherUsersSale() throws Exception {
        User other = new User();
        other.setBusinessId(TENANT);
        other.setEmail("other-void@test");
        other.setName("Other");
        other.setRoleId(ROLE_POS_OWNONLY);
        other.setBranchId(branchId);
        other.setStatus(UserStatus.ACTIVE);
        other.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(other);

        openShift(new BigDecimal("80.00"));

        String body = """
                {"branchId":"%s","lines":[{"itemId":"%s","quantity":1,"unitPrice":5}],"payments":[{"method":"cash","amount":5}]}
                """.formatted(branchId, itemId);

        MvcResult saleRes = mockMvc.perform(post("/api/v1/sales")
                        .contentType(APPLICATION_JSON)
                        .content(body)
                        .header("Idempotency-Key", "own-void-" + UUID.randomUUID())
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isCreated())
                .andReturn();

        String saleId = objectMapper.readTree(saleRes.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post("/api/v1/sales/{saleId}/void", saleId)
                        .contentType(APPLICATION_JSON)
                        .content("{}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, other.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS_OWNONLY))
                .andExpect(status().isForbidden());

        assertThat(saleRepository.findById(saleId).orElseThrow().getStatus())
                .isEqualTo(SalesConstants.SALE_STATUS_COMPLETED);
    }

    @Test
    void postSale_paymentSumMismatch_returnsBadRequest() throws Exception {
        openShift(new BigDecimal("10"));

        String body = """
                {"branchId":"%s","lines":[{"itemId":"%s","quantity":1,"unitPrice":10}],"payments":[{"method":"cash","amount":9}]}
                """.formatted(branchId, itemId);

        mockMvc.perform(post("/api/v1/sales")
                        .contentType(APPLICATION_JSON)
                        .content(body)
                        .header("Idempotency-Key", "bad-" + UUID.randomUUID())
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postRefund_fullSale_restoresStockDrawer_journal_andReplayIdempotent() throws Exception {
        openShift(new BigDecimal("100.00"));

        String saleBody = """
                {"branchId":"%s","lines":[{"itemId":"%s","quantity":2,"unitPrice":5}],"payments":[{"method":"cash","amount":10}]}
                """.formatted(branchId, itemId);

        MvcResult saleRes = mockMvc.perform(post("/api/v1/sales")
                        .contentType(APPLICATION_JSON)
                        .content(saleBody)
                        .header("Idempotency-Key", "pre-refund-" + UUID.randomUUID())
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode saleJson = objectMapper.readTree(saleRes.getResponse().getContentAsString());
        String saleId = saleJson.get("id").asText();
        String saleItemId = saleJson.get("items").get(0).get("id").asText();

        String idem = "refund-idem-" + UUID.randomUUID();
        String refundBody = """
                {"lines":[{"saleItemId":"%s","quantity":2.0}],"payments":[{"method":"cash","amount":10}],"reason":"return"}
                """.formatted(saleItemId);

        mockMvc.perform(post("/api/v1/sales/{saleId}/refund", saleId)
                        .contentType(APPLICATION_JSON)
                        .content(refundBody)
                        .header("Idempotency-Key", idem)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isOk());

        Sale sale = saleRepository.findById(saleId).orElseThrow();
        assertThat(sale.getStatus()).isEqualTo(SalesConstants.SALE_STATUS_REFUNDED);
        assertThat(sale.getRefundedTotal()).isEqualByComparingTo(new BigDecimal("10.00"));

        InventoryBatch early = inventoryBatchRepository.findById("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1").orElseThrow();
        assertThat(early.getQuantityRemaining()).isEqualByComparingTo(new BigDecimal("5"));

        MvcResult cur = mockMvc.perform(get("/api/v1/shifts/current")
                        .param("branchId", branchId)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(cur.getResponse().getContentAsString())
                .get("expectedClosingCash")
                .decimalValue()).isEqualByComparingTo(new BigDecimal("100.00"));

        var ref = refundRepository.findByBusinessIdAndIdempotencyKey(TENANT, idem).orElseThrow();
        assertJournalBalanced(journalLineRepository.findByJournalEntryId(ref.getJournalEntryId()));

        mockMvc.perform(post("/api/v1/sales/{saleId}/refund", saleId)
                        .contentType(APPLICATION_JSON)
                        .content(refundBody)
                        .header("Idempotency-Key", idem)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isOk());

        assertThat(refundRepository.findAll()).hasSize(1);
    }

    @Test
    void postRefund_voidRejectedAfterRefund() throws Exception {
        openShift(new BigDecimal("100.00"));

        String saleBody = """
                {"branchId":"%s","lines":[{"itemId":"%s","quantity":2,"unitPrice":5}],"payments":[{"method":"cash","amount":10}]}
                """.formatted(branchId, itemId);

        MvcResult saleRes = mockMvc.perform(post("/api/v1/sales")
                        .contentType(APPLICATION_JSON)
                        .content(saleBody)
                        .header("Idempotency-Key", "sale-part-" + UUID.randomUUID())
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode saleJson = objectMapper.readTree(saleRes.getResponse().getContentAsString());
        String saleId = saleJson.get("id").asText();
        String saleItemId = saleJson.get("items").get(0).get("id").asText();

        String refundBody = """
                {"lines":[{"saleItemId":"%s","quantity":1}],"payments":[{"method":"cash","amount":5}],"reason":"partial"}
                """.formatted(saleItemId);

        mockMvc.perform(post("/api/v1/sales/{saleId}/refund", saleId)
                        .contentType(APPLICATION_JSON)
                        .content(refundBody)
                        .header("Idempotency-Key", "partial-r-" + UUID.randomUUID())
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/sales/{saleId}/void", saleId)
                        .contentType(APPLICATION_JSON)
                        .content("{}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isConflict());
    }

    @Test
    void postRefund_whenOriginalBatchInactive_createsRefundReturnBatch() throws Exception {
        openShift(new BigDecimal("100.00"));

        String saleBody = """
                {"branchId":"%s","lines":[{"itemId":"%s","quantity":2,"unitPrice":5}],"payments":[{"method":"cash","amount":10}]}
                """.formatted(branchId, itemId);

        MvcResult saleRes = mockMvc.perform(post("/api/v1/sales")
                        .contentType(APPLICATION_JSON)
                        .content(saleBody)
                        .header("Idempotency-Key", "inactive-b-" + UUID.randomUUID())
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode saleJson = objectMapper.readTree(saleRes.getResponse().getContentAsString());
        String saleId = saleJson.get("id").asText();
        String saleItemId = saleJson.get("items").get(0).get("id").asText();
        String batchId = saleJson.get("items").get(0).get("batchId").asText();

        InventoryBatch orig = inventoryBatchRepository.findById(batchId).orElseThrow();
        orig.setStatus("depleted");
        inventoryBatchRepository.save(orig);

        String refundBody = """
                {"lines":[{"saleItemId":"%s","quantity":2}],"payments":[{"method":"cash","amount":10}],"reason":"return"}
                """.formatted(saleItemId);

        mockMvc.perform(post("/api/v1/sales/{saleId}/refund", saleId)
                        .contentType(APPLICATION_JSON)
                        .content(refundBody)
                        .header("Idempotency-Key", "rr-batch-" + UUID.randomUUID())
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isOk());

        long rrBatches = inventoryBatchRepository.findAll().stream()
                .filter(b -> InventoryConstants.BATCH_SOURCE_REFUND_RETURN.equals(b.getSourceType()))
                .count();
        assertThat(rrBatches).isEqualTo(1);

        long refundMvt = stockMovementRepository.findAll().stream()
                .filter(m -> InventoryConstants.MOVEMENT_REFUND.equals(m.getMovementType()))
                .count();
        assertThat(refundMvt).isEqualTo(1);
    }

    @Test
    void getSaleReceiptPdf_returnsPdf() throws Exception {
        openShift(new BigDecimal("50.00"));
        String saleBody = """
                {"branchId":"%s","lines":[{"itemId":"%s","quantity":1,"unitPrice":7.5}],"payments":[{"method":"cash","amount":7.5}]}
                """.formatted(branchId, itemId);
        MvcResult saleRes = mockMvc.perform(post("/api/v1/sales")
                        .contentType(APPLICATION_JSON)
                        .content(saleBody)
                        .header("Idempotency-Key", "rcp-pdf-" + UUID.randomUUID())
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isCreated())
                .andReturn();
        String saleId = objectMapper.readTree(saleRes.getResponse().getContentAsString()).get("id").asText();

        MvcResult pdf = mockMvc.perform(get("/api/v1/sales/{saleId}/receipt.pdf", saleId)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString(MediaType.APPLICATION_PDF_VALUE)))
                .andReturn();

        assertThat(pdf.getResponse().getContentAsByteArray())
                .startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void getSaleReceiptThermal_returnsEscPosInit() throws Exception {
        openShift(new BigDecimal("40.00"));
        String saleBody = """
                {"branchId":"%s","lines":[{"itemId":"%s","quantity":1,"unitPrice":3}],"payments":[{"method":"cash","amount":3}]}
                """.formatted(branchId, itemId);
        MvcResult saleRes = mockMvc.perform(post("/api/v1/sales")
                        .contentType(APPLICATION_JSON)
                        .content(saleBody)
                        .header("Idempotency-Key", "rcp-th-" + UUID.randomUUID())
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isCreated())
                .andReturn();
        String saleId = objectMapper.readTree(saleRes.getResponse().getContentAsString()).get("id").asText();

        MvcResult raw = mockMvc.perform(get("/api/v1/sales/{saleId}/receipt/thermal", saleId)
                        .param("widthMm", "80")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE))
                .andReturn();

        byte[] body = raw.getResponse().getContentAsByteArray();
        assertThat(body.length).isGreaterThan(20);
        assertThat(body[0]).isEqualTo((byte) 0x1B);
        assertThat(body[1]).isEqualTo((byte) 0x40);
    }

    @Test
    void getSaleReceiptThermal_invalidWidth_returnsBadRequest() throws Exception {
        openShift(new BigDecimal("10.00"));
        String saleBody = """
                {"branchId":"%s","lines":[{"itemId":"%s","quantity":1,"unitPrice":5}],"payments":[{"method":"cash","amount":5}]}
                """.formatted(branchId, itemId);
        MvcResult saleRes = mockMvc.perform(post("/api/v1/sales")
                        .contentType(APPLICATION_JSON)
                        .content(saleBody)
                        .header("Idempotency-Key", "rcp-bw-" + UUID.randomUUID())
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isCreated())
                .andReturn();
        String saleId = objectMapper.readTree(saleRes.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/api/v1/sales/{saleId}/receipt/thermal", saleId)
                        .param("widthMm", "70")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postSale_clientSoldAtBeyondOneHourSkew_usesServerTime() throws Exception {
        openShift(new BigDecimal("100.00"));
        Instant clientClaim = Instant.now().minusSeconds(7200);
        String body = """
                {"branchId":"%s","lines":[{"itemId":"%s","quantity":1,"unitPrice":2}],"payments":[{"method":"cash","amount":2}],"clientSoldAt":"%s"}
                """.formatted(branchId, itemId, clientClaim);

        MvcResult res = mockMvc.perform(post("/api/v1/sales")
                        .contentType(APPLICATION_JSON)
                        .content(body)
                        .header("Idempotency-Key", "skew-" + UUID.randomUUID())
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isCreated())
                .andReturn();
        String saleId = objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText();
        Sale sale = saleRepository.findById(saleId).orElseThrow();
        assertThat(sale.getSoldAt()).isAfter(clientClaim.plusSeconds(3600));
        assertThat(Math.abs(Duration.between(sale.getSoldAt(), Instant.now()).toSeconds())).isLessThan(30L);
    }

    @Test
    void postSale_clientSoldAtWithinSkew_isPreserved() throws Exception {
        openShift(new BigDecimal("100.00"));
        Instant clientClaim = Instant.now().minusSeconds(600);
        String body = """
                {"branchId":"%s","lines":[{"itemId":"%s","quantity":1,"unitPrice":3}],"payments":[{"method":"cash","amount":3}],"clientSoldAt":"%s"}
                """.formatted(branchId, itemId, clientClaim);

        MvcResult res = mockMvc.perform(post("/api/v1/sales")
                        .contentType(APPLICATION_JSON)
                        .content(body)
                        .header("Idempotency-Key", "skew-ok-" + UUID.randomUUID())
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isCreated())
                .andReturn();
        String saleId = objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText();
        Sale sale = saleRepository.findById(saleId).orElseThrow();
        long msSkew = Math.abs(sale.getSoldAt().toEpochMilli() - clientClaim.toEpochMilli());
        assertThat(msSkew).isLessThan(2000L);
    }

    private void openShift(BigDecimal opening) throws Exception {
        mockMvc.perform(post("/api/v1/shifts/open")
                        .contentType(APPLICATION_JSON)
                        .content("{\"branchId\":\"%s\",\"openingCash\":%s}".formatted(branchId, opening.toPlainString()))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isCreated());
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

    private static void assertJournalBalanced(List<JournalLine> lines) {
        BigDecimal dr = BigDecimal.ZERO;
        BigDecimal cr = BigDecimal.ZERO;
        for (JournalLine l : lines) {
            dr = dr.add(l.getDebit());
            cr = cr.add(l.getCredit());
        }
        assertThat(dr).isEqualByComparingTo(cr);
    }
}
