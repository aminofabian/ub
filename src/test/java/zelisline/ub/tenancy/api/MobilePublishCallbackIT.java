package zelisline.ub.tenancy.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.mobile.publish.callback-secret=test-publish-callback-secret",
})
class MobilePublishCallbackIT {

    private static final String SLUG = "publish-callback-it";
    private static final String SECRET = "test-publish-callback-secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BusinessRepository businessRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    @BeforeEach
    void seed() {
        businessRepository.deleteAll();

        Business business = new Business();
        business.setId("cccccccc-cccc-cccc-cccc-cccccccccccc");
        business.setName("Callback IT");
        business.setSlug(SLUG);
        business.setSettings("""
                {
                  "mobile": {
                    "provisionedAt": "2026-01-01T00:00:00Z",
                    "publish": {
                      "status": "requested",
                      "requestedAt": "2026-01-02T12:00:00Z",
                      "app": "shopper",
                      "platform": "all"
                    }
                  }
                }
                """);
        businessRepository.save(business);
    }

    @Test
    void rejectsMissingSecret() throws Exception {
        mockMvc.perform(post("/webhooks/mobile-publish/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug":"%s","status":"building"}
                                """.formatted(SLUG)))
                .andExpect(status().isForbidden());
    }

    @Test
    void recordsBuildingThenSubmitted() throws Exception {
        mockMvc.perform(post("/webhooks/mobile-publish/status")
                        .header("X-Mobile-Publish-Secret", SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "slug": "%s",
                                  "status": "building",
                                  "workflowUrl": "https://github.com/org/repo/actions/runs/1"
                                }
                                """.formatted(SLUG)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/webhooks/mobile-publish/status")
                        .header("X-Mobile-Publish-Secret", SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug":"%s","status":"submitted"}
                                """.formatted(SLUG)))
                .andExpect(status().isNoContent());

        Business updated = businessRepository.findBySlugAndDeletedAtIsNull(SLUG).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(updated.getSettings())
                .contains("\"status\":\"submitted\"")
                .contains("https://github.com/org/repo/actions/runs/1")
                .contains("completedAt");
    }
}
