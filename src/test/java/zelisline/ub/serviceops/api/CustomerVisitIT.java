package zelisline.ub.serviceops.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;

import zelisline.ub.identity.domain.User;
import zelisline.ub.sales.domain.SaleItem;
import zelisline.ub.sales.domain.SalePayment;
import zelisline.ub.serviceops.application.StaffEarningsService;

/**
 * One customer visit: several services, each with its own person, place and progress; one
 * payment; commission per person, tips apart, the shop's share what is left (ADR 0016).
 * Mike (the fixture "John") cuts hair at 50%; Lenah grooms at 50%.
 */
class CustomerVisitIT extends ServiceSaleTestSupport {

    @Autowired
    private StaffEarningsService staffEarningsService;

    private String mike;
    private String lenah;
    private String cut;
    private String grooming;
    private String chair1;
    private String chair3;

    @BeforeEach
    void aBarbershopWithThreeChairs() throws Exception {
        mike = john;
        lenah = createStaff(TENANT, owner, "Lenah");
        cut = createService(TENANT, owner, "{\"name\":\"Cut\",\"durationMinutes\":30,\"price\":200}");
        grooming = createService(TENANT, owner, "{\"name\":\"Beard grooming\",\"durationMinutes\":15,\"price\":100}");
        for (String who : new String[] {mike, lenah}) {
            mockMvc.perform(as(put("/api/v1/commissions/plans/" + who), TENANT, owner, ROLE_OWNER)
                            .content("{\"type\":\"PERCENT\",\"rate\":50}"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(as(put("/api/v1/service-stations"), TENANT, owner, ROLE_OWNER)
                        .content("{\"branchId\":\"" + branchId + "\",\"count\":3}"))
                .andExpect(status().isOk());
        JsonNode resources = resources();
        assertThat(resources).hasSize(3);
        chair1 = resources.get(0).get("id").asText();
        chair3 = resources.get(2).get("id").asText();
        assertThat(resources.get(0).get("name").asText()).isEqualTo("Chair 1");
        // Reading twice does not make the chairs twice.
        assertThat(resources()).hasSize(3);
    }

    @Test
    void mikeCutsLenahGroomsAndEachIsPaidForTheirOwnWork() throws Exception {
        JsonNode visit = open(cut, grooming);
        String id = visit.get("id").asText();
        String cutLine = visit.get("lines").get(0).get("id").asText();
        String groomLine = visit.get("lines").get(1).get("id").asText();

        // Not payable while nobody is named: the person who opened it is not assumed to have done it.
        pay(id, "{\"payments\":[{\"method\":\"cash\",\"amount\":300}]}", 409);

        JsonNode started = line(id, cutLine, "start", "{\"performerStaffProfileId\":\"" + mike + "\",\"resourceId\":\"" + chair1 + "\"}", 200);
        assertThat(started.get("stage").asText()).isEqualTo("IN_SERVICE");
        assertThat(started.get("lines").get(0).get("performerName").asText()).isEqualTo("John");
        assertThat(started.get("lines").get(0).get("resourceName").asText()).isEqualTo("Chair 1");
        assertThat(started.get("lines").get(0).get("stage").asText()).isEqualTo("IN_SERVICE");
        assertThat(started.get("lines").get(1).get("stage").asText()).isEqualTo("WAITING");
        // A double tap is harmless.
        line(id, cutLine, "start", "{\"performerStaffProfileId\":\"" + mike + "\",\"resourceId\":\"" + chair1 + "\"}", 200);

        // Lenah starts the grooming on another chair while the cut is still going on.
        line(id, groomLine, "start", "{\"performerStaffProfileId\":\"" + lenah + "\",\"resourceId\":\"" + chair3 + "\"}", 200);
        // A service under way cannot be removed.
        mockMvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/service-tickets/" + id + "/lines/" + groomLine), TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isConflict());

        // Mike finishes first: the visit goes on, but Mike and Chair 1 are free.
        JsonNode cutDone = line(id, cutLine, "done", "{}", 200);
        assertThat(cutDone.get("stage").asText()).isEqualTo("IN_SERVICE");
        assertThat(cutDone.get("lines").get(0).get("stage").asText()).isEqualTo("DONE");
        assertThat(busy(chair1)).isFalse();
        assertThat(busy(chair3)).isTrue();
        JsonNode allDone = line(id, groomLine, "done", "{}", 200);
        assertThat(allDone.get("stage").asText()).isEqualTo("READY_TO_PAY");

        // What it will pay out, before any money moves.
        JsonNode preview = earnings(id, owner, ROLE_OWNER, 200);
        assertThat(preview.get("serviceTotal").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(preview.get("commissionTotal").decimalValue()).isEqualByComparingTo("150.00");
        assertThat(preview.get("shopShare").decimalValue()).isEqualByComparingTo("150.00");
        assertThat(preview.get("lines").get(0).get("shopShare").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(preview.get("lines").get(1).get("shares").get(0).get("commission").decimalValue())
                .isEqualByComparingTo("50.00");
        // Earnings are private: a seller cannot see them, nor can another business.
        earnings(id, cashier, ROLE_CASHIER, 403);
        mockMvc.perform(as(get("/api/v1/service-tickets/" + id + "/earnings"), OTHER, otherOwner, ROLE_OWNER))
                .andExpect(status().isNotFound());

        // KSh 400 handed over: 300 for the services and a 100 tip the customer did not assign,
        // split equally; paid part M-Pesa, part cash.
        JsonNode paid = pay(id, "{\"payments\":[{\"method\":\"mpesa_manual\",\"amount\":250,\"reference\":\"QX1\"},"
                + "{\"method\":\"cash\",\"amount\":150}],\"tips\":[{\"staffProfileId\":\"" + mike
                + "\",\"amount\":50},{\"staffProfileId\":\"" + lenah + "\",\"amount\":50}]}", 200);
        String saleId = paid.get("sale").get("id").asText();
        assertThat(paid.get("sale").get("grandTotal").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(salePaymentRepository.findBySaleIdOrderBySortOrderAsc(saleId))
                .extracting(SalePayment::getMethod).containsExactlyInAnyOrder("mpesa_manual", "cash");
        assertThat(saleItemRepository.findBySaleIdOrderByLineIndexAsc(saleId))
                .extracting(SaleItem::getPerformerStaffProfileId).containsExactly(mike, lenah);

        // Mike: 100 commission + 50 tip. Lenah: 50 + 50. The shop keeps 150 of the 300; the tip is nobody's revenue.
        assertThat(staffEarningsService.balance(TENANT, mike)).isEqualByComparingTo("150.00");
        assertThat(staffEarningsService.balance(TENANT, lenah)).isEqualByComparingTo("100.00");
        assertThat(accountBalance("4010")).isEqualByComparingTo("300.00");
        assertThat(accountBalance("2310")).isEqualByComparingTo("100.00");
        assertThat(accountBalance("2300")).isEqualByComparingTo("150.00");
        assertBalanced(journalLineRepository.findAll());
        // The finished visit keeps who did what, where and when.
        JsonNode kept = objectMapper.readTree(mockMvc.perform(as(get("/api/v1/service-tickets/" + id), TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(kept.get("status").asText()).isEqualTo("completed");
        assertThat(kept.get("lines").get(1).get("resourceName").asText()).isEqualTo("Chair 3");
        assertThat(kept.get("lines").get(1).get("finishedAt").isNull()).isFalse();

        // Refunded in full by voiding: revenue, both commissions and both tips go back.
        mockMvc.perform(as(post("/api/v1/sales/" + saleId + "/void"), TENANT, owner, ROLE_OWNER)
                        .content("{\"notes\":\"customer complaint\"}"))
                .andExpect(status().is2xxSuccessful());
        assertThat(staffEarningsService.balance(TENANT, mike)).isEqualByComparingTo("0.00");
        assertThat(staffEarningsService.balance(TENANT, lenah)).isEqualByComparingTo("0.00");
        assertThat(accountBalance("4010")).isEqualByComparingTo("0.00");
        assertBalanced(journalLineRepository.findAll());
    }

    @Test
    void aTipForOnePersonGoesToThatPersonAlone() throws Exception {
        JsonNode visit = open(cut, grooming);
        String id = visit.get("id").asText();
        // A small shop: name who did each service and take the money, no chairs, no start and stop.
        line(id, visit.get("lines").get(0).get("id").asText(), "assign", "{\"performerStaffProfileId\":\"" + mike + "\"}", 200);
        line(id, visit.get("lines").get(1).get("id").asText(), "assign", "{\"performerStaffProfileId\":\"" + lenah + "\"}", 200);
        pay(id, "{\"payments\":[{\"method\":\"cash\",\"amount\":400}],\"tips\":[{\"staffProfileId\":\"" + mike + "\",\"amount\":100}]}", 200);
        assertThat(staffEarningsService.balance(TENANT, mike)).isEqualByComparingTo("200.00");
        assertThat(staffEarningsService.balance(TENANT, lenah)).isEqualByComparingTo("50.00");
        // Unevenly, 70 and 30, on another visit.
        JsonNode second = open(cut, grooming);
        String id2 = second.get("id").asText();
        line(id2, second.get("lines").get(0).get("id").asText(), "assign", "{\"performerStaffProfileId\":\"" + mike + "\"}", 200);
        line(id2, second.get("lines").get(1).get("id").asText(), "assign", "{\"performerStaffProfileId\":\"" + lenah + "\"}", 200);
        pay(id2, "{\"payments\":[{\"method\":\"cash\",\"amount\":400}],\"tips\":[{\"staffProfileId\":\"" + mike
                + "\",\"amount\":70},{\"staffProfileId\":\"" + lenah + "\",\"amount\":30}]}", 200);
        assertThat(staffEarningsService.balance(TENANT, mike)).isEqualByComparingTo("370.00");
        assertThat(staffEarningsService.balance(TENANT, lenah)).isEqualByComparingTo("130.00");
        // The money handed over must cover services and tips together.
        JsonNode third = open(cut);
        line(third.get("id").asText(), third.get("lines").get(0).get("id").asText(), "assign",
                "{\"performerStaffProfileId\":\"" + mike + "\"}", 200);
        pay(third.get("id").asText(), "{\"payments\":[{\"method\":\"cash\",\"amount\":200}],\"tips\":[{\"staffProfileId\":\""
                + mike + "\",\"amount\":100}]}", 400);
    }

    @Test
    void aBusyPersonAndABusyChairAreSeparateRefusals() throws Exception {
        JsonNode first = open(cut);
        String firstId = first.get("id").asText();
        String firstLine = first.get("lines").get(0).get("id").asText();
        line(firstId, firstLine, "start", "{\"performerStaffProfileId\":\"" + mike + "\",\"resourceId\":\"" + chair1 + "\"}", 200);

        JsonNode second = open(cut);
        String secondId = second.get("id").asText();
        String secondLine = second.get("lines").get(0).get("id").asText();
        // Mike is busy, whichever chair. Chair 1 is busy, whoever asks.
        line(secondId, secondLine, "start", "{\"performerStaffProfileId\":\"" + mike + "\",\"resourceId\":\"" + chair3 + "\"}", 409);
        line(secondId, secondLine, "start", "{\"performerStaffProfileId\":\"" + lenah + "\",\"resourceId\":\"" + chair1 + "\"}", 409);
        // The one-person, one-chair path of the floor board obeys the same rules.
        mockMvc.perform(as(post("/api/v1/service-tickets/" + secondId + "/start"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"performerStaffProfileId\":\"" + mike + "\",\"stationNo\":2}"))
                .andExpect(status().isConflict());
        mockMvc.perform(as(post("/api/v1/service-tickets/" + secondId + "/start"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"performerStaffProfileId\":\"" + lenah + "\",\"stationNo\":1}"))
                .andExpect(status().isConflict());
        assertThat(posDraftLineRepository.findById(secondLine).orElseThrow().getLineStage()).isNotEqualTo("IN_SERVICE");

        // Mike finishes early: both he and the chair can be used at once.
        line(firstId, firstLine, "done", "{}", 200);
        line(secondId, secondLine, "start", "{\"performerStaffProfileId\":\"" + mike + "\",\"resourceId\":\"" + chair1 + "\"}", 200);

        // Another business's chair or person is not there to be used.
        String theirs = createStaff(OTHER, otherOwner, "Outsider");
        JsonNode third = open(cut);
        line(third.get("id").asText(), third.get("lines").get(0).get("id").asText(), "start",
                "{\"performerStaffProfileId\":\"" + theirs + "\"}", 400);
        mockMvc.perform(as(post("/api/v1/service-tickets/" + firstId + "/lines/" + firstLine + "/start"), OTHER, otherOwner, ROLE_OWNER)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void resourcesHaveNamesTypesCleaningTimeAndCanBeTakenOutOfUse() throws Exception {
        // A seller cannot change the shop's resources; another business cannot see or touch them.
        mockMvc.perform(as(post("/api/v1/service-resources"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"branchId\":\"" + branchId + "\",\"name\":\"Bed 1\",\"resourceType\":\"BED\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(get("/api/v1/service-resources").param("branchId", branchId), OTHER, otherOwner, ROLE_OWNER))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(patch("/api/v1/service-resources/" + chair1), OTHER, otherOwner, ROLE_OWNER)
                        .content("{\"active\":false}"))
                .andExpect(status().isNotFound());

        MvcResult made = mockMvc.perform(as(post("/api/v1/service-resources"), TENANT, owner, ROLE_OWNER)
                        .content("{\"branchId\":\"" + branchId + "\",\"name\":\"Bed 1\",\"resourceType\":\"BED\",\"cleanupMinutes\":10}"))
                .andExpect(status().isCreated()).andReturn();
        String bed = objectMapper.readTree(made.getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(as(post("/api/v1/service-resources"), TENANT, owner, ROLE_OWNER)
                        .content("{\"branchId\":\"" + branchId + "\",\"name\":\"Bed 1\",\"resourceType\":\"BED\"}"))
                .andExpect(status().isConflict());
        assertThat(resources()).hasSize(4);

        // A massage needs a bed: a chair will not do.
        String massage = createService(TENANT, owner, "{\"name\":\"Massage\",\"durationMinutes\":60,\"price\":1500}");
        mockMvc.perform(as(put("/api/v1/service-resources/requirements/" + massage), TENANT, owner, ROLE_OWNER)
                        .content("{\"resourceType\":\"BED\"}"))
                .andExpect(status().isNoContent());
        JsonNode visit = open(massage);
        String id = visit.get("id").asText();
        String massageLine = visit.get("lines").get(0).get("id").asText();
        line(id, massageLine, "start", "{\"performerStaffProfileId\":\"" + lenah + "\",\"resourceId\":\"" + chair1 + "\"}", 400);
        line(id, massageLine, "start", "{\"performerStaffProfileId\":\"" + lenah + "\",\"resourceId\":\"" + bed + "\"}", 200);
        // In use: it cannot be switched off under the customer.
        mockMvc.perform(as(patch("/api/v1/service-resources/" + bed), TENANT, owner, ROLE_OWNER).content("{\"active\":false}"))
                .andExpect(status().isConflict());
        line(id, massageLine, "done", "{}", 200);

        // Ten minutes to change the sheets: the bed is not free the moment the massage ends.
        JsonNode next = open(massage);
        String nextLine = next.get("lines").get(0).get("id").asText();
        line(next.get("id").asText(), nextLine, "start", "{\"performerStaffProfileId\":\"" + lenah + "\",\"resourceId\":\"" + bed + "\"}", 409);
        mockMvc.perform(as(patch("/api/v1/service-resources/" + bed), TENANT, owner, ROLE_OWNER).content("{\"cleanupMinutes\":0}"))
                .andExpect(status().isOk());
        // Out of use for repair until tomorrow, then back.
        mockMvc.perform(as(patch("/api/v1/service-resources/" + bed), TENANT, owner, ROLE_OWNER)
                        .content("{\"blockedUntil\":\"" + Instant.now().plus(1, ChronoUnit.DAYS) + "\",\"blockedReason\":\"Broken leg\"}"))
                .andExpect(status().isOk());
        line(next.get("id").asText(), nextLine, "start", "{\"performerStaffProfileId\":\"" + lenah + "\",\"resourceId\":\"" + bed + "\"}", 409);
        mockMvc.perform(as(patch("/api/v1/service-resources/" + bed), TENANT, owner, ROLE_OWNER).content("{\"clearBlock\":true}"))
                .andExpect(status().isOk());
        line(next.get("id").asText(), nextLine, "start", "{\"performerStaffProfileId\":\"" + lenah + "\",\"resourceId\":\"" + bed + "\"}", 200);
    }

    @Test
    void threeServicesStayUnderOneVisitAndOnePersonCanDoTwoOfThem() throws Exception {
        String wash = createService(TENANT, owner, "{\"name\":\"Hair wash\",\"durationMinutes\":20,\"price\":150}");
        JsonNode visit = open(cut, grooming);
        String id = visit.get("id").asText();
        // Added while the visit is under way.
        line(id, visit.get("lines").get(0).get("id").asText(), "start", "{\"performerStaffProfileId\":\"" + mike + "\"}", 200);
        MvcResult added = mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/lines"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"serviceId\":\"" + wash + "\"}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode three = objectMapper.readTree(added.getResponse().getContentAsString());
        assertThat(three.get("lines")).hasSize(3);
        assertThat(three.get("total").decimalValue()).isEqualByComparingTo("450.00");
        // Mike does the cut and the wash for this customer; Lenah the grooming.
        line(id, three.get("lines").get(2).get("id").asText(), "assign", "{\"performerStaffProfileId\":\"" + mike + "\"}", 200);
        line(id, three.get("lines").get(1).get("id").asText(), "assign", "{\"performerStaffProfileId\":\"" + lenah + "\"}", 200);
        JsonNode paid = pay(id, "{\"payments\":[{\"method\":\"cash\",\"amount\":450}]}", 200);
        assertThat(saleRepository.count()).isEqualTo(1);
        assertThat(saleItemRepository.findBySaleIdOrderByLineIndexAsc(paid.get("sale").get("id").asText())).hasSize(3);
        assertThat(staffEarningsService.balance(TENANT, mike)).isEqualByComparingTo("175.00");
        assertThat(staffEarningsService.balance(TENANT, lenah)).isEqualByComparingTo("50.00");
        // Paying freed everyone: nothing is left in progress.
        assertThat(posDraftLineRepository.findAll()).allMatch(l -> "DONE".equals(l.getLineStage()));
    }

    // ------------------------------------------------------------------ helpers

    @Test
    void theOwnerSeesWhatEachChairMade() throws Exception {
        // One visit: the cut on Chair 1, the grooming on Chair 3. A second visit: a cut with no chair chosen.
        JsonNode visit = open(cut, grooming);
        String id = visit.get("id").asText();
        String cutLine = visit.get("lines").get(0).get("id").asText();
        String groomLine = visit.get("lines").get(1).get("id").asText();
        line(id, cutLine, "start", "{\"performerStaffProfileId\":\"" + mike + "\",\"resourceId\":\"" + chair1 + "\"}", 200);
        line(id, groomLine, "start", "{\"performerStaffProfileId\":\"" + lenah + "\",\"resourceId\":\"" + chair3 + "\"}", 200);
        line(id, cutLine, "done", "{}", 200);
        line(id, groomLine, "done", "{}", 200);

        // Nothing is counted until the money is taken.
        JsonNode before = chairs(owner, ROLE_OWNER, 200);
        assertThat(before.get("revenue").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(before.get("chairs")).hasSize(3);

        JsonNode paid = pay(id, "{\"payments\":[{\"method\":\"cash\",\"amount\":300}]}", 200);
        JsonNode walkIn = open(cut);
        String walkInId = walkIn.get("id").asText();
        line(walkInId, walkIn.get("lines").get(0).get("id").asText(), "assign",
                "{\"performerStaffProfileId\":\"" + mike + "\"}", 200);
        pay(walkInId, "{\"payments\":[{\"method\":\"cash\",\"amount\":200}]}", 200);

        JsonNode report = chairs(owner, ROLE_OWNER, 200);
        assertThat(report.get("revenue").decimalValue()).isEqualByComparingTo("500.00");
        JsonNode first = report.get("chairs").get(0);
        assertThat(first.get("name").asText()).isEqualTo("Chair 1");
        assertThat(first.get("revenue").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(first.get("servicesDone").asLong()).isEqualTo(1);
        assertThat(first.get("customers").asLong()).isEqualTo(1);
        assertThat(first.get("minutes").asLong()).isEqualTo(30);
        // An idle chair is listed as idle, not left out.
        assertThat(report.get("chairs").get(1).get("revenue").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(report.get("chairs").get(2).get("revenue").decimalValue()).isEqualByComparingTo("100.00");
        // Work with no chair recorded is shown apart, never shared out between the chairs.
        assertThat(report.get("unplacedRevenue").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(report.get("unplacedServices").asLong()).isEqualTo(1);

        // Takings are the owner's to see: not a seller's, and not another business's.
        chairs(cashier, ROLE_CASHIER, 403);
        mockMvc.perform(as(get("/api/v1/commissions/reports/chairs").param("branchId", branchId), OTHER, otherOwner, ROLE_OWNER))
                .andExpect(status().is4xxClientError());

        // A voided sale leaves the chairs it was done on.
        mockMvc.perform(as(post("/api/v1/sales/" + paid.get("sale").get("id").asText() + "/void"), TENANT, owner, ROLE_OWNER)
                        .content("{\"notes\":\"customer complaint\"}"))
                .andExpect(status().is2xxSuccessful());
        JsonNode after = chairs(owner, ROLE_OWNER, 200);
        assertThat(after.get("revenue").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(after.get("chairs").get(0).get("revenue").decimalValue()).isEqualByComparingTo("0.00");
    }

    private JsonNode chairs(User user, String role, int expected) throws Exception {
        MvcResult result = mockMvc.perform(as(get("/api/v1/commissions/reports/chairs").param("branchId", branchId), TENANT, user, role))
                .andExpect(status().is(expected)).andReturn();
        return expected >= 400 ? null : objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode open(String... serviceIds) throws Exception {
        StringBuilder lines = new StringBuilder();
        for (String serviceId : serviceIds) {
            lines.append(lines.length() == 0 ? "" : ",").append("{\"serviceId\":\"").append(serviceId).append("\"}");
        }
        MvcResult result = mockMvc.perform(as(post("/api/v1/service-tickets"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"branchId\":\"" + branchId + "\",\"clientTicketId\":\"" + UUID.randomUUID()
                                + "\",\"lines\":[" + lines + "]}"))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode line(String id, String lineId, String action, String body, int expected) throws Exception {
        MvcResult result = mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/lines/" + lineId + "/" + action),
                        TENANT, cashier, ROLE_CASHIER).content(body))
                .andExpect(status().is(expected)).andReturn();
        return expected >= 400 ? null : objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode pay(String id, String body, int expected) throws Exception {
        MvcResult result = mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/pay"), TENANT, cashier, ROLE_CASHIER)
                        .header("Idempotency-Key", "pay-" + UUID.randomUUID()).content(body))
                .andExpect(status().is(expected)).andReturn();
        return expected >= 400 ? null : objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode earnings(String id, User user, String role, int expected) throws Exception {
        MvcResult result = mockMvc.perform(as(get("/api/v1/service-tickets/" + id + "/earnings"), TENANT, user, role))
                .andExpect(status().is(expected)).andReturn();
        return expected >= 400 ? null : objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode resources() throws Exception {
        MvcResult result = mockMvc.perform(as(get("/api/v1/service-resources").param("branchId", branchId), TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("resources");
    }

    private boolean busy(String resourceId) throws Exception {
        for (JsonNode r : resources()) {
            if (resourceId.equals(r.get("id").asText())) {
                return !r.get("busyTicketId").isNull();
            }
        }
        throw new AssertionError("no such resource");
    }
}
