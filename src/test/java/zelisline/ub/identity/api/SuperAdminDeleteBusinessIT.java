package zelisline.ub.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.SuperAdmin;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.SuperAdminRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class SuperAdminDeleteBusinessIT {

    private static final String VIEWER_ROLE_ID = "22222222-0000-0000-0000-000000000005";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SuperAdminRepository superAdminRepository;

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String businessId;
    private String saToken;

    @BeforeEach
    void seed() throws Exception {
        superAdminRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();
        businessRepository.deleteAll();

        SuperAdmin admin = new SuperAdmin();
        admin.setEmail("ops@example.com");
        admin.setName("Ops");
        admin.setPasswordHash(passwordEncoder.encode("super-secret-pass"));
        admin.setActive(true);
        superAdminRepository.save(admin);

        Business business = new Business();
        business.setName("To Delete Inc");
        business.setSlug("to-delete-inc");
        businessRepository.save(business);
        businessId = business.getId();

        Role viewer = new Role();
        viewer.setId(VIEWER_ROLE_ID);
        viewer.setBusinessId(null);
        viewer.setRoleKey("viewer");
        viewer.setName("Viewer");
        viewer.setSystem(true);
        roleRepository.save(viewer);

        User u1 = new User();
        u1.setBusinessId(businessId);
        u1.setEmail("a@delete.test");
        u1.setName("A");
        u1.setRoleId(VIEWER_ROLE_ID);
        u1.setStatus(UserStatus.ACTIVE);
        u1.setPasswordHash(passwordEncoder.encode("p"));
        userRepository.save(u1);

        User u2 = new User();
        u2.setBusinessId(businessId);
        u2.setEmail("b@delete.test");
        u2.setName("B");
        u2.setRoleId(VIEWER_ROLE_ID);
        u2.setStatus(UserStatus.ACTIVE);
        u2.setPasswordHash(passwordEncoder.encode("p"));
        userRepository.save(u2);

        String json = mockMvc.perform(post("/api/v1/super-admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"ops@example.com","password":"super-secret-pass"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        saToken = JsonPath.read(json, "$.accessToken");
    }

    @Test
    void superAdminDeletesTenantSoftDeletesBusinessAndUsers() throws Exception {
        mockMvc.perform(delete("/api/v1/super-admin/businesses/{id}", businessId)
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isNoContent());

        assertThat(businessRepository.findById(businessId)).isPresent();
        assertThat(businessRepository.findById(businessId).orElseThrow().getDeletedAt()).isNotNull();

        assertThat(userRepository.findByBusinessIdAndEmailAndDeletedAtIsNull(businessId, "a@delete.test"))
                .isEmpty();

        assertThat(
                userRepository.findAll().stream()
                        .filter(u -> businessId.equals(u.getBusinessId()))
                        .toList()
        )
                .hasSize(2)
                .allMatch(u -> u.getDeletedAt() != null);

        mockMvc.perform(delete("/api/v1/super-admin/businesses/{id}", businessId)
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isNotFound());
    }
}
