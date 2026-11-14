package zelisline.ub.serviceops.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;

import zelisline.ub.serviceops.application.StaffEarningsService;

/** Two staff on one service line: each earns on their own share, by their own plan (ADR 0015). */
class ServiceSharedWorkIT extends ServiceSaleTestSupport {

    @Autowired
    private StaffEarningsService staffEarningsService;

    private String mary;

    @BeforeEach
    void twoStaffWithDifferentPlans() throws Exception {
        mary = createStaff(TENANT, owner, "Mary");
        mockMvc.perform(as(put("/api/v1/commissions/plans/" + john), TENANT, owner, ROLE_OWNER)
                        .content("{\"type\":\"PERCENT\",\"rate\":40}"))
                .andExpect(status().isOk());
        mockMvc.perform(as(put("/api/v1/commissions/plans/" + mary), TENANT, owner, ROLE_OWNER)
                        .content("{\"type\":\"FIXED\",\"rate\":50}"))
                .andExpect(status().isOk());
        mockMvc.perform(as(put("/api/v1/service-stations"), TENANT, owner, ROLE_OWNER)
                        .content("{\"branchId\":\"" + branchId + "\",\"count\":2}"))
                .andExpect(status().isOk());
    }

    @Test
    void eachEarnsOnTheirOwnShareAndAVoidTakesBothBack() throws Exception {
        JsonNode sale = postSale("k-" + UUID.randomUUID(), shared(john, mary, "40"), "300", 201);
        // John: 40% of his 180. Mary: her fixed 50, for the 40% of the work she did.
        assertThat(staffEarningsService.balance(TENANT, john)).isEqualByComparingTo("72.00");
        assertThat(staffEarningsService.balance(TENANT, mary)).isEqualByComparingTo("20.00");
        assertBalanced(journalLineRepository.findAll());

        mockMvc.perform(as(post("/api/v1/sales/" + sale.get("id").asText() + "/void"), TENANT, owner, ROLE_OWNER)
                        .content("{\"notes\":\"wrong customer\"}"))
                .andExpect(status().is2xxSuccessful());
        assertThat(staffEarningsService.balance(TENANT, john)).isEqualByComparingTo("0.00");
        assertThat(staffEarningsService.balance(TENANT, mary)).isEqualByComparingTo("0.00");
    }

    @Test
    void halfEachWhenNoShareIsGiven() throws Exception {
        postSale("k-" + UUID.randomUUID(), shared(john, mary, null), "300", 201);
        assertThat(staffEarningsService.balance(TENANT, john)).isEqualByComparingTo("60.00");
        assertThat(staffEarningsService.balance(TENANT, mary)).isEqualByComparingTo("25.00");
    }

    @Test
    void theSecondStaffMemberMustBeSomeoneElseWhoDoesTheServiceInThisBusiness() throws Exception {
        postSale("k-" + UUID.randomUUID(), shared(john, john, "50"), "300", 400);
        postSale("k-" + UUID.randomUUID(), shared(john, "nobody", "50"), "300", 400);
        postSale("k-" + UUID.randomUUID(), shared(john, mary, "0"), "300", 400);
        postSale("k-" + UUID.randomUUID(), shared(john, mary, "100"), "300", 400);
        // A share with nobody to give it to.
        postSale("k-" + UUID.randomUUID(), "{\"kind\":\"SERVICE\",\"serviceId\":\"" + haircut
                + "\",\"performerStaffProfileId\":\"" + john + "\",\"quantity\":1,\"unitPrice\":300,"
                + "\"assistantSharePercent\":30}", "300", 400);
        // Another business's staff member.
        String theirs = createStaff(OTHER, otherOwner, "Outsider");
        postSale("k-" + UUID.randomUUID(), shared(john, theirs, "50"), "300", 400);
        // Only people allowed to do this service.
        mockMvc.perform(as(put("/api/v1/services/" + haircut + "/staff"), TENANT, owner, ROLE_OWNER)
                        .content("{\"staffProfileIds\":[\"" + john + "\"]}"))
                .andExpect(status().isOk());
        postSale("k-" + UUID.randomUUID(), shared(john, mary, "50"), "300", 400);
        assertThat(saleRepository.count()).isZero();
        assertThat(staffLedgerEntryRepository.count()).isZero();
    }

    @Test
    void threePeopleCanShareOneShaveOnATicket() throws Exception {
        String peter = createStaff(TENANT, owner, "Peter");
        mockMvc.perform(as(put("/api/v1/commissions/plans/" + peter), TENANT, owner, ROLE_OWNER)
                        .content("{\"type\":\"PERCENT\",\"rate\":50}"))
                .andExpect(status().isOk());
        MvcResult created = mockMvc.perform(as(post("/api/v1/service-tickets"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"branchId\":\"" + branchId + "\",\"clientTicketId\":\"" + UUID.randomUUID()
                                + "\",\"lines\":[{\"serviceId\":\"" + haircut + "\"}]}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode ticket = objectMapper.readTree(created.getResponse().getContentAsString());
        String id = ticket.get("id").asText();
        String line = ticket.get("lines").get(0).get("id").asText();
        act(id, "start", "{\"performerStaffProfileId\":\"" + john + "\",\"stationNo\":1}", 200);
        String path = "lines/" + line + "/helpers";

        // The person serving cannot also be a helper; nobody twice; shares must leave him something.
        act(id, path, "{\"helpers\":[{\"staffProfileId\":\"" + john + "\"}]}", 400);
        act(id, path, "{\"helpers\":[{\"staffProfileId\":\"" + mary + "\"},{\"staffProfileId\":\"" + mary + "\"}]}", 400);
        act(id, path, "{\"helpers\":[{\"staffProfileId\":\"" + mary + "\",\"sharePercent\":60},{\"staffProfileId\":\""
                + peter + "\",\"sharePercent\":40}]}", 400);
        mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/" + path), OTHER, otherOwner, ROLE_OWNER)
                        .content("{\"helpers\":[{\"staffProfileId\":\"" + mary + "\"}]}"))
                .andExpect(status().isNotFound());

        // No shares given: an equal three-way split.
        JsonNode equal = act(id, path, "{\"helpers\":[{\"staffProfileId\":\"" + mary + "\"},{\"staffProfileId\":\""
                + peter + "\"}]}", 200);
        assertThat(equal.get("lines").get(0).get("helpers")).hasSize(2);
        assertThat(equal.get("lines").get(0).get("helpers").get(0).get("sharePercent").decimalValue())
                .isEqualByComparingTo("33.33");
        // Cleared, then set with exact shares: Mary 25%, Peter 25%, John keeps half.
        assertThat(act(id, path, "{\"helpers\":[]}", 200).get("lines").get(0).get("helpers")).isEmpty();
        act(id, path, "{\"helpers\":[{\"staffProfileId\":\"" + mary + "\",\"sharePercent\":25},{\"staffProfileId\":\""
                + peter + "\",\"sharePercent\":25}]}", 200);

        act(id, "done", "{}", 200);
        MvcResult paid = mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/pay"), TENANT, cashier, ROLE_CASHIER)
                        .header("Idempotency-Key", "p-" + UUID.randomUUID())
                        .content("{\"payments\":[{\"method\":\"cash\",\"amount\":300}]}"))
                .andExpect(status().isOk()).andReturn();
        // John: 40% of 150. Mary: a quarter of her fixed 50. Peter: 50% of 75, rounded to shillings.
        assertThat(staffEarningsService.balance(TENANT, john)).isEqualByComparingTo("60.00");
        assertThat(staffEarningsService.balance(TENANT, mary)).isEqualByComparingTo("13.00");
        assertThat(staffEarningsService.balance(TENANT, peter)).isEqualByComparingTo("38.00");
        assertBalanced(journalLineRepository.findAll());

        // A void takes all three back.
        String saleId = objectMapper.readTree(paid.getResponse().getContentAsString()).get("sale").get("id").asText();
        mockMvc.perform(as(post("/api/v1/sales/" + saleId + "/void"), TENANT, owner, ROLE_OWNER)
                        .content("{\"notes\":\"wrong customer\"}"))
                .andExpect(status().is2xxSuccessful());
        for (String who : new String[] {john, mary, peter}) {
            assertThat(staffEarningsService.balance(TENANT, who)).isEqualByComparingTo("0.00");
        }
    }

    private String shared(String lead, String second, String share) {
        return "{\"kind\":\"SERVICE\",\"serviceId\":\"" + haircut + "\",\"performerStaffProfileId\":\"" + lead
                + "\",\"quantity\":1,\"unitPrice\":300,\"assistantStaffProfileId\":\"" + second + "\""
                + (share == null ? "" : ",\"assistantSharePercent\":" + share) + "}";
    }

    private JsonNode act(String id, String action, String body, int expected) throws Exception {
        MvcResult result = mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/" + action), TENANT, cashier, ROLE_CASHIER)
                        .content(body))
                .andExpect(status().is(expected)).andReturn();
        return expected >= 400 ? null : objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
