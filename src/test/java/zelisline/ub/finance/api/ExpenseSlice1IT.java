package zelisline.ub.finance.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.domain.Expense;
import zelisline.ub.finance.domain.JournalLine;
import zelisline.ub.finance.repository.ExpenseRepository;
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
class ExpenseSlice1IT {

    private static final String TENANT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2";
    private static final String P_EW = "11111111-0000-0000-0000-000000000190";
    private static final String P_ER = "11111111-0000-0000-0000-000000000191";
    private static final String P_SO = "11111111-0000-0000-0000-000000000064";
    private static final String ROLE_POS = "22222222-0000-0000-0000-0000000000ab";

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
    private LedgerAccountRepository ledgerAccountRepository;
    @Autowired
    private JournalLineRepository journalLineRepository;
    @Autowired
    private ExpenseRepository expenseRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User cashier;
    private String branchId;

    @BeforeEach
    void seed() {
        journalLineRepository.deleteAll();
        expenseRepository.deleteAll();
        shiftRepository.deleteAll();
        ledgerAccountRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        branchRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("Expense Shop");
        b.setSlug("expense-shop");
        businessRepository.save(b);

        Branch br = new Branch();
        br.setBusinessId(TENANT);
        br.setName("Till");
        branchRepository.save(br);
        branchId = br.getId();

        permissionRepository.save(perm(P_EW, "finance.expenses.write", "w"));
        permissionRepository.save(perm(P_ER, "finance.expenses.read", "r"));
        permissionRepository.save(perm(P_SO, "shifts.open", "o"));

        Role r = new Role();
        r.setId(ROLE_POS);
        r.setBusinessId(null);
        r.setRoleKey("pos_expense_test");
        r.setName("POS Expense Test");
        r.setSystem(true);
        roleRepository.save(r);
        for (String pid : List.of(P_EW, P_ER, P_SO)) {
            RolePermission rp = new RolePermission();
            rp.setId(new RolePermission.Id(ROLE_POS, pid));
            rolePermissionRepository.save(rp);
        }

        cashier = new User();
        cashier.setBusinessId(TENANT);
        cashier.setEmail("cashier-expense@test");
        cashier.setName("Cashier");
        cashier.setRoleId(ROLE_POS);
        cashier.setBranchId(branchId);
        cashier.setStatus(UserStatus.ACTIVE);
        cashier.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(cashier);
    }

    @Test
    void recordCashDrawerExpense_postsBalancedJournal_andDecrementsShiftExpectedCash() throws Exception {
        mockMvc.perform(post("/api/v1/shifts/open")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"branchId":"%s","openingCash":100.00,"notes":"morning"}
                                """.formatted(branchId))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isCreated());

        LocalDate day = LocalDate.now();
        MvcResult res = mockMvc.perform(post("/api/v1/finance/expenses")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "expenseDate":"%s",
                                  "name":"Petty cash soap",
                                  "categoryType":"variable",
                                  "amount":10.00,
                                  "paymentMethod":"cash",
                                  "includeInCashDrawer":true,
                                  "branchId":"%s"
                                }
                                """.formatted(day, branchId))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(res.getResponse().getContentAsString());
        String expenseId = json.get("id").asText();
        String journalEntryId = json.get("journalEntryId").asText();
        assertThat(journalEntryId).isNotBlank();

        Expense saved = expenseRepository.findByIdAndBusinessId(expenseId, TENANT).orElseThrow();
        assertThat(saved.isIncludeInCashDrawer()).isTrue();
        assertThat(saved.getPaymentMethod()).isEqualTo("cash");

        BigDecimal expected = shiftRepository
                .findByBusinessIdAndBranchIdAndStatus(TENANT, branchId, "open")
                .orElseThrow()
                .getExpectedClosingCash();
        assertThat(expected).isEqualByComparingTo(new BigDecimal("90.00"));

        List<JournalLine> lines = journalLineRepository.findByJournalEntryId(journalEntryId);
        assertJournalBalanced(lines);

        String cashId = ledgerAccountRepository.findByBusinessIdAndCode(TENANT, LedgerAccountCodes.OPERATING_CASH)
                .orElseThrow()
                .getId();
        String expId = ledgerAccountRepository.findByBusinessIdAndCode(TENANT, LedgerAccountCodes.OPERATING_EXPENSES)
                .orElseThrow()
                .getId();
        BigDecimal netCash = BigDecimal.ZERO;
        BigDecimal netExp = BigDecimal.ZERO;
        for (JournalLine jl : lines) {
            if (jl.getLedgerAccountId().equals(cashId)) {
                netCash = netCash.add(jl.getDebit()).subtract(jl.getCredit());
            }
            if (jl.getLedgerAccountId().equals(expId)) {
                netExp = netExp.add(jl.getDebit()).subtract(jl.getCredit());
            }
        }
        assertThat(netCash).isEqualByComparingTo(new BigDecimal("-10.00"));
        assertThat(netExp).isEqualByComparingTo(new BigDecimal("10.00"));

        mockMvc.perform(get("/api/v1/finance/expenses/%s".formatted(expenseId))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isOk());
    }

    @Test
    void recordExpense_idempotencyKey_returnsSameResponse_andDoesNotDuplicateRow() throws Exception {
        LocalDate day = LocalDate.now();
        String key = "idem-expense-1";

        MvcResult first = mockMvc.perform(post("/api/v1/finance/expenses")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "expenseDate":"%s",
                                  "name":"Bank transfer rent",
                                  "categoryType":"fixed",
                                  "amount":500.00,
                                  "paymentMethod":"bank",
                                  "includeInCashDrawer":false
                                }
                                """.formatted(day))
                        .header("Idempotency-Key", key)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult second = mockMvc.perform(post("/api/v1/finance/expenses")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "expenseDate":"%s",
                                  "name":"Bank transfer rent",
                                  "categoryType":"fixed",
                                  "amount":500.00,
                                  "paymentMethod":"bank",
                                  "includeInCashDrawer":false
                                }
                                """.formatted(day))
                        .header("Idempotency-Key", key)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode a = objectMapper.readTree(first.getResponse().getContentAsString());
        JsonNode b = objectMapper.readTree(second.getResponse().getContentAsString());
        assertThat(a.get("id").asText()).isEqualTo(b.get("id").asText());
        assertThat(expenseRepository.count()).isEqualTo(1);
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

