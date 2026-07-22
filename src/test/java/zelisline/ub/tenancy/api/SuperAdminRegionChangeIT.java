package zelisline.ub.tenancy.api;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.domain.ItemType;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.identity.domain.SuperAdmin;
import zelisline.ub.identity.repository.SuperAdminRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.selfserve.countries=KE,UG"
})
class SuperAdminRegionChangeIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private ItemTypeRepository itemTypeRepository;

    @Autowired
    private SuperAdminRepository superAdminRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String saToken;
    private String businessId;

    @BeforeEach
    void setUp() throws Exception {
        itemRepository.deleteAll();
        itemTypeRepository.deleteAll();
        businessRepository.deleteAll();
        superAdminRepository.deleteAll();

        SuperAdmin admin = new SuperAdmin();
        admin.setEmail("region-ops@example.com");
        admin.setName("Region Ops");
        admin.setPasswordHash(passwordEncoder.encode("super-secret-pass"));
        admin.setActive(true);
        superAdminRepository.save(admin);

        Business business = new Business();
        business.setName("Risk Co");
        business.setSlug("risk-co");
        business.setCountryCode("KE");
        business.setCurrency("KES");
        business.setTimezone("Africa/Nairobi");
        business.setSettings("{}");
        businessRepository.save(business);
        businessId = business.getId();

        ItemType type = new ItemType();
        type.setBusinessId(businessId);
        type.setTypeKey("goods");
        type.setLabel("Goods");
        type.setDefault(true);
        itemTypeRepository.save(type);

        Item item = new Item();
        item.setBusinessId(businessId);
        item.setName("Sugar");
        item.setSku("SUG-1");
        item.setItemTypeId(type.getId());
        itemRepository.save(item);

        String json = mockMvc.perform(post("/api/v1/super-admin/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"region-ops@example.com","password":"super-secret-pass"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        saToken = JsonPath.read(json, "$.accessToken");
    }

    @Test
    void saCountryChange_requiresAcknowledgeWhenItemsExist() throws Exception {
        mockMvc.perform(patch("/api/v1/super-admin/businesses/{id}", businessId)
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"countryCode":"UG"}
                                """))
                .andExpect(status().isConflict());

        mockMvc.perform(patch("/api/v1/super-admin/businesses/{id}", businessId)
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"countryCode":"UG","acknowledgeRegionRisk":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countryCode").value("UG"))
                .andExpect(jsonPath("$.currency").value("UGX"));
    }
}
