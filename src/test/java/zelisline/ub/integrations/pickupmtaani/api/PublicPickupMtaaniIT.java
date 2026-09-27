package zelisline.ub.integrations.pickupmtaani.api;

import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.integrations.pickupmtaani.infrastructure.PickupMtaaniClient;
import zelisline.ub.integrations.pickupmtaani.infrastructure.PickupMtaaniClient.DeliveryCharge;
import zelisline.ub.integrations.pickupmtaani.infrastructure.PickupMtaaniClient.GeoOption;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class PublicPickupMtaaniIT {

    private static final String TENANT = "dddddddd-dddd-dddd-dddd-dddddddddd8";
    private static final String SLUG = "pum-public-co";
    private static final String BRANCH = "branch-pum-1";
    private static final String API_KEY = "tenant-pum-key";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private BusinessRepository businessRepository;
    @Autowired
    private BranchRepository branchRepository;
    @Autowired
    private CredentialEncryptionService credentialEncryptionService;

    @MockitoBean
    @SuppressWarnings("unused")
    private PickupMtaaniClient pickupMtaaniClient;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    @BeforeEach
    void seed() {
        branchRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("PUM Public Co");
        b.setSlug(SLUG);
        b.setCurrency("KES");
        businessRepository.save(b);

        Branch br = new Branch();
        br.setId(BRANCH);
        br.setBusinessId(TENANT);
        br.setName("Main");
        branchRepository.save(br);

        b.setSettings(settingsWithPickup());
        businessRepository.save(b);
    }

    private String settingsWithPickup() {
        String enc = credentialEncryptionService.encryptSecret(API_KEY);
        return """
                {"storefront":{"enabled":true,"catalogBranchId":"%s"},
                 "pickupMtaani":{"enabled":true,"apiKeyEnc":"%s","accountMode":"single_business",
                   "originAgentId":362,"originAgentName":"Kahawa West Agent","agent":true,"doorstep":true,
                   "feeMode":"pass_through","markupKes":0}}
                """.formatted(BRANCH, enc);
    }

    private String base() {
        return "/api/v1/public/businesses/" + SLUG + "/pickup-mtaani";
    }

    @Test
    void destinations_noArea_returnsAreas() throws Exception {
        when(pickupMtaaniClient.listAreas(API_KEY, null))
                .thenReturn(List.of(new GeoOption(1L, "Nairobi", null, null, null)));

        mockMvc.perform(get(base() + "/destinations").param("mode", "agent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("area"))
                .andExpect(jsonPath("$.options[0].name").value("Nairobi"));
    }

    @Test
    void destinations_area_returnsLocations() throws Exception {
        when(pickupMtaaniClient.listLocations(API_KEY, 5L, "destination", null))
                .thenReturn(List.of(new GeoOption(50L, "Westlands", null, 5L, null)));

        mockMvc.perform(get(base() + "/destinations").param("areaId", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("location"))
                .andExpect(jsonPath("$.options[0].name").value("Westlands"));
    }

    @Test
    void destinations_location_returnsAgents() throws Exception {
        when(pickupMtaaniClient.listAgents(API_KEY, 7L, "destination", null))
                .thenReturn(List.of(new GeoOption(454L, "Westlands Agent", 7L, null, null)));

        mockMvc.perform(get(base() + "/destinations").param("locationId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("agent"))
                .andExpect(jsonPath("$.options[0].id").value(454));
    }

    @Test
    void destinations_doorstepMode_returnsDoorstepDestinations() throws Exception {
        when(pickupMtaaniClient.listDoorstepDestinations(API_KEY, null, null))
                .thenReturn(List.of(new GeoOption(900L, "Doorstep A", null, null, null)));

        mockMvc.perform(get(base() + "/destinations").param("mode", "doorstep"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("doorstep"))
                .andExpect(jsonPath("$.options[0].id").value(900));
    }

    @Test
    void quote_agent_returnsPricedQuote() throws Exception {
        when(pickupMtaaniClient.getAgentDeliveryCharge(API_KEY, 362L, 454L))
                .thenReturn(new DeliveryCharge(new BigDecimal("150.00"), "{}"));

        String body = objectMapper.writeValueAsString(Map.of(
                "mode", "agent",
                "destinationId", 454,
                "destinationLabel", "Westlands Agent"));

        mockMvc.perform(post(base() + "/quote").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quoteId").isNotEmpty())
                .andExpect(jsonPath("$.mode").value("agent"))
                .andExpect(jsonPath("$.amountKes").value(150.00));
    }

    @Test
    void destinations_notConfigured_notFound() throws Exception {
        Business b = businessRepository.findById(TENANT).orElseThrow();
        b.setSettings("{\"storefront\":{\"enabled\":true,\"catalogBranchId\":\"" + BRANCH + "\"}}");
        businessRepository.save(b);

        mockMvc.perform(get(base() + "/destinations").param("mode", "agent"))
                .andExpect(status().isNotFound());
    }

    @Test
    void checkoutOptions_includesPickupMtaaniWhenEligible() throws Exception {
        mockMvc.perform(get("/api/v1/public/businesses/" + SLUG + "/payments/checkout-options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pickupMtaani.enabled").value(true))
                .andExpect(jsonPath("$.pickupMtaani.agent").value(true))
                .andExpect(jsonPath("$.pickupMtaani.originLabel").value("Kahawa West Agent"));
    }

    @Test
    void checkoutOptions_omitsPickupMtaaniForNonKesShop() throws Exception {
        Business b = businessRepository.findById(TENANT).orElseThrow();
        b.setCurrency("USD");
        businessRepository.save(b);

        mockMvc.perform(get("/api/v1/public/businesses/" + SLUG + "/payments/checkout-options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pickupMtaani").doesNotExist());
    }
}
