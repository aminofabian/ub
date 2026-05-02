package zelisline.ub.identity.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import zelisline.ub.identity.domain.SuperAdmin;
import zelisline.ub.identity.repository.SuperAdminRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class SuperAdminAuthIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SuperAdminRepository superAdminRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void seed() {
        superAdminRepository.deleteAll();
        SuperAdmin admin = new SuperAdmin();
        admin.setEmail("ops@example.com");
        admin.setName("Ops");
        admin.setPasswordHash(passwordEncoder.encode("super-secret-pass"));
        admin.setActive(true);
        superAdminRepository.save(admin);
    }

    @Test
    void loginThenAccessSuperAdminApi() throws Exception {
        String json = mockMvc.perform(post("/api/v1/super-admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"ops@example.com","password":"super-secret-pass"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String token = JsonPath.read(json, "$.accessToken");

        mockMvc.perform(get("/api/v1/super-admin/businesses")
                        .param("page", "0")
                        .param("size", "20")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
