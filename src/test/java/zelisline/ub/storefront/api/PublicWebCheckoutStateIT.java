package zelisline.ub.storefront.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;

import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;
import zelisline.ub.storefront.application.ShopperCheckoutStateService;
import zelisline.ub.storefront.repository.ShopperCheckoutProfileRepository;
import zelisline.ub.storefront.repository.WebCheckoutSessionRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class PublicWebCheckoutStateIT {

    private static final String TENANT = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    private static final String BUYER_ROLE_ID = "22222222-2222-2222-2222-222222222202";
    private static final String SLUG = "checkout-state-it";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private WebCheckoutSessionRepository webCheckoutSessionRepository;

    @Autowired
    private ShopperCheckoutProfileRepository shopperCheckoutProfileRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    @BeforeEach
    void seed() {
        userRepository.deleteAll();
        roleRepository.deleteAll();
        branchRepository.deleteAll();
        businessRepository.deleteAll();

        Business business = new Business();
        business.setId(TENANT);
        business.setName("Checkout State Shop");
        business.setSlug(SLUG);
        business.setCurrency("KES");
        business.setSettings("{}");
        businessRepository.save(business);

        Branch branch = new Branch();
        branch.setBusinessId(TENANT);
        branch.setName("Main");
        branch.setActive(true);
        String branchId = branchRepository.save(branch).getId();

        business.setSettings(
                "{\"storefront\":{\"enabled\":true,\"catalogBranchId\":\"%s\",\"label\":\"Hi\"}}"
                        .formatted(branchId));
        businessRepository.save(business);

        Role buyer = new Role();
        buyer.setId(BUYER_ROLE_ID);
        buyer.setBusinessId(null);
        buyer.setRoleKey("buyer");
        buyer.setName("Buyer");
        buyer.setSystem(true);
        roleRepository.save(buyer);

        User user = new User();
        user.setBusinessId(TENANT);
        user.setEmail("buyer@example.com");
        user.setName("Fabian Amino");
        user.setRoleId(BUYER_ROLE_ID);
        user.setStatus(UserStatus.ACTIVE);
        user.setPasswordHash(passwordEncoder.encode("password123"));
        userRepository.save(user);
    }

    @Test
    void checkoutState_advancesThroughContactAndDelivery() throws Exception {
        String cartId = createCart();
        String token = loginToken();

        mockMvc.perform(get("/api/v1/public/businesses/" + SLUG + "/carts/" + cartId + "/checkout-state")
                        .header("X-Tenant-Id", TENANT)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.currentStep").value(1))
                .andExpect(jsonPath("$.detailsSubStep").value("contact"));

        mockMvc.perform(patch("/api/v1/public/businesses/" + SLUG + "/carts/" + cartId + "/checkout-state/contact")
                        .header("X-Tenant-Id", TENANT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Fabian",
                                  "lastName": "Amino",
                                  "email": "buyer@example.com",
                                  "areaCode": "+254",
                                  "phone": "714282874"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed.contact").value(true))
                .andExpect(jsonPath("$.detailsSubStep").value("delivery"));

        mockMvc.perform(patch("/api/v1/public/businesses/" + SLUG + "/carts/" + cartId + "/checkout-state/delivery")
                        .header("X-Tenant-Id", TENANT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "county": "Nairobi",
                                  "subCounty": "Roysambu",
                                  "ward": "Githurai",
                                  "streetAddress": "35393",
                                  "deliveryNotes": "",
                                  "saveForNextTime": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed.delivery").value(true))
                .andExpect(jsonPath("$.currentStep").value(2));
    }

    void checkoutState_guestSessionAndSavedProfile() throws Exception {
        String cartId = createCart();

        MvcResult deliveryResult = mockMvc.perform(
                        patch("/api/v1/public/businesses/" + SLUG + "/carts/" + cartId + "/checkout-state/delivery")
                                .header("X-Tenant-Id", TENANT)
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {
                                          "county": "Nairobi",
                                          "subCounty": "Roysambu",
                                          "ward": "Githurai",
                                          "streetAddress": "35393",
                                          "deliveryNotes": "",
                                          "saveForNextTime": false
                                        }
                                        """))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(deliveryResult.getResponse().getContentAsString()).contains("contact");

        mockMvc.perform(patch("/api/v1/public/businesses/" + SLUG + "/carts/" + cartId + "/checkout-state/contact")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Guest",
                                  "lastName": "Shopper",
                                  "email": "guest@example.com",
                                  "areaCode": "+254",
                                  "phone": "700111222"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.detailsSubStep").value("delivery"));

        MvcResult saved = mockMvc.perform(
                        patch("/api/v1/public/businesses/" + SLUG + "/carts/" + cartId + "/checkout-state/delivery")
                                .header("X-Tenant-Id", TENANT)
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {
                                          "county": "Nairobi",
                                          "subCounty": "Roysambu",
                                          "ward": "Githurai",
                                          "streetAddress": "35393",
                                          "deliveryNotes": "Gate B",
                                          "saveForNextTime": true
                                        }
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStep").value(2))
                .andExpect(jsonPath("$.guestKey").isString())
                .andReturn();

        String guestKey = JsonPath.read(saved.getResponse().getContentAsString(), "$.guestKey");
        assertThat(guestKey).isNotBlank();
        assertThat(shopperCheckoutProfileRepository.findByBusinessIdAndGuestKey(TENANT, guestKey)).isPresent();

        String newCartId = createCart();
        mockMvc.perform(get("/api/v1/public/businesses/" + SLUG + "/carts/" + newCartId + "/checkout-state")
                        .header("X-Tenant-Id", TENANT)
                        .header(ShopperCheckoutStateService.GUEST_KEY_HEADER, guestKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStep").value(2))
                .andExpect(jsonPath("$.profile.streetAddress").value("35393"));
    }

    @Test
    void checkoutState_guestIsUnauthenticated() throws Exception {
        String cartId = createCart();

        mockMvc.perform(get("/api/v1/public/businesses/" + SLUG + "/carts/" + cartId + "/checkout-state")
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.currentStep").value(1));
    }

    private String createCart() throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/public/businesses/" + SLUG + "/carts")
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(r.getResponse().getContentAsString(), "$.id");
    }

    private String loginToken() throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"buyer@example.com","password":"password123"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        String token = JsonPath.read(r.getResponse().getContentAsString(), "$.accessToken");
        assertThat(token).isNotBlank();
        return token;
    }
}
