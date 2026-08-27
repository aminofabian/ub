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
import zelisline.ub.sales.application.DrawoutApprovalNotifier;
import zelisline.ub.sales.repository.CashDrawoutRepository;
import zelisline.ub.sales.repository.CashDrawerMovementRepository;
import zelisline.ub.sales.repository.ShiftExpenseRepository;
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
class DrawoutIT {

    private static final String TENANT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbd1";
    private static final String P_SO = "11111111-0000-0000-0000-000000000064";
    private static final String P_SC = "11111111-0000-0000-0000-000000000065";
    private static final String P_SR = "11111111-0000-0000-0000-000000000066";
    private static final String P_DA = "11111111-0000-0000-0000-000000000710";
    private static final String ROLE_POS = "22222222-0000-0000-0000-0000000000d1";
    private static final String ROLE_ADMIN = "22222222-0000-0000-0000-0000000000d2";

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
    private CashDrawoutRepository cashDrawoutRepository;
    @Autowired
    private ShiftExpenseRepository shiftExpenseRepository;
    @Autowired
    private CashDrawerMovementRepository cashDrawerMovementRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DrawoutApprovalNotifier drawoutApprovalNotifier;

    private User cashier;
    private User admin;
    private String branchId;

    @BeforeEach
    void seed() {
        cashDrawerMovementRepository.deleteAll();
        shiftExpenseRepository.deleteAll();
        cashDrawoutRepository.deleteAll();
        shiftRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        branchRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("Drawout Shop");
        b.setSlug("drawout-shop");
        businessRepository.save(b);

        Branch br = new Branch();
        br.setBusinessId(TENANT);
        br.setName("Till");
        branchRepository.save(br);
        branchId = br.getId();

        permissionRepository.save(perm(P_SO, "shifts.open", "o"));
        permissionRepository.save(perm(P_SC, "shifts.close", "c"));
        permissionRepository.save(perm(P_SR, "shifts.read", "r"));
        permissionRepository.save(perm(P_DA, "shifts.drawouts.approve", "a"));

        Role pos = new Role();
        pos.setId(ROLE_POS);
        pos.setBusinessId(null);
        pos.setRoleKey("pos_test");
        pos.setName("POS Test");
        pos.setSystem(true);
        roleRepository.save(pos);
        for (String pid : List.of(P_SO, P_SC, P_SR)) {
            RolePermission rp = new RolePermission();
            rp.setId(new RolePermission.Id(ROLE_POS, pid));
            rolePermissionRepository.save(rp);
        }

        Role adminRole = new Role();
        adminRole.setId(ROLE_ADMIN);
        adminRole.setBusinessId(null);
        adminRole.setRoleKey("admin");
        adminRole.setName("Admin Test");
        adminRole.setSystem(true);
        roleRepository.save(adminRole);
        for (String pid : List.of(P_SO, P_SC, P_SR, P_DA)) {
            RolePermission rp = new RolePermission();
            rp.setId(new RolePermission.Id(ROLE_ADMIN, pid));
            rolePermissionRepository.save(rp);
        }

        cashier = new User();
        cashier.setBusinessId(TENANT);
        cashier.setEmail("cashier-drawout@test");
        cashier.setName("Cashier");
        cashier.setRoleId(ROLE_POS);
        cashier.setBranchId(branchId);
        cashier.setStatus(UserStatus.ACTIVE);
        cashier.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(cashier);

        admin = new User();
        admin.setBusinessId(TENANT);
        admin.setEmail("admin-drawout@test");
        admin.setName("Admin");
        admin.setRoleId(ROLE_ADMIN);
        admin.setStatus(UserStatus.ACTIVE);
        admin.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(admin);
    }

    @Test
    void pendingDrawout_reducesExpectedCash_andApproveDoesNotDoubleCount() throws Exception {
        String shiftId = openShift("1000.00");

        JsonNode created = postDrawout(shiftId, "750.00", cashier);
        assertThat(created.get("status").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(expectedCash(shiftId)).isEqualByComparingTo("250.00");

        mockMvc.perform(post("/api/v1/drawouts/%s/approve".formatted(created.get("id").asText()))
                        .contentType(APPLICATION_JSON)
                        .content("{\"approvalMethod\":\"PIN\"}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, admin.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_ADMIN))
                .andExpect(status().isOk());

        assertThat(expectedCash(shiftId)).isEqualByComparingTo("250.00");
    }

    @Test
    void rejectPendingDrawout_restoresExpectedCash() throws Exception {
        String shiftId = openShift("1000.00");
        JsonNode created = postDrawout(shiftId, "750.00", cashier);
        assertThat(expectedCash(shiftId)).isEqualByComparingTo("250.00");

        mockMvc.perform(post("/api/v1/drawouts/%s/reject".formatted(created.get("id").asText()))
                        .contentType(APPLICATION_JSON)
                        .content("{\"rejectionReason\":\"Not authorised\"}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, admin.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_ADMIN))
                .andExpect(status().isOk());

        assertThat(expectedCash(shiftId)).isEqualByComparingTo("1000.00");
    }

    @Test
    void selfApprovedDrawout_reducesExpectedCashImmediately() throws Exception {
        String shiftId = openShift("1000.00");
        JsonNode created = postDrawout(shiftId, "200.00", cashier);
        assertThat(created.get("status").asText()).isEqualTo("APPROVED");
        assertThat(expectedCash(shiftId)).isEqualByComparingTo("800.00");
    }

    @Test
    void cashierCannotApprovePendingDrawout() throws Exception {
        String shiftId = openShift("1000.00");
        JsonNode created = postDrawout(shiftId, "750.00", cashier);

        mockMvc.perform(post("/api/v1/drawouts/%s/approve".formatted(created.get("id").asText()))
                        .contentType(APPLICATION_JSON)
                        .content("{\"approvalMethod\":\"PIN\"}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isForbidden());
    }

    private String openShift(String opening) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/shifts/open")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"branchId":"%s","openingCash":%s}
                                """.formatted(branchId, opening))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private JsonNode postDrawout(String shiftId, String amount, User actor) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/shifts/%s/drawouts".formatted(shiftId))
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"amount":%s,"category":"PETTY_CASH","description":"Water delivery for shop",
                                 "recipientName":"John"}
                                """.formatted(amount))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, actor.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, actor.getRoleId()))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private BigDecimal expectedCash(String shiftId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/shifts/%s".formatted(shiftId))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_POS))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode expected = json.get("expectedClosingCash");
        if (expected == null || expected.isNull()) {
            expected = json.get("expectedCash");
        }
        return expected.decimalValue();
    }

    private static Permission perm(String id, String key, String desc) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(desc);
        return p;
    }
}
