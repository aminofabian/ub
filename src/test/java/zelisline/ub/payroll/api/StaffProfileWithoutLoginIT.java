package zelisline.ub.payroll.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import zelisline.ub.identity.domain.Permission;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.RolePermission;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.PermissionRepository;
import zelisline.ub.identity.repository.RolePermissionRepository;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.payroll.application.StaffProfileService;
import zelisline.ub.payroll.domain.StaffProfile;
import zelisline.ub.payroll.repository.StaffProfileRepository;
import zelisline.ub.platform.security.TestAuthenticationFilter;
import zelisline.ub.serviceops.repository.ServiceCategoryRepository;
import zelisline.ub.serviceops.repository.ServiceOfferingRepository;
import zelisline.ub.serviceops.repository.ServiceStaffLinkRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/** Staff who never sign in (ADR 0014): created, listed, edited and archived by profile id. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class StaffProfileWithoutLoginIT {

    private static final String TENANT_A = "57000000-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final String TENANT_B = "57000000-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    private static final String ROLE_OWNER = "57222222-2222-4222-8222-222222222201";
    private static final String ROLE_CASHIER = "57222222-2222-4222-8222-222222222202";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private BusinessRepository businessRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PermissionRepository permissionRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private RolePermissionRepository rolePermissionRepository;
    @Autowired
    private StaffProfileRepository staffProfileRepository;
    @Autowired
    private StaffProfileService staffProfileService;
    @Autowired
    private ServiceStaffLinkRepository staffLinkRepository;
    @Autowired
    private ServiceOfferingRepository serviceRepository;
    @Autowired
    private ServiceCategoryRepository categoryRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User ownerA;
    private User cashierA;
    private User ownerB;

    @BeforeEach
    void seed() {
        staffLinkRepository.deleteAll();
        serviceRepository.deleteAll();
        categoryRepository.deleteAll();
        staffProfileRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        businessRepository.deleteAll();

        business(TENANT_A, "staff-a");
        business(TENANT_B, "staff-b");
        role(ROLE_OWNER, "owner");
        role(ROLE_CASHIER, "cashier");
        int n = 0;
        for (String key : List.of("staff.profile.read", "staff.hr.read", "staff.hr.update",
                "payroll.view", "services.catalog.manage", "sales.sell")) {
            String id = "57111111-1111-4111-8111-11111111110" + (n++);
            permissionRepository.save(perm(id, key));
            grant(ROLE_OWNER, id);
            if (key.equals("staff.profile.read") || key.equals("sales.sell")) {
                grant(ROLE_CASHIER, id);
            }
        }
        ownerA = user(TENANT_A, "owner-a@staff.test", "Owner A", ROLE_OWNER);
        cashierA = user(TENANT_A, "cashier-a@staff.test", "Cashier A", ROLE_CASHIER);
        ownerB = user(TENANT_B, "owner-b@staff.test", "Owner B", ROLE_OWNER);
    }

    @Test
    void addsAStaffMemberWithNoLogin() throws Exception {
        String body = mockMvc.perform(as(post("/api/v1/staff/profiles"), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"displayName\":\"  John \",\"title\":\"Barber\",\"phone\":\"0700000001\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.loginName").doesNotExist())
                .andExpect(jsonPath("$.roleName").doesNotExist())
                .andExpect(jsonPath("$.publicFields.displayName").value("John"))
                .andExpect(jsonPath("$.publicFields.title").value("Barber"))
                .andExpect(jsonPath("$.publicFields.bookable").value(true))
                .andExpect(jsonPath("$.publicFields.includeInPayroll").value(false))
                .andExpect(jsonPath("$.publicFields.employmentStatus").value("active"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        StaffProfile stored = staffProfileRepository.findByIdAndBusinessId(id, TENANT_A).orElseThrow();
        assertThat(stored.getUserId()).isNull();
        assertThat(stored.isBookable()).isTrue();

        // Listed after the account holders; a cashier sees them without the private fields.
        mockMvc.perform(as(get("/api/v1/staff/profiles"), TENANT_A, ownerA, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[2].id").value(id))
                .andExpect(jsonPath("$[2].privateFields.phone").value("0700000001"));
        mockMvc.perform(as(get("/api/v1/staff/profiles"), TENANT_A, cashierA, ROLE_CASHIER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[2].publicFields.displayName").value("John"))
                .andExpect(jsonPath("$[2].privateFields").doesNotExist());
    }

    @Test
    void manyStaffWithNoLoginCanExistInOneBusiness() throws Exception {
        create(TENANT_A, ownerA, "John");
        create(TENANT_A, ownerA, "Peter");
        create(TENANT_A, ownerA, "Brian");
        assertThat(staffProfileRepository.findByBusinessIdAndUserIdIsNull(TENANT_A)).hasSize(3);
    }

    @Test
    void nameIsRequiredAndUniqueAmongCurrentStaffWithNoLogin() throws Exception {
        for (String json : List.of("{}", "{\"displayName\":\"   \"}", "{\"displayName\":null}")) {
            mockMvc.perform(as(post("/api/v1/staff/profiles"), TENANT_A, ownerA, ROLE_OWNER).content(json))
                    .andExpect(status().isBadRequest());
        }
        String john = create(TENANT_A, ownerA, "John");
        mockMvc.perform(as(post("/api/v1/staff/profiles"), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"displayName\":\" JOHN \"}"))
                .andExpect(status().isConflict());
        // Another business may have its own John, and may rename someone to John.
        create(TENANT_B, ownerB, "John");
        String otherShop = create(TENANT_B, ownerB, "Otieno");
        mockMvc.perform(as(patch("/api/v1/staff/profiles/" + otherShop), TENANT_B, ownerB, ROLE_OWNER)
                        .content("{\"displayName\":\"Peter\"}"))
                .andExpect(status().isOk());

        String peter = create(TENANT_A, ownerA, "Peter");
        mockMvc.perform(as(patch("/api/v1/staff/profiles/" + peter), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"displayName\":\"john\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(as(patch("/api/v1/staff/profiles/" + peter), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"displayName\":\"  \"}"))
                .andExpect(status().isBadRequest());
        // Renaming to a variant of your own name is fine.
        mockMvc.perform(as(patch("/api/v1/staff/profiles/" + john), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"displayName\":\"JOHN\"}"))
                .andExpect(status().isOk());

        // Once John has left, the name is free again.
        mockMvc.perform(as(patch("/api/v1/staff/profiles/" + john), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"employmentStatus\":\"terminated\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicFields.employmentStatus").value("terminated"));
        String newJohn = create(TENANT_A, ownerA, "John");

        // Re-hiring the first John would put two Johns on the floor: refused until one is renamed.
        mockMvc.perform(as(patch("/api/v1/staff/profiles/" + john), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"employmentStatus\":\"active\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(as(patch("/api/v1/staff/profiles/" + john), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"employmentStatus\":\"active\",\"displayName\":\"John K\"}"))
                .andExpect(status().isOk());
        assertThat(staffProfileRepository.findById(newJohn).orElseThrow().getLoginlessNameKey()).isEqualTo("john");
        assertThat(staffProfileRepository.findById(john).orElseThrow().getLoginlessNameKey()).isEqualTo("john k");
    }

    @Test
    void theDatabaseItselfRefusesASecondCurrentStaffMemberWithTheSameName() {
        // What protects against a double tap: the unique key, with the service check bypassed.
        StaffProfile first = loginless("John");
        staffProfileRepository.saveAndFlush(first);
        StaffProfile second = loginless("John");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> staffProfileRepository.saveAndFlush(second))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void lookupsByUserNeverMatchStaffWithNoLogin() throws Exception {
        create(TENANT_A, ownerA, "John");
        assertThat(staffProfileService.findByUser(TENANT_A, null)).isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> staffProfileService.ensureProfile(TENANT_A, null))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> staffProfileService.requireProfileForUser(TENANT_A, " "))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void staffWithALoginAreUnaffectedByTheNameRule() throws Exception {
        create(TENANT_A, ownerA, "Cashier A");
        String cashierProfile = staffProfileService.ensureProfile(TENANT_A, cashierA.getId()).getId();
        assertThat(staffProfileRepository.findById(cashierProfile).orElseThrow().getLoginlessNameKey()).isNull();
        // Same display name as the login-less profile, payroll flag on: both fine for an account holder.
        mockMvc.perform(as(patch("/api/v1/staff/profiles/" + cashierProfile), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"displayName\":\"Cashier A\",\"includeInPayroll\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicFields.includeInPayroll").value(true));
        // The by-user route still works without the new field.
        mockMvc.perform(as(patch("/api/v1/staff/" + cashierA.getId() + "/profile"), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"title\":\"Till\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicFields.title").value("Till"))
                .andExpect(jsonPath("$.publicFields.bookable").value(false));
        assertThat(staffProfileRepository.findById(cashierProfile).orElseThrow().getLoginlessNameKey()).isNull();
    }

    @Test
    void editsByProfileIdIncludingBookable() throws Exception {
        String id = create(TENANT_A, ownerA, "John");
        mockMvc.perform(as(patch("/api/v1/staff/profiles/" + id), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"bookable\":false,\"title\":\"Senior barber\",\"notes\":\"Tuesdays off\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicFields.bookable").value(false))
                .andExpect(jsonPath("$.publicFields.title").value("Senior barber"))
                .andExpect(jsonPath("$.privateFields.notes").value("Tuesdays off"));
        mockMvc.perform(as(patch("/api/v1/staff/profiles/" + id), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"includeInPayroll\":true}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(as(patch("/api/v1/staff/profiles/" + id), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"employmentStatus\":\"sacked\"}"))
                .andExpect(status().isBadRequest());

        // A profile that does have a login can be edited by id too, and keeps its user.
        String cashierProfile = staffProfileService.ensureProfile(TENANT_A, cashierA.getId()).getId();
        mockMvc.perform(as(patch("/api/v1/staff/profiles/" + cashierProfile), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"bookable\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(cashierA.getId()))
                .andExpect(jsonPath("$.loginName").value("Cashier A"))
                .andExpect(jsonPath("$.publicFields.bookable").value(true));
        // The existing by-user route still works and now reports bookable.
        mockMvc.perform(as(get("/api/v1/staff/" + cashierA.getId() + "/profile"), TENANT_A, ownerA, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicFields.bookable").value(true));
    }

    @Test
    void anotherBusinessCannotSeeOrEditThem() throws Exception {
        String id = create(TENANT_A, ownerA, "John");
        mockMvc.perform(as(get("/api/v1/staff/profiles"), TENANT_B, ownerB, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userId").value(ownerB.getId()));
        mockMvc.perform(as(patch("/api/v1/staff/profiles/" + id), TENANT_B, ownerB, ROLE_OWNER)
                        .content("{\"displayName\":\"Stolen\",\"employmentStatus\":\"terminated\"}"))
                .andExpect(status().isNotFound());
        StaffProfile untouched = staffProfileRepository.findById(id).orElseThrow();
        assertThat(untouched.getDisplayName()).isEqualTo("John");
        assertThat(untouched.getEmploymentStatus()).isEqualTo("active");
    }

    @Test
    void onlyHrCanAddOrEdit() throws Exception {
        String id = create(TENANT_A, ownerA, "John");
        mockMvc.perform(as(post("/api/v1/staff/profiles"), TENANT_A, cashierA, ROLE_CASHIER)
                        .content("{\"displayName\":\"Peter\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(patch("/api/v1/staff/profiles/" + id), TENANT_A, cashierA, ROLE_CASHIER)
                        .content("{\"bookable\":false}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/staff/profiles").header("X-Tenant-Id", TENANT_A)
                        .contentType(APPLICATION_JSON).content("{\"displayName\":\"Peter\"}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));
        assertThat(staffProfileRepository.findByBusinessIdAndUserIdIsNull(TENANT_A)).hasSize(1);
    }

    @Test
    void payrollScreensStillLoadWithStaffWhoHaveNoLogin() throws Exception {
        String id = create(TENANT_A, ownerA, "John");
        staffProfileService.ensureProfile(TENANT_A, cashierA.getId());
        LocalDate today = LocalDate.now();
        String period = "?year=" + today.getYear() + "&month=" + today.getMonthValue();

        String runs = mockMvc.perform(as(get("/api/v1/payroll/runs" + period), TENANT_A, ownerA, ROLE_OWNER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // Payroll runs are per login; a staff member with none is never on one.
        assertThat(runs).doesNotContain(id);
        mockMvc.perform(as(get("/api/v1/payroll/summary" + period), TENANT_A, ownerA, ROLE_OWNER))
                .andExpect(status().isOk());
        mockMvc.perform(as(get("/api/v1/payroll/payslips" + period), TENANT_A, ownerA, ROLE_OWNER))
                .andExpect(status().isOk());
        mockMvc.perform(as(get("/api/v1/payroll/advances"), TENANT_A, ownerA, ROLE_OWNER))
                .andExpect(status().isOk());
        mockMvc.perform(as(get("/api/v1/payroll/calendar?year=" + today.getYear()), TENANT_A, ownerA, ROLE_OWNER))
                .andExpect(status().isOk());
        // Branch-filtered views resolve a profile's branch through its user; none must not break them.
        mockMvc.perform(as(get("/api/v1/payroll/summary" + period + "&branchId=no-such-branch"),
                        TENANT_A, ownerA, ROLE_OWNER))
                .andExpect(status().isOk());
        mockMvc.perform(as(get("/api/v1/payroll/calendar?year=" + today.getYear() + "&branchId=no-such-branch"),
                        TENANT_A, ownerA, ROLE_OWNER))
                .andExpect(status().isOk());
    }

    @Test
    void aStaffMemberWithNoLoginCanBeLinkedToAService() throws Exception {
        String john = create(TENANT_A, ownerA, "John");
        String service = JsonPath.read(mockMvc.perform(as(post("/api/v1/services"), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"name\":\"Haircut\",\"durationMinutes\":30,\"price\":300}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(as(put("/api/v1/services/" + service + "/staff"), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"staffProfileIds\":[\"" + john + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staffProfileIds[0]").value(john));
    }

    // ------------------------------------------------------------------ helpers

    private static StaffProfile loginless(String name) {
        StaffProfile profile = new StaffProfile();
        profile.setBusinessId(TENANT_A);
        profile.setDisplayName(name);
        profile.setLoginlessNameKey(name.toLowerCase());
        profile.setEmploymentStatus("active");
        profile.setJoinPayMode("half");
        return profile;
    }

    private String create(String tenant, User user, String name) throws Exception {
        String body = mockMvc.perform(as(post("/api/v1/staff/profiles"), tenant, user, ROLE_OWNER)
                        .content("{\"displayName\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private static MockHttpServletRequestBuilder as(
            MockHttpServletRequestBuilder builder, String tenant, User user, String roleId) {
        return builder
                .header("X-Tenant-Id", tenant)
                .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                .header(TestAuthenticationFilter.HEADER_ROLE_ID, roleId)
                .contentType(APPLICATION_JSON);
    }

    private void business(String id, String slug) {
        Business business = new Business();
        business.setId(id);
        business.setName(slug);
        business.setSlug(slug);
        business.setSettings("{}");
        businessRepository.save(business);
    }

    private void role(String id, String key) {
        Role role = new Role();
        role.setId(id);
        role.setBusinessId(null);
        role.setRoleKey(key);
        role.setName(key);
        role.setSystem(true);
        roleRepository.save(role);
    }

    private User user(String tenant, String email, String name, String roleId) {
        User user = new User();
        user.setBusinessId(tenant);
        user.setEmail(email);
        user.setName(name);
        user.setRoleId(roleId);
        user.setStatus(UserStatus.ACTIVE);
        user.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        return userRepository.save(user);
    }

    private static Permission perm(String id, String key) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(key);
        return p;
    }

    private void grant(String roleId, String permissionId) {
        RolePermission rp = new RolePermission();
        rp.setId(new RolePermission.Id(roleId, permissionId));
        rolePermissionRepository.save(rp);
    }
}
