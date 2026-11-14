package zelisline.ub.serviceops.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;

import zelisline.ub.identity.domain.User;
import zelisline.ub.serviceops.application.StaffEarningsService;

/** One haircut, one, two or three people on it, and optionally what each is paid for it. */
class ServiceTeamAmountsIT extends ServiceSaleTestSupport {

    @Autowired
    private StaffEarningsService staffEarningsService;

    private String mike;
    private String lenah;
    private String peter;

    @BeforeEach
    void threePeopleOnFortyPercent() throws Exception {
        mike = john;
        lenah = createStaff(TENANT, owner, "Lenah");
        peter = createStaff(TENANT, owner, "Peter");
        for (String who : new String[] {mike, lenah, peter}) {
            mockMvc.perform(as(put("/api/v1/commissions/plans/" + who), TENANT, owner, ROLE_OWNER)
                            .content("{\"type\":\"PERCENT\",\"rate\":40}"))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void theTeamIsSetInOneGoAndSplitsEquallyUnlessAmountsAreTyped() throws Exception {
        JsonNode visit = open();
        String id = visit.get("id").asText();
        String line = visit.get("lines").get(0).get("id").asText();

        // A seller names who worked on it: Mike leads, Lenah and Peter help, a third each.
        JsonNode three = team(id, line, cashier, ROLE_CASHIER, members(mike, null, lenah, null, peter, null), 200);
        JsonNode dto = three.get("lines").get(0);
        assertThat(dto.get("performerStaffProfileId").asText()).isEqualTo(mike);
        assertThat(dto.get("helpers")).hasSize(2);
        assertThat(dto.get("performerAmount").isNull()).isTrue();
        JsonNode byPlan = earnings(id);
        // 40% of a third of 300 each: 40, 40, 40.
        assertThat(byPlan.get("commissionTotal").decimalValue()).isEqualByComparingTo("120.00");

        // The same seller may not decide what people are paid; a manager may.
        team(id, line, cashier, ROLE_CASHIER, members(mike, "150", lenah, null, peter, null), 403);
        // Never more than the haircut costs, never a negative or part of a shilling, nobody twice.
        team(id, line, owner, ROLE_OWNER, members(mike, "200", lenah, "150", peter, null), 400);
        team(id, line, owner, ROLE_OWNER, members(mike, "-5", lenah, null, peter, null), 400);
        team(id, line, owner, ROLE_OWNER, members(mike, "10.50", lenah, null, peter, null), 400);
        team(id, line, owner, ROLE_OWNER, members(mike, null, mike, null, peter, null), 400);
        mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/lines/" + line + "/team"), OTHER, otherOwner, ROLE_OWNER)
                        .content(members(mike, null, lenah, null, peter, null)))
                .andExpect(status().isNotFound());

        // Mike gets 120 and Lenah 60 for this one; Peter is left to his plan (40% of his third).
        JsonNode set = team(id, line, owner, ROLE_OWNER, members(mike, "120", lenah, "60", peter, null), 200);
        assertThat(set.get("lines").get(0).get("performerAmount").decimalValue()).isEqualByComparingTo("120");
        JsonNode preview = earnings(id);
        assertThat(preview.get("commissionTotal").decimalValue()).isEqualByComparingTo("220.00");
        assertThat(preview.get("shopShare").decimalValue()).isEqualByComparingTo("80.00");

        // The cashier takes the money: the amounts were authorised when they were set.
        pay(id, 200);
        assertThat(staffEarningsService.balance(TENANT, mike)).isEqualByComparingTo("120.00");
        assertThat(staffEarningsService.balance(TENANT, lenah)).isEqualByComparingTo("60.00");
        assertThat(staffEarningsService.balance(TENANT, peter)).isEqualByComparingTo("40.00");
        assertThat(accountBalance("2300")).isEqualByComparingTo("220.00");
        assertBalanced(journalLineRepository.findAll());
    }

    @Test
    void onePersonCanBePaidASetAmountAndChangingThemDropsIt() throws Exception {
        JsonNode visit = open();
        String id = visit.get("id").asText();
        String line = visit.get("lines").get(0).get("id").asText();
        team(id, line, owner, ROLE_OWNER, members(mike, "100"), 200);
        assertThat(earnings(id).get("commissionTotal").decimalValue()).isEqualByComparingTo("100.00");
        // Someone else takes over: the amount agreed for Mike is not Lenah's.
        MvcResult swapped = mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/lines/" + line + "/assign"),
                        TENANT, cashier, ROLE_CASHIER).content("{\"performerStaffProfileId\":\"" + lenah + "\"}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(objectMapper.readTree(swapped.getResponse().getContentAsString())
                .get("lines").get(0).get("performerAmount").isNull()).isTrue();
        assertThat(earnings(id).get("commissionTotal").decimalValue()).isEqualByComparingTo("120.00");
        pay(id, 200);
        assertThat(staffEarningsService.balance(TENANT, lenah)).isEqualByComparingTo("120.00");
        assertThat(staffEarningsService.balance(TENANT, mike)).isEqualByComparingTo("0.00");
    }

    @Test
    void aDirectSaleCannotSmuggleInAmountsWithoutPermission() throws Exception {
        String amounts = "{\"kind\":\"SERVICE\",\"serviceId\":\"" + haircut + "\",\"performerStaffProfileId\":\"" + mike
                + "\",\"quantity\":1,\"unitPrice\":300,\"performerAmount\":290}";
        // The fixtures ring sales as the cashier, who may not set what staff are paid.
        postSale("k-" + UUID.randomUUID(), amounts, "300", 403);
        assertThat(saleRepository.count()).isZero();
        assertThat(staffLedgerEntryRepository.count()).isZero();
    }

    private static String members(String... idThenAmount) {
        StringBuilder out = new StringBuilder("{\"members\":[");
        for (int i = 0; i < idThenAmount.length; i += 2) {
            out.append(i == 0 ? "" : ",").append("{\"staffProfileId\":\"").append(idThenAmount[i]).append("\"");
            if (idThenAmount[i + 1] != null) {
                out.append(",\"amount\":").append(idThenAmount[i + 1]);
            }
            out.append("}");
        }
        return out.append("]}").toString();
    }

    private JsonNode open() throws Exception {
        MvcResult result = mockMvc.perform(as(post("/api/v1/service-tickets"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"branchId\":\"" + branchId + "\",\"clientTicketId\":\"" + UUID.randomUUID()
                                + "\",\"lines\":[{\"serviceId\":\"" + haircut + "\"}]}"))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode team(String id, String line, User user, String role, String body, int expected) throws Exception {
        MvcResult result = mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/lines/" + line + "/team"), TENANT, user, role)
                        .content(body))
                .andExpect(status().is(expected)).andReturn();
        return expected >= 400 ? null : objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode earnings(String id) throws Exception {
        return objectMapper.readTree(mockMvc.perform(as(get("/api/v1/service-tickets/" + id + "/earnings"), TENANT, owner, ROLE_OWNER))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private void pay(String id, int expected) throws Exception {
        mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/pay"), TENANT, cashier, ROLE_CASHIER)
                        .header("Idempotency-Key", "pay-" + UUID.randomUUID())
                        .content("{\"payments\":[{\"method\":\"cash\",\"amount\":300}]}"))
                .andExpect(status().is(expected));
    }
}
