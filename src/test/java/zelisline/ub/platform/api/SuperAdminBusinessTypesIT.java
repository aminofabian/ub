package zelisline.ub.platform.api;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

/**
 * HTTP-level coverage for the business-type catalogue (ADR 0029): the public read is open, the
 * Super Admin edit requires {@code PERM_sa.console.full}, and an unauthenticated edit is refused.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class SuperAdminBusinessTypesIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SuperAdminRepository superAdminRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String saToken;

    @BeforeEach
    void seed() throws Exception {
        superAdminRepository.deleteAll();

        SuperAdmin admin = new SuperAdmin();
        admin.setEmail("ops-types@example.com");
        admin.setName("Ops Types");
        admin.setPasswordHash(passwordEncoder.encode("super-secret-pass"));
        admin.setActive(true);
        superAdminRepository.save(admin);

        String json = mockMvc.perform(post("/api/v1/super-admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"ops-types@example.com","password":"super-secret-pass"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        saToken = JsonPath.read(json, "$.accessToken");
    }

    @Test
    void publicReadReturnsEnabledCatalogWithoutAuth() throws Exception {
        mockMvc.perform(get("/api/v1/public/business-types").accept(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'bar')].label").value(org.hamcrest.Matchers.hasItem("Bar")))
                .andExpect(jsonPath("$[?(@.code == 'lodging')].section")
                        .value(org.hamcrest.Matchers.hasItem("lodging")));
    }

    @Test
    void superAdminCanRelabelAndHideAType() throws Exception {
        mockMvc.perform(put("/api/v1/super-admin/platform/business-types/bar")
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"label":"Pub / bar","hint":"Drinks and bites","section":"food",
                                 "displayOrder":170,"enabled":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Pub / bar"))
                .andExpect(jsonPath("$.enabled").value(false));

        // A hidden type disappears from the public read...
        mockMvc.perform(get("/api/v1/public/business-types").accept(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'bar')]").isEmpty());

        // ...but the Super Admin editor still shows it so it can be re-enabled.
        mockMvc.perform(get("/api/v1/super-admin/platform/business-types")
                        .header("Authorization", "Bearer " + saToken).accept(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'bar')].enabled")
                        .value(org.hamcrest.Matchers.hasItem(false)));
    }

    @Test
    void updateRejectsUnknownCode() throws Exception {
        mockMvc.perform(put("/api/v1/super-admin/platform/business-types/casino")
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"label":"Casino","section":"shop","displayOrder":1,"enabled":true}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void unauthenticatedEditIsRefused() throws Exception {
        mockMvc.perform(put("/api/v1/super-admin/platform/business-types/bar")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"label":"Hacked","section":"food","displayOrder":170,"enabled":true}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateRejectsOffListSectionAndNegativeOrder() throws Exception {
        mockMvc.perform(put("/api/v1/super-admin/platform/business-types/bar")
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"label":"Bar","section":"casino","displayOrder":-5,"enabled":true}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void imageUploadRejectsNonImageContent() throws Exception {
        // A text file that is not any image kind is rejected by the server-side magic-byte check,
        // before any media storage is touched.
        var notAnImage = new org.springframework.mock.web.MockMultipartFile(
                "file", "notes.txt", "text/plain", "just some text, definitely not an image".getBytes());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/v1/super-admin/platform/business-types/bar/image")
                        .file(notAnImage)
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isBadRequest());
    }
}
