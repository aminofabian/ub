package zelisline.ub.storefront.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.domain.DomainMapping;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.tenancy.slug-domain-suffix=kiosk.ke",
        "app.mobile.deep-link-scheme=kiosk",
})
class PublicMobileConfigIT {

    private static final String TENANT_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String SLUG = "palmart-mobile-it";

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
        business.setId(TENANT_ID);
        business.setName("Palmart");
        business.setSlug(SLUG);
        business.setSettings("""
                {
                  "storefront": { "enabled": true, "catalogBranchId": "branch-1" },
                  "branding": {
                    "displayName": "Palmart Supermarket",
                    "primaryColor": "#28a745"
                  },
                  "mobile": {
                    "provisionedAt": "2026-01-01T00:00:00Z",
                    "scheme": "palmart-mobile-it",
                    "apps": {
                      "shopper": {
                        "name": "Palmart Supermarket",
                        "bundleId": "com.palmartmobileit.shopper",
                        "whiteLabel": true
                      },
                      "cashier": {
                        "name": "Palmart Supermarket Cashier",
                        "bundleId": "com.palmartmobileit.cashier",
                        "whiteLabel": true
                      }
                    },
                    "storeLinks": {}
                  }
                }
                """);
        businessRepository.save(business);

        DomainMapping mapping = new DomainMapping();
        mapping.setBusinessId(TENANT_ID);
        mapping.setDomain("palmart.kiosk.ke");
        mapping.setPrimary(true);
        mapping.setActive(true);
        org.mockito.Mockito.when(domainMappingRepository.findByBusinessIdAndDeletedAtIsNull(TENANT_ID))
                .thenReturn(java.util.List.of(mapping));
    }

    @Test
    void returnsMobileConfigForSlug() throws Exception {
        mockMvc.perform(get("/api/v1/public/businesses/" + SLUG + "/mobile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(TENANT_ID))
                .andExpect(jsonPath("$.slug").value(SLUG))
                .andExpect(jsonPath("$.displayName").value("Palmart Supermarket"))
                .andExpect(jsonPath("$.tenantHost").value("palmart.kiosk.ke"))
                .andExpect(jsonPath("$.tenantStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.storefrontEnabled").value(true))
                .andExpect(jsonPath("$.deepLinks.shopper").value("kiosk://shop/" + SLUG))
                .andExpect(jsonPath("$.deepLinks.cashier").value("kiosk://login/" + SLUG))
                .andExpect(jsonPath("$.deepLinks.universalShop").value("https://palmart.kiosk.ke/shop"))
                .andExpect(jsonPath("$.apps[0].role").value("shopper"))
                .andExpect(jsonPath("$.apps[0].embeddedTenantSlug").value(SLUG))
                .andExpect(jsonPath("$.apps[0].whiteLabel").value(true))
                .andExpect(jsonPath("$.apps[0].bundleId").value("com.palmartmobileit.shopper"))
                .andExpect(jsonPath("$.apps[1].role").value("cashier"))
                .andExpect(jsonPath("$.apps[1].name").value("Palmart Supermarket Cashier"));
    }

    @Test
    void unknownSlugReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/public/businesses/no-such-business/mobile"))
                .andExpect(status().isNotFound());
    }
}
