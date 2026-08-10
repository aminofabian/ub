package zelisline.ub.sales.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.application.CashDrawerLedgerService;
import zelisline.ub.sales.domain.CashDrawout;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.domain.SalePayment;
import zelisline.ub.sales.domain.Shift;
import zelisline.ub.sales.repository.CashDrawoutRepository;
import zelisline.ub.sales.repository.CashDrawerMovementRepository;
import zelisline.ub.sales.repository.SalePaymentRepository;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.sales.repository.ShiftRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * Phase 1 cash drawer ledger: seeding, live movement recording, lazy backfill
 * replay, and reconciliation of the ledger projection to expected_closing_cash.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class CashDrawerLedgerIT {

    private static final String TENANT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2";
    private static final String P_SO = "11111111-0000-0000-0000-000000000064";
    private static final String P_SC = "11111111-0000-0000-0000-000000000065";
    private static final String P_SR = "11111111-0000-0000-0000-000000000066";
    private static final String ROLE_POS = "22222222-0000-0000-0000-0000000000ac";

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
    private ShiftRepository shiftRepository;
    @Autowired
    private SaleRepository saleRepository;
    @Autowired
    private SalePaymentRepository salePaymentRepository;
    @Autowired
    private CashDrawoutRepository cashDrawoutRepository;
    @Autowired
    private CashDrawerMovementRepository movementRepository;
    @Autowired
    private CashDrawerLedgerService ledgerService;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User cashier;
    private String branchId;

    @BeforeEach
    void seed() {
        movementRepository.deleteAll();
        cashDrawoutRepository.deleteAll();
        salePaymentRepository.deleteAll();
        saleRepository.deleteAll();
        shiftRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        branchRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("Ledger Shop");
        b.setSlug("ledger-shop");
        businessRepository.save(b);

        Branch br = new Branch();
        br.setBusinessId(TENANT);
        br.setName("Till");
        branchRepository.save(br);
        branchId = br.getId();

        permissionRepository.save(perm(P_SO, "shifts.open", "o"));
        permissionRepository.save(perm(P_SC, "shifts.close", "c"));
        permissionRepository.save(perm(P_SR, "shifts.read", "r"));

        Role r = new Role();
        r.setId(ROLE_POS);
        r.setBusinessId(null);
        r.setRoleKey("pos_test");
        r.setName("POS Test");
        r.setSystem(true);
        roleRepository.save(r);
        for (String pid : List.of(P_SO, P_SC, P_SR)) {
            RolePermission rp = new RolePermission();
            rp.setId(new RolePermission.Id(ROLE_POS, pid));
            rolePermissionRepository.save(rp);
        }

        cashier = new User();
        cashier.setBusinessId(TENANT);
        cashier.setEmail("cashier-ledger@test");
        cashier.setName("Cashier");
        cashier.setRoleId(ROLE_POS);
        cashier.setBranchId(branchId);
        cashier.setStatus(UserStatus.ACTIVE);
        cashier.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(cashier);
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    private static Permission perm(String id, String key, String desc) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(desc);
        return p;
    }

    private String openShiftWithDenominations() throws Exception {
        mockMvc.perform(post("/api/v1/shifts/open")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"branchId":"%s","openingCash":8000.00,
                                 "denominations":[
                                   {"denomination":1000,"denominationType":"NOTE","quantity":5},
                                   {"denomination":500,"denominationType":"NOTE","quantity":2},
                                   {"denomination":200,"denominationType":"NOTE","quantity":5},
                                   {"denomination":100,"denominationType":"NOTE","quantity":10}]}
                                """.formatted(branchId))
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
        return objectMapper.readTree(cur.getResponse().getContentAsString()).get("id").asText();
    }

    private JsonNode fetchBalances(String shiftId) throws Exception {
        MvcResult res = mockMvc.perform(get("/api/v1/shifts/%s/drawer-balances".formatted(shiftId))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    private static long qty(JsonNode balances, int denom) {
        for (JsonNode row : balances.get("balances")) {
            if (row.get("denomination").asInt() == denom) {
                return row.get("quantity").asLong();
            }
        }
        return 0;
    }

    // ========================================================================
    // TESTS
    // ========================================================================

    @Test
    void openShift_withDenominations_seedsLedgerAndReconciles() throws Exception {
        String shiftId = openShiftWithDenominations();

        JsonNode balances = fetchBalances(shiftId);
        assertThat(balances.get("consistent").asBoolean()).isTrue();
        assertThat(balances.get("expectedClosingCash").decimalValue()).isEqualByComparingTo("8000.00");
        assertThat(balances.get("ledgerTotal").decimalValue()).isEqualByComparingTo("8000.00");
        assertThat(qty(balances, 1000)).isEqualTo(5);
        assertThat(qty(balances, 500)).isEqualTo(2);
        assertThat(qty(balances, 200)).isEqualTo(5);
        assertThat(qty(balances, 100)).isEqualTo(10);
        assertThat(movementRepository.countByShiftId(shiftId)).isEqualTo(4);
    }

    @Test
    void recordSale_recordsReceivedAndChangeMovements() throws Exception {
        String shiftId = openShiftWithDenominations();

        // Cash sale: 700 total, customer hands over 1,000 → 300 change.
        String saleId = java.util.UUID.randomUUID().toString();
        ledgerService.recordSale(shiftId, saleId, new BigDecimal("1000.00"), new BigDecimal("700.00"),
                new BigDecimal("700.00"), cashier.getId());

        // The SaleService applyDrawerCash path bumps expected by the cash payment sum.
        Shift shift = shiftRepository.findById(shiftId).orElseThrow();
        shift.setExpectedClosingCash(new BigDecimal("8700.00"));
        shiftRepository.save(shift);

        JsonNode balances = fetchBalances(shiftId);
        assertThat(balances.get("consistent").asBoolean()).isTrue();
        assertThat(balances.get("ledgerTotal").decimalValue()).isEqualByComparingTo("8700.00");
        // +1 × 1000 received; change 300 → −1 × 200, −1 × 100
        assertThat(qty(balances, 1000)).isEqualTo(6);
        assertThat(qty(balances, 200)).isEqualTo(4);
        assertThat(qty(balances, 100)).isEqualTo(9);
    }

    @Test
    void recordSale_walletOverpay_recordsReceivedWithoutChange() throws Exception {
        String shiftId = openShiftWithDenominations();

        // Customer pays 1,000 for a 700 sale; 300 credited to wallet — no physical change.
        String saleId = java.util.UUID.randomUUID().toString();
        ledgerService.recordSale(shiftId, saleId, null, new BigDecimal("700.00"),
                new BigDecimal("1000.00"), cashier.getId());

        Shift shift = shiftRepository.findById(shiftId).orElseThrow();
        shift.setExpectedClosingCash(new BigDecimal("9000.00"));
        shiftRepository.save(shift);

        JsonNode balances = fetchBalances(shiftId);
        assertThat(balances.get("consistent").asBoolean()).isTrue();
        assertThat(qty(balances, 1000)).isEqualTo(6);
        assertThat(qty(balances, 200)).isEqualTo(5);
        assertThat(qty(balances, 100)).isEqualTo(10);
    }

    @Test
    void recordDrawout_andReversal_movesExpectedBalances() throws Exception {
        String shiftId = openShiftWithDenominations();

        ledgerService.recordAmount(shiftId, CashDrawerLedgerService.EVENT_DRAWOUT,
                CashDrawerLedgerService.REF_DRAWOUT, java.util.UUID.randomUUID().toString(),
                new BigDecimal("500.00").negate(), CashDrawerLedgerService.CONFIDENCE_INFERRED, cashier.getId(), null);
        Shift shift = shiftRepository.findById(shiftId).orElseThrow();
        shift.setExpectedClosingCash(new BigDecimal("7500.00"));
        shiftRepository.save(shift);

        JsonNode afterDrawout = fetchBalances(shiftId);
        assertThat(afterDrawout.get("consistent").asBoolean()).isTrue();
        assertThat(qty(afterDrawout, 500)).isEqualTo(1);

        // Void the drawout — amount returns to the drawer.
        ledgerService.recordAmount(shiftId, CashDrawerLedgerService.EVENT_DRAWOUT_REVERSAL,
                CashDrawerLedgerService.REF_DRAWOUT, java.util.UUID.randomUUID().toString(),
                new BigDecimal("500.00"), CashDrawerLedgerService.CONFIDENCE_INFERRED, cashier.getId(), null);
        Shift fresh = shiftRepository.findById(shiftId).orElseThrow();
        fresh.setExpectedClosingCash(new BigDecimal("8000.00"));
        shiftRepository.save(fresh);

        JsonNode afterReversal = fetchBalances(shiftId);
        assertThat(afterReversal.get("consistent").asBoolean()).isTrue();
        assertThat(qty(afterReversal, 500)).isEqualTo(2);
    }

    @Test
    void preLedgerShift_lazilyBackfillsOpeningSalesAndDrawouts() {
        // Simulate a shift that predates the ledger (no movements at all).
        Shift shift = new Shift();
        shift.setBusinessId(TENANT);
        shift.setBranchId(branchId);
        shift.setOpenedBy(cashier.getId());
        shift.setTillDeviceKey("till-1");
        shift.setStatus(SalesConstants.SHIFT_STATUS_OPEN);
        shift.setOpeningCash(new BigDecimal("10000.00"));
        shift.setExpectedClosingCash(new BigDecimal("10200.00"));
        shiftRepository.save(shift);
        String shiftId = shift.getId();

        // Completed cash sale: 700 total, 1,000 handed over.
        Sale sale = new Sale();
        sale.setId(java.util.UUID.randomUUID().toString());
        sale.setBusinessId(TENANT);
        sale.setBranchId(branchId);
        sale.setShiftId(shiftId);
        sale.setStatus(SalesConstants.SALE_STATUS_COMPLETED);
        sale.setIdempotencyKey("idem-ledger-1");
        sale.setGrandTotal(new BigDecimal("700.00"));
        sale.setCashReceived(new BigDecimal("1000.00"));
        sale.setSoldBy(cashier.getId());
        sale.setReceiptNo(1L);
        saleRepository.save(sale);
        SalePayment payment = new SalePayment();
        payment.setSaleId(sale.getId());
        payment.setMethod(SalesConstants.PAYMENT_METHOD_CASH);
        payment.setAmount(new BigDecimal("700.00"));
        payment.setSortOrder(0);
        salePaymentRepository.save(payment);

        // Approved drawout of 500.
        CashDrawout drawout = new CashDrawout();
        drawout.setShiftId(shiftId);
        drawout.setCategory(SalesConstants.DRAWOUT_CATEGORY_PETTY_CASH);
        drawout.setAmount(new BigDecimal("500.00"));
        drawout.setDescription("Petty cash");
        drawout.setRecipientName("Clerk");
        drawout.setStatus(SalesConstants.DRAWOUT_STATUS_APPROVED);
        drawout.setApprovalTier(SalesConstants.APPROVAL_TIER_1);
        drawout.setInitiatedBy(cashier.getId());
        drawout.setApprovedBy(cashier.getId());
        cashDrawoutRepository.save(drawout);

        // First projection triggers backfill and must reconcile.
        JsonNode balances = objectMapper.valueToTree(ledgerService.drawerBalances(shift));
        assertThat(balances.get("consistent").asBoolean()).isTrue();
        assertThat(balances.get("ledgerTotal").decimalValue()).isEqualByComparingTo("10200.00");
        // Opening 10,000 → 10 × 1,000; sale +1 × 1,000, change −1 × 200 − 1 × 100; drawout −1 × 500
        assertThat(qty(balances, 1000)).isEqualTo(11);
        assertThat(qty(balances, 500)).isEqualTo(-1);
        assertThat(qty(balances, 200)).isEqualTo(-1);
        assertThat(qty(balances, 100)).isEqualTo(-1);

        long recorded = movementRepository.countByShiftId(shiftId);

        // Replay must be idempotent — a second projection adds nothing.
        ledgerService.drawerBalances(shift);
        assertThat(movementRepository.countByShiftId(shiftId)).isEqualTo(recorded);
    }

    @Test
    void closeShift_withDenominations_keepsLedgerReconciled() throws Exception {
        String shiftId = openShiftWithDenominations();

        mockMvc.perform(post("/api/v1/shifts/%s/close".formatted(shiftId))
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"countedClosingCash":8000.00,
                                 "denominations":[
                                   {"denomination":1000,"denominationType":"NOTE","quantity":5},
                                   {"denomination":500,"denominationType":"NOTE","quantity":2},
                                   {"denomination":200,"denominationType":"NOTE","quantity":5},
                                   {"denomination":100,"denominationType":"NOTE","quantity":10}]}
                                """)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isOk());

        JsonNode balances = fetchBalances(shiftId);
        assertThat(balances.get("consistent").asBoolean()).isTrue();
        assertThat(balances.get("expectedClosingCash").decimalValue()).isEqualByComparingTo("8000.00");
        assertThat(qty(balances, 1000)).isEqualTo(5);
    }
}
