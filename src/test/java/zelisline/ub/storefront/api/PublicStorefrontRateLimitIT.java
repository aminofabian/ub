package zelisline.ub.storefront.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(
        properties = {
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "app.security.public-storefront-rate-limit-per-minute=2",
        }
)
class PublicStorefrontRateLimitIT {

    private static final String TENANT = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String SLUG = "rate-limit-shop";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private BranchRepository branchRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    @BeforeEach
    void seed() {
        branchRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("RL Shop");
        b.setSlug(SLUG);
        businessRepository.save(b);

        Branch br = new Branch();
        br.setBusinessId(TENANT);
        br.setName("Main");
        br.setActive(true);
        String branchId = branchRepository.save(br).getId();

        b.setSettings("{\"storefront\":{\"enabled\":true,\"catalogBranchId\":\"%s\"}}".formatted(branchId));
        businessRepository.save(b);
    }

    @Test
    void thirdPublicGet_returns429() throws Exception {
        String path = "/api/v1/public/businesses/" + SLUG + "/storefront";
        mockMvc.perform(get(path)).andExpect(status().isOk());
        mockMvc.perform(get(path)).andExpect(status().isOk());
        mockMvc.perform(get(path)).andExpect(status().isTooManyRequests());
    }
}
