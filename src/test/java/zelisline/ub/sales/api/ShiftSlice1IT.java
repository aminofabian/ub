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
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.domain.Shift;
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
class ShiftSlice1IT {

    private static final String TENANT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1";
    private static final String P_SO = "11111111-0000-0000-0000-000000000064";
    private static final String P_SC = "11111111-0000-0000-0000-000000000065";
    private static final String P_SR = "11111111-0000-0000-0000-000000000066";
    private static final String ROLE_POS = "22222222-0000-0000-0000-0000000000aa";

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
    private JournalLineRepository journalLineRepository;
    @Autowired
    private JournalEntryRepository journalEntryRepository;
    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User cashier;
    private String branchId;

    @BeforeEach
    void seed() {
        journalLineRepository.deleteAll();
        journalEntryRepository.deleteAll();
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
        b.setName("Shift Shop");
        b.setSlug("shift-shop");
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
        cashier.setEmail("cashier-shift@test");
        cashier.setName("Cashier");
        cashier.setRoleId(ROLE_POS);
        cashier.setBranchId(branchId);
        cashier.setStatus(UserStatus.ACTIVE);
        cashier.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(cashier);
    }

    @Test
    void openCurrentClose_balancedBooksOnVariance() throws Exception {
        mockMvc.perform(post("/api/v1/shifts/open")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"branchId":"%s","openingCash":100.00,"notes":"morning"}
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

        JsonNode curJson = objectMapper.readTree(cur.getResponse().getContentAsString());
        String shiftId = curJson.get("id").asText();
        assertThat(curJson.get("expectedClosingCash").decimalValue()).isEqualByComparingTo(new BigDecimal("100.00"));

        mockMvc.perform(post("/api/v1/shifts/%s/close".formatted(shiftId))
                        .contentType(APPLICATION_JSON)
                        .content("{\"countedClosingCash\":95.50,\"notes\":\"short\"}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isOk());

        Shift closed = shiftRepository.findById(shiftId).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(SalesConstants.SHIFT_STATUS_CLOSED);
        assertThat(closed.getCloseJournalEntryId()).isNotNull();

        List<JournalLine> lines = journalLineRepository.findByJournalEntryId(closed.getCloseJournalEntryId());
        assertJournalBalanced(lines);
        String cashId = ledgerAccountRepository
                .findByBusinessIdAndCode(TENANT, LedgerAccountCodes.OPERATING_CASH)
                .orElseThrow()
                .getId();
        BigDecimal netCash = BigDecimal.ZERO;
        for (JournalLine jl : lines) {
            if (jl.getLedgerAccountId().equals(cashId)) {
                netCash = netCash.add(jl.getDebit()).subtract(jl.getCredit());
            }
        }
        assertThat(netCash).isEqualByComparingTo(new BigDecimal("-4.50"));
    }

    @Test
    void secondOpenWhileFirstActive_returnsConflict() throws Exception {
        mockMvc.perform(post("/api/v1/shifts/open")
                        .contentType(APPLICATION_JSON)
                        .content("{\"branchId\":\"%s\",\"openingCash\":10}".formatted(branchId))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/shifts/open")
                        .contentType(APPLICATION_JSON)
                        .content("{\"branchId\":\"%s\",\"openingCash\":20}".formatted(branchId))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isConflict());
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
