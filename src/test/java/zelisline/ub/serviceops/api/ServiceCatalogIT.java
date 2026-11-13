package zelisline.ub.serviceops.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.web.server.ResponseStatusException;

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
import zelisline.ub.payroll.domain.EmploymentStatus;
import zelisline.ub.payroll.domain.StaffProfile;
import zelisline.ub.payroll.repository.StaffProfileRepository;
import zelisline.ub.platform.security.TestAuthenticationFilter;
import zelisline.ub.serviceops.application.ServiceCatalogService;
import zelisline.ub.serviceops.repository.ServiceCategoryRepository;
import zelisline.ub.serviceops.repository.ServiceOfferingRepository;
import zelisline.ub.serviceops.repository.ServiceStaffLinkRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class ServiceCatalogIT {

    private static final String TENANT_A = "5e000000-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final String TENANT_B = "5e000000-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    private static final String PERM_MANAGE = "5e111111-1111-4111-8111-111111111101";
    private static final String PERM_SELL = "5e111111-1111-4111-8111-111111111102";
    private static final String ROLE_OWNER = "5e222222-2222-4222-8222-222222222201";
    private static final String ROLE_CASHIER = "5e222222-2222-4222-8222-222222222202";
    private static final String ROLE_VIEWER = "5e222222-2222-4222-8222-222222222203";
    private static final String ROLE_MANAGE_ONLY = "5e222222-2222-4222-8222-222222222204";

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
    private ServiceCategoryRepository categoryRepository;
    @Autowired
    private ServiceOfferingRepository serviceRepository;
    @Autowired
    private ServiceStaffLinkRepository staffLinkRepository;
    @Autowired
    private ServiceCatalogService serviceCatalogService;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User ownerA;
    private User cashierA;
    private User viewerA;
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

        business(TENANT_A, "kinyozi-a");
        business(TENANT_B, "kinyozi-b");

        permissionRepository.save(perm(PERM_MANAGE, "services.catalog.manage"));
        permissionRepository.save(perm(PERM_SELL, "sales.sell"));
        role(ROLE_OWNER, "owner");
        role(ROLE_CASHIER, "cashier");
        role(ROLE_VIEWER, "viewer");
        grant(ROLE_OWNER, PERM_MANAGE);
        grant(ROLE_OWNER, PERM_SELL);
        grant(ROLE_CASHIER, PERM_SELL);
        role(ROLE_MANAGE_ONLY, "catalogue_editor");
        grant(ROLE_MANAGE_ONLY, PERM_MANAGE);

        ownerA = user(TENANT_A, "owner-a@test", ROLE_OWNER);
        cashierA = user(TENANT_A, "cashier-a@test", ROLE_CASHIER);
        viewerA = user(TENANT_A, "viewer-a@test", ROLE_VIEWER);
        ownerB = user(TENANT_B, "owner-b@test", ROLE_OWNER);
    }

    @Test
    void createReadAndListAService() throws Exception {
        String categoryId = createCategory(TENANT_A, ownerA, "Haircuts");
        String body = mockMvc.perform(as(post("/api/v1/services"), TENANT_A, ownerA, ROLE_OWNER)
                        .content("""
                                {"name":"  Haircut ","categoryId":"%s","durationMinutes":30,"price":300}
                                """.formatted(categoryId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Haircut"))
                .andExpect(jsonPath("$.durationMinutes").value(30))
                .andExpect(jsonPath("$.price").value(300.00))
                .andExpect(jsonPath("$.priceIsVariable").value(false))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.staffProfileIds").isEmpty())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        mockMvc.perform(as(get("/api/v1/services/" + id), TENANT_A, ownerA, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value(categoryId));

        createService(TENANT_A, ownerA, "Beard trim", 15, "150");
        mockMvc.perform(as(get("/api/v1/services"), TENANT_A, ownerA, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].name").value("Beard trim"));
        mockMvc.perform(as(get("/api/v1/services").param("categoryId", categoryId), TENANT_A, ownerA, ROLE_OWNER))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Haircut"));
        mockMvc.perform(as(get("/api/v1/services").param("q", "BEARD"), TENANT_A, ownerA, ROLE_OWNER))
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void searchTreatsWildcardsAsText() throws Exception {
        createService(TENANT_A, ownerA, "Haircut", 30, "300");
        mockMvc.perform(as(get("/api/v1/services").param("q", "%"), TENANT_A, ownerA, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
        mockMvc.perform(as(get("/api/v1/services").param("q", "_"), TENANT_A, ownerA, ROLE_OWNER))
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void pageSizeIsCapped() throws Exception {
        createService(TENANT_A, ownerA, "Haircut", 30, "300");
        mockMvc.perform(as(get("/api/v1/services").param("size", "100000"), TENANT_A, ownerA, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(200));
    }

    @Test
    void rejectsInvalidInput() throws Exception {
        for (String json : List.of(
                "{\"name\":\"\",\"durationMinutes\":30,\"price\":300}",
                "{\"name\":\"Cut\",\"durationMinutes\":0,\"price\":300}",
                "{\"name\":\"Cut\",\"durationMinutes\":1441,\"price\":300}",
                "{\"name\":\"Cut\",\"durationMinutes\":30,\"price\":-1}",
                "{\"name\":\"Cut\",\"durationMinutes\":30}",
                "{\"name\":\"Cut\",\"durationMinutes\":30,\"price\":300,\"commissionType\":\"PERCENT\"}",
                "{\"name\":\"Cut\",\"durationMinutes\":30,\"price\":300,\"commissionType\":\"PERCENT\",\"commissionValue\":101}",
                "{\"name\":\"Cut\",\"durationMinutes\":30,\"price\":300,\"commissionType\":\"BONUS\",\"commissionValue\":10}",
                "{\"name\":\"Cut\",\"durationMinutes\":30,\"price\":300,\"categoryId\":\"no-such-category\"}")) {
            mockMvc.perform(as(post("/api/v1/services"), TENANT_A, ownerA, ROLE_OWNER).content(json))
                    .andExpect(status().isBadRequest());
        }
        assertThat(serviceRepository.count()).isZero();
    }

    @Test
    void commissionOverrideIsStoredAndCanBeCleared() throws Exception {
        String body = mockMvc.perform(as(post("/api/v1/services"), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"name\":\"Colour\",\"durationMinutes\":90,\"price\":1500,"
                                + "\"commissionType\":\"percent\",\"commissionValue\":40}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.commissionType").value("PERCENT"))
                .andExpect(jsonPath("$.commissionValue").value(40.00))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        mockMvc.perform(as(patch("/api/v1/services/" + id), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"commissionType\":\"FIXED\",\"commissionValue\":200}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commissionType").value("FIXED"))
                .andExpect(jsonPath("$.commissionValue").value(200.00));
        mockMvc.perform(as(patch("/api/v1/services/" + id), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"clearCommission\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commissionType").doesNotExist())
                .andExpect(jsonPath("$.commissionValue").doesNotExist());
    }

    @Test
    void duplicateNameIsRefusedUntilTheOriginalIsDeleted() throws Exception {
        String id = createService(TENANT_A, ownerA, "Haircut", 30, "300");
        mockMvc.perform(as(post("/api/v1/services"), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"name\":\" HAIRCUT \",\"durationMinutes\":20,\"price\":250}"))
                .andExpect(status().isConflict());

        String other = createService(TENANT_A, ownerA, "Kids cut", 30, "250");
        mockMvc.perform(as(patch("/api/v1/services/" + other), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"name\":\"haircut\"}"))
                .andExpect(status().isConflict());

        // Another business may use the same name.
        createService(TENANT_B, ownerB, "Haircut", 30, "300");

        mockMvc.perform(as(delete("/api/v1/services/" + id), TENANT_A, ownerA, ROLE_OWNER))
                .andExpect(status().isNoContent());
        mockMvc.perform(as(get("/api/v1/services/" + id), TENANT_A, ownerA, ROLE_OWNER))
                .andExpect(status().isNotFound());
        createService(TENANT_A, ownerA, "Haircut", 30, "350");
    }

    @Test
    void staleVersionIsRefused() throws Exception {
        String id = createService(TENANT_A, ownerA, "Haircut", 30, "300");
        String first = mockMvc.perform(as(patch("/api/v1/services/" + id), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"price\":350,\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(350.00))
                .andReturn().getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(first, "$.version")).longValue()).isEqualTo(1L);

        mockMvc.perform(as(patch("/api/v1/services/" + id), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"price\":400,\"version\":0}"))
                .andExpect(status().isConflict());
        mockMvc.perform(as(get("/api/v1/services/" + id), TENANT_A, ownerA, ROLE_OWNER))
                .andExpect(jsonPath("$.price").value(350.00));
    }

    @Test
    void eligibleStaffMustBelongToTheBusiness() throws Exception {
        String staffA = staffProfileService.ensureProfile(TENANT_A, cashierA.getId()).getId();
        String staffB = staffProfileService.ensureProfile(TENANT_B, ownerB.getId()).getId();
        String id = createService(TENANT_A, ownerA, "Haircut", 30, "300");

        mockMvc.perform(as(put("/api/v1/services/" + id + "/staff"), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"staffProfileIds\":[\"" + staffB + "\"]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(as(post("/api/v1/services"), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"name\":\"Shave\",\"durationMinutes\":15,\"price\":100,"
                                + "\"staffProfileIds\":[\"" + staffB + "\"]}"))
                .andExpect(status().isBadRequest());
        assertThat(staffLinkRepository.count()).isZero();

        mockMvc.perform(as(put("/api/v1/services/" + id + "/staff"), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"staffProfileIds\":[\"" + staffA + "\",\"" + staffA + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staffProfileIds.length()").value(1))
                .andExpect(jsonPath("$.staffProfileIds[0]").value(staffA));
        mockMvc.perform(as(get("/api/v1/services"), TENANT_A, ownerA, ROLE_OWNER))
                .andExpect(jsonPath("$.content[0].staffProfileIds[0]").value(staffA));

        mockMvc.perform(as(put("/api/v1/services/" + id + "/staff"), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"staffProfileIds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staffProfileIds").isEmpty());
        assertThat(staffLinkRepository.count()).isZero();
    }

    @Test
    void secondBusinessCannotSeeOrChangeAnotherBusinessesCatalogue() throws Exception {
        String categoryId = createCategory(TENANT_A, ownerA, "Haircuts");
        String id = createService(TENANT_A, ownerA, "Haircut", 30, "300");

        mockMvc.perform(as(get("/api/v1/services"), TENANT_B, ownerB, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
        mockMvc.perform(as(get("/api/v1/service-categories"), TENANT_B, ownerB, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(as(get("/api/v1/services/" + id), TENANT_B, ownerB, ROLE_OWNER))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(patch("/api/v1/services/" + id), TENANT_B, ownerB, ROLE_OWNER)
                        .content("{\"price\":1}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(put("/api/v1/services/" + id + "/staff"), TENANT_B, ownerB, ROLE_OWNER)
                        .content("{\"staffProfileIds\":[]}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(delete("/api/v1/services/" + id), TENANT_B, ownerB, ROLE_OWNER))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(patch("/api/v1/service-categories/" + categoryId), TENANT_B, ownerB, ROLE_OWNER)
                        .content("{\"name\":\"Stolen\"}"))
                .andExpect(status().isNotFound());
        // Another business's category cannot be attached to our service.
        mockMvc.perform(as(post("/api/v1/services"), TENANT_B, ownerB, ROLE_OWNER)
                        .content("{\"name\":\"Cut\",\"durationMinutes\":30,\"price\":300,\"categoryId\":\""
                                + categoryId + "\"}"))
                .andExpect(status().isBadRequest());

        String idB = createService(TENANT_B, ownerB, "Shave", 15, "100");
        mockMvc.perform(as(patch("/api/v1/services/" + idB), TENANT_B, ownerB, ROLE_OWNER)
                        .content("{\"categoryId\":\"" + categoryId + "\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(as(get("/api/v1/services/" + id), TENANT_A, ownerA, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(300.00));
        assertThatThrownBy(() -> serviceCatalogService.requireSellable(TENANT_B, id))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void sellersCanReadButOnlyManagersCanChange() throws Exception {
        String id = createService(TENANT_A, ownerA, "Haircut", 30, "300");

        mockMvc.perform(as(get("/api/v1/services"), TENANT_A, cashierA, ROLE_CASHIER))
                .andExpect(status().isOk());
        mockMvc.perform(as(get("/api/v1/service-categories"), TENANT_A, cashierA, ROLE_CASHIER))
                .andExpect(status().isOk());
        mockMvc.perform(as(post("/api/v1/services"), TENANT_A, cashierA, ROLE_CASHIER)
                        .content("{\"name\":\"Shave\",\"durationMinutes\":15,\"price\":100}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(patch("/api/v1/services/" + id), TENANT_A, cashierA, ROLE_CASHIER)
                        .content("{\"price\":1}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(delete("/api/v1/services/" + id), TENANT_A, cashierA, ROLE_CASHIER))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(post("/api/v1/service-categories"), TENANT_A, cashierA, ROLE_CASHIER)
                        .content("{\"name\":\"Nails\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(as(put("/api/v1/services/" + id + "/staff"), TENANT_A, cashierA, ROLE_CASHIER)
                        .content("{\"staffProfileIds\":[]}"))
                .andExpect(status().isForbidden());
        String categoryId = createCategory(TENANT_A, ownerA, "Haircuts");
        mockMvc.perform(as(patch("/api/v1/service-categories/" + categoryId), TENANT_A, cashierA, ROLE_CASHIER)
                        .content("{\"name\":\"Cuts\"}"))
                .andExpect(status().isForbidden());

        // A role that can manage the catalogue but cannot sell can still read it.
        User editor = user(TENANT_A, "editor-a@test", ROLE_MANAGE_ONLY);
        mockMvc.perform(as(get("/api/v1/services"), TENANT_A, editor, ROLE_MANAGE_ONLY))
                .andExpect(status().isOk());
        mockMvc.perform(as(get("/api/v1/services/" + id), TENANT_A, editor, ROLE_MANAGE_ONLY))
                .andExpect(status().isOk());
        mockMvc.perform(as(get("/api/v1/service-categories"), TENANT_A, editor, ROLE_MANAGE_ONLY))
                .andExpect(status().isOk());

        mockMvc.perform(as(get("/api/v1/services"), TENANT_A, viewerA, ROLE_VIEWER))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/services").header("X-Tenant-Id", TENANT_A))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void deletedOrSwitchedOffServiceCannotBeSold() throws Exception {
        String id = createService(TENANT_A, ownerA, "Haircut", 30, "300");
        assertThat(serviceCatalogService.requireSellable(TENANT_A, id).getName()).isEqualTo("Haircut");

        mockMvc.perform(as(patch("/api/v1/services/" + id), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk());
        assertThatThrownBy(() -> serviceCatalogService.requireSellable(TENANT_A, id))
                .isInstanceOf(ResponseStatusException.class);
        mockMvc.perform(as(get("/api/v1/services").param("active", "true"), TENANT_A, ownerA, ROLE_OWNER))
                .andExpect(jsonPath("$.content.length()").value(0));

        mockMvc.perform(as(delete("/api/v1/services/" + id), TENANT_A, ownerA, ROLE_OWNER))
                .andExpect(status().isNoContent());
        assertThatThrownBy(() -> serviceCatalogService.requireSellable(TENANT_A, id))
                .isInstanceOf(ResponseStatusException.class);
        mockMvc.perform(as(delete("/api/v1/services/" + id), TENANT_A, ownerA, ROLE_OWNER))
                .andExpect(status().isNotFound());
    }

    @Test
    void categoryNamesAreUniquePerBusiness() throws Exception {
        String id = createCategory(TENANT_A, ownerA, "Haircuts");
        mockMvc.perform(as(post("/api/v1/service-categories"), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"name\":\"haircuts\"}"))
                .andExpect(status().isConflict());
        createCategory(TENANT_B, ownerB, "Haircuts");
        mockMvc.perform(as(patch("/api/v1/service-categories/" + id), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"name\":\"Cuts\",\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Cuts"))
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void commissionOnEditNeedsTypeAndValueTogether() throws Exception {
        String body = mockMvc.perform(as(post("/api/v1/services"), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"name\":\"Colour\",\"durationMinutes\":90,\"price\":1500,"
                                + "\"commissionType\":\"PERCENT\",\"commissionValue\":40}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        for (String json : List.of(
                "{\"commissionType\":\"FIXED\"}",
                "{\"commissionValue\":50}",
                "{\"commissionType\":\"PERCENT\",\"commissionValue\":100.01}",
                "{\"clearCommission\":true,\"commissionType\":\"FIXED\",\"commissionValue\":10}",
                "{\"name\":\"   \"}")) {
            mockMvc.perform(as(patch("/api/v1/services/" + id), TENANT_A, ownerA, ROLE_OWNER).content(json))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(as(get("/api/v1/services/" + id), TENANT_A, ownerA, ROLE_OWNER))
                .andExpect(jsonPath("$.name").value("Colour"))
                .andExpect(jsonPath("$.commissionType").value("PERCENT"))
                .andExpect(jsonPath("$.commissionValue").value(40.00));
    }

    @Test
    void aServiceCanBeRenamedToAVariantOfItsOwnName() throws Exception {
        String id = createService(TENANT_A, ownerA, "Haircut", 30, "300");
        mockMvc.perform(as(patch("/api/v1/services/" + id), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"name\":\"HAIRCUT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("HAIRCUT"));
        String categoryId = createCategory(TENANT_A, ownerA, "Cuts");
        mockMvc.perform(as(patch("/api/v1/service-categories/" + categoryId), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"name\":\"CUTS\"}"))
                .andExpect(status().isOk());
        String other = createCategory(TENANT_A, ownerA, "Nails");
        mockMvc.perform(as(patch("/api/v1/service-categories/" + other), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"name\":\"cuts\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void terminatedStaffCannotBeLinkedAndDeleteRemovesLinks() throws Exception {
        StaffProfile current = staffProfileService.ensureProfile(TENANT_A, cashierA.getId());
        StaffProfile gone = staffProfileService.ensureProfile(TENANT_A, viewerA.getId());
        gone.setEmploymentStatus(EmploymentStatus.TERMINATED);
        staffProfileService.save(gone);
        String id = createService(TENANT_A, ownerA, "Haircut", 30, "300");

        mockMvc.perform(as(put("/api/v1/services/" + id + "/staff"), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"staffProfileIds\":[\"" + gone.getId() + "\"]}"))
                .andExpect(status().isBadRequest());

        String linked = mockMvc.perform(as(put("/api/v1/services/" + id + "/staff"), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"staffProfileIds\":[\"" + current.getId() + "\"]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // Changing the staff list counts as an edit: an editor holding the old version is refused.
        assertThat(((Number) JsonPath.read(linked, "$.version")).longValue()).isEqualTo(1L);
        mockMvc.perform(as(patch("/api/v1/services/" + id), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"price\":400,\"version\":0}"))
                .andExpect(status().isConflict());
        assertThat(staffLinkRepository.count()).isEqualTo(1);

        mockMvc.perform(as(delete("/api/v1/services/" + id), TENANT_A, ownerA, ROLE_OWNER))
                .andExpect(status().isNoContent());
        assertThat(staffLinkRepository.count()).isZero();
    }

    @Test
    void activeFilterAndEscapeCharacterInSearch() throws Exception {
        String id = createService(TENANT_A, ownerA, "Wash! and go", 20, "200");
        createService(TENANT_A, ownerA, "Haircut", 30, "300");
        mockMvc.perform(as(get("/api/v1/services").param("q", "wash!"), TENANT_A, ownerA, ROLE_OWNER))
                .andExpect(jsonPath("$.content.length()").value(1));
        mockMvc.perform(as(get("/api/v1/services").param("q", "!"), TENANT_A, ownerA, ROLE_OWNER))
                .andExpect(jsonPath("$.content.length()").value(1));

        mockMvc.perform(as(patch("/api/v1/services/" + id), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk());
        mockMvc.perform(as(get("/api/v1/services").param("active", "false"), TENANT_A, ownerA, ROLE_OWNER))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id));
    }

    @Test
    void sellersDoNotSeeWhatAServicePaysItsPerformer() throws Exception {
        String body = mockMvc.perform(as(post("/api/v1/services"), TENANT_A, ownerA, ROLE_OWNER)
                        .content("{\"name\":\"Colour\",\"durationMinutes\":90,\"price\":1500,"
                                + "\"commissionType\":\"PERCENT\",\"commissionValue\":40}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        mockMvc.perform(as(get("/api/v1/services"), TENANT_A, ownerA, ROLE_OWNER))
                .andExpect(jsonPath("$.content[0].commissionValue").value(40.00));
        mockMvc.perform(as(get("/api/v1/services"), TENANT_A, cashierA, ROLE_CASHIER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Colour"))
                .andExpect(jsonPath("$.content[0].price").value(1500.00))
                .andExpect(jsonPath("$.content[0].commissionType").doesNotExist())
                .andExpect(jsonPath("$.content[0].commissionValue").doesNotExist());
        mockMvc.perform(as(get("/api/v1/services/" + id), TENANT_A, cashierA, ROLE_CASHIER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commissionValue").doesNotExist());
    }

    @Test
    void theTillCanListWhoServesCustomers() throws Exception {
        StaffProfile barber = staffProfileService.ensureProfile(TENANT_A, viewerA.getId());
        barber.setDisplayName("John");
        barber.setBookable(true);
        staffProfileService.save(barber);
        // Has a profile but does not serve customers.
        staffProfileService.ensureProfile(TENANT_A, cashierA.getId());
        // Left the shop.
        StaffProfile gone = staffProfileService.ensureProfile(TENANT_A, ownerA.getId());
        gone.setBookable(true);
        gone.setEmploymentStatus(EmploymentStatus.TERMINATED);
        staffProfileService.save(gone);
        // Another business's barber.
        StaffProfile other = staffProfileService.ensureProfile(TENANT_B, ownerB.getId());
        other.setBookable(true);
        staffProfileService.save(other);

        mockMvc.perform(as(get("/api/v1/services/performers"), TENANT_A, cashierA, ROLE_CASHIER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].staffProfileId").value(barber.getId()))
                .andExpect(jsonPath("$[0].displayName").value("John"))
                // Names only: nothing about pay.
                .andExpect(jsonPath("$[0].rate").doesNotExist())
                .andExpect(jsonPath("$[0].type").doesNotExist());
        mockMvc.perform(as(get("/api/v1/services/performers"), TENANT_A, viewerA, ROLE_VIEWER))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private String createCategory(String tenant, User user, String name) throws Exception {
        String body = mockMvc.perform(as(post("/api/v1/service-categories"), tenant, user, ROLE_OWNER)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private String createService(String tenant, User user, String name, int minutes, String price) throws Exception {
        String body = mockMvc.perform(as(post("/api/v1/services"), tenant, user, ROLE_OWNER)
                        .content("{\"name\":\"" + name + "\",\"durationMinutes\":" + minutes
                                + ",\"price\":" + price + "}"))
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

    private User user(String tenant, String email, String roleId) {
        User user = new User();
        user.setBusinessId(tenant);
        user.setEmail(email);
        user.setName(email);
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
