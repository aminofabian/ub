package zelisline.ub.serviceops.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;

import zelisline.ub.posdraft.application.PosDraftService;
import zelisline.ub.sales.domain.SaleItem;
import zelisline.ub.serviceops.application.StaffEarningsService;

/** The walk-in queue: tickets on the held-cart tables, chairs, and payment into a service sale (ADR 0014). */
class ServiceTicketIT extends ServiceSaleTestSupport {

    @Autowired
    private PosDraftService posDraftService;
    @Autowired
    private StaffEarningsService staffEarningsService;

    @BeforeEach
    void threeChairs() throws Exception {
        mockMvc.perform(as(put("/api/v1/service-stations"), TENANT, owner, ROLE_OWNER)
                        .content("{\"branchId\":\"" + branchId + "\",\"count\":3}"))
                .andExpect(status().isOk());
        mockMvc.perform(as(put("/api/v1/commissions/plans/" + john), TENANT, owner, ROLE_OWNER)
                        .content("{\"type\":\"PERCENT\",\"rate\":40}"))
                .andExpect(status().isOk());
    }

    @Test
    void aWalkInGoesFromTheQueueToAChairToPaid() throws Exception {
        String clientId = UUID.randomUUID().toString();
        JsonNode ticket = createTicket(clientId, null);
        String id = ticket.get("id").asText();
        assertThat(ticket.get("stage").asText()).isEqualTo("WAITING");
        assertThat(ticket.get("total").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(ticket.get("ticketNumber").asLong()).isPositive();

        // The same client id again is the same ticket.
        assertThat(createTicket(clientId, null).get("id").asText()).isEqualTo(id);
        assertThat(board().get("tickets")).hasSize(1);
        assertThat(board().get("stationCount").asInt()).isEqualTo(3);

        // Payment is refused until someone has served the customer.
        payTicket(id, "p0-" + UUID.randomUUID(), "300", 409);

        JsonNode started = act(id, "start", "{\"performerStaffProfileId\":\"" + john + "\",\"stationNo\":2}", 200);
        assertThat(started.get("stage").asText()).isEqualTo("IN_SERVICE");
        assertThat(started.get("stationNo").asInt()).isEqualTo(2);
        assertThat(started.get("staffName").asText()).isEqualTo("John");
        // A double tap on Start is harmless.
        act(id, "start", "{\"performerStaffProfileId\":\"" + john + "\",\"stationNo\":2}", 200);

        assertThat(act(id, "done", "{}", 200).get("stage").asText()).isEqualTo("READY_TO_PAY");
        act(id, "done", "{}", 200);
        // Done by mistake: back in the chair, then done again.
        assertThat(act(id, "reopen", "{}", 200).get("stage").asText()).isEqualTo("IN_SERVICE");
        act(id, "reopen", "{}", 200);
        act(id, "done", "{}", 200);

        String key = "pay-" + UUID.randomUUID();
        JsonNode paid = payTicket(id, key, "300", 200);
        assertThat(paid.get("ticket").get("status").asText()).isEqualTo("completed");
        String saleId = paid.get("sale").get("id").asText();
        SaleItem line = saleItemRepository.findBySaleIdOrderByLineIndexAsc(saleId).getFirst();
        assertThat(line.getLineKind()).isEqualTo("SERVICE");
        assertThat(line.getPerformerStaffProfileId()).isEqualTo(john);
        assertThat(staffEarningsService.balance(TENANT, john)).isEqualByComparingTo("120.00");

        // Paying again, same key or a new one, never rings a second sale.
        assertThat(payTicket(id, key, "300", 200).get("sale").get("id").asText()).isEqualTo(saleId);
        assertThat(payTicket(id, "other-" + UUID.randomUUID(), "300", 200).get("sale").get("id").asText())
                .isEqualTo(saleId);
        assertThat(saleRepository.count()).isEqualTo(1);
        assertThat(staffEarningsService.balance(TENANT, john)).isEqualByComparingTo("120.00");
        // A paid ticket leaves the board and cannot be cancelled.
        assertThat(board().get("tickets")).isEmpty();
        act(id, "cancel", "{\"reason\":\"oops\"}", 409);
    }

    @Test
    void twoCustomersCannotShareAChairAndChairsMustExist() throws Exception {
        String peter = createStaff(TENANT, owner, "Peter");
        String first = createTicket(UUID.randomUUID().toString(), null).get("id").asText();
        String second = createTicket(UUID.randomUUID().toString(), null).get("id").asText();
        act(first, "start", "{\"performerStaffProfileId\":\"" + john + "\",\"stationNo\":1}", 200);
        act(second, "start", "{\"performerStaffProfileId\":\"" + peter + "\",\"stationNo\":1}", 409);
        act(second, "start", "{\"performerStaffProfileId\":\"" + peter + "\",\"stationNo\":4}", 400);
        act(second, "start", "{\"performerStaffProfileId\":\"" + peter + "\",\"stationNo\":0}", 400);
        act(second, "start", "{\"performerStaffProfileId\":\"not-a-barber\",\"stationNo\":2}", 400);
        act(second, "start", "{\"performerStaffProfileId\":\"" + peter + "\",\"stationNo\":2}", 200);

        // Once the first customer is done the chair is free again, and a mistaken Done cannot
        // put them back into a chair someone else has taken.
        act(first, "done", "{}", 200);
        String third = createTicket(UUID.randomUUID().toString(), john).get("id").asText();
        act(third, "start", "{\"performerStaffProfileId\":\"" + john + "\",\"stationNo\":1}", 200);
        act(first, "reopen", "{}", 409);
    }

    @Test
    void servicesCanBeAddedAndRemovedUntilPaymentAndAWalkAwayIsRecorded() throws Exception {
        String shave = createService(TENANT, owner, "{\"name\":\"Shave\",\"durationMinutes\":15,\"price\":100}");
        String id = createTicket(UUID.randomUUID().toString(), john).get("id").asText();
        act(id, "start", "{\"performerStaffProfileId\":\"" + john + "\",\"stationNo\":3}", 200);

        JsonNode withShave = act(id, "lines", "{\"serviceId\":\"" + shave + "\"}", 200);
        assertThat(withShave.get("lines")).hasSize(2);
        assertThat(withShave.get("total").decimalValue()).isEqualByComparingTo("400.00");
        String shaveLine = withShave.get("lines").get(1).get("id").asText();
        MvcResult removed = mockMvc.perform(as(delete("/api/v1/service-tickets/" + id + "/lines/" + shaveLine),
                        TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isOk()).andReturn();
        assertThat(objectMapper.readTree(removed.getResponse().getContentAsString()).get("total").decimalValue())
                .isEqualByComparingTo("300.00");
        // The last service cannot be removed; cancel the ticket instead.
        String only = objectMapper.readTree(removed.getResponse().getContentAsString())
                .get("lines").get(0).get("id").asText();
        mockMvc.perform(as(delete("/api/v1/service-tickets/" + id + "/lines/" + only), TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isBadRequest());

        // The customer is in the chair: a seller alone cannot throw the ticket away.
        act(id, "cancel", "{\"abandoned\":true}", 403);
        MvcResult cancelled = mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/cancel"), TENANT, owner, ROLE_OWNER)
                        .content("{\"abandoned\":true}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode left = objectMapper.readTree(cancelled.getResponse().getContentAsString());
        assertThat(left.get("status").asText()).isEqualTo("cancelled");
        assertThat(left.get("stage").asText()).isEqualTo("ABANDONED");
        assertThat(posDraftRepository.findById(id).orElseThrow().getCancelledReason()).isEqualTo("Customer left");
        // Cancelling twice is harmless; a cancelled ticket cannot be paid or changed.
        act(id, "cancel", "{}", 200);
        payTicket(id, "x-" + UUID.randomUUID(), "300", 409);
        act(id, "done", "{}", 409);
        assertThat(saleRepository.count()).isZero();
        assertThat(board().get("tickets")).isEmpty();
    }

    @Test
    void aTicketIsNotAHeldCartAndBelongsToItsBusiness() throws Exception {
        String id = createTicket(UUID.randomUUID().toString(), null).get("id").asText();
        // The retail held-cart code cannot open, change or complete it.
        assertThatThrownBy(() -> posDraftService.getDraft(TENANT, id, false))
                .isInstanceOf(ResponseStatusException.class);

        mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/start"), OTHER, otherOwner, ROLE_OWNER)
                        .content("{\"performerStaffProfileId\":\"" + john + "\",\"stationNo\":1}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/cancel"), OTHER, otherOwner, ROLE_OWNER)
                        .content("{}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/pay"), OTHER, otherOwner, ROLE_OWNER)
                        .header("Idempotency-Key", "o-" + UUID.randomUUID())
                        .content("{\"payments\":[{\"method\":\"cash\",\"amount\":300}]}"))
                .andExpect(status().isNotFound());
        // Another business cannot read this branch's queue or open a ticket on it.
        mockMvc.perform(as(get("/api/v1/service-tickets").param("branchId", branchId), OTHER, otherOwner, ROLE_OWNER))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(post("/api/v1/service-tickets"), OTHER, otherOwner, ROLE_OWNER)
                        .content("{\"branchId\":\"" + branchId + "\",\"clientTicketId\":\"" + UUID.randomUUID()
                                + "\",\"lines\":[{\"serviceId\":\"" + haircut + "\"}]}"))
                .andExpect(status().isBadRequest());
        assertThat(posDraftRepository.findById(id).orElseThrow().getServiceStage()).isEqualTo("WAITING");
    }

    @Test
    void aTipCanBeTakenWhenTheTicketIsPaid() throws Exception {
        String id = createTicket(UUID.randomUUID().toString(), john).get("id").asText();
        act(id, "start", "{\"performerStaffProfileId\":\"" + john + "\",\"stationNo\":1}", 200);
        MvcResult result = mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/pay"), TENANT, cashier, ROLE_CASHIER)
                        .header("Idempotency-Key", "tip-" + UUID.randomUUID())
                        .content("{\"payments\":[{\"method\":\"cash\",\"amount\":350}],\"tips\":[{\"staffProfileId\":\""
                                + john + "\",\"amount\":50}]}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode sale = objectMapper.readTree(result.getResponse().getContentAsString()).get("sale");
        assertThat(sale.get("tipTotal").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(staffEarningsService.balance(TENANT, john)).isEqualByComparingTo("170.00");
        // No key, no payment.
        String other = createTicket(UUID.randomUUID().toString(), john).get("id").asText();
        act(other, "start", "{\"performerStaffProfileId\":\"" + john + "\",\"stationNo\":1}", 200);
        mockMvc.perform(as(post("/api/v1/service-tickets/" + other + "/pay"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"payments\":[{\"method\":\"cash\",\"amount\":300}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void closingAShiftDoesNotThrowAwayOpenTickets() throws Exception {
        String waiting = createTicket(UUID.randomUUID().toString(), null).get("id").asText();
        String inChair = createTicket(UUID.randomUUID().toString(), john).get("id").asText();
        act(inChair, "start", "{\"performerStaffProfileId\":\"" + john + "\",\"stationNo\":1}", 200);

        // What shift close runs for held retail carts.
        int cancelled = posDraftService.cancelPendingForClosedShift(
                TENANT, shiftRepository.findAll().getFirst(), cashier.getId());

        assertThat(cancelled).isZero();
        assertThat(posDraftRepository.findById(waiting).orElseThrow().getStatus()).isEqualTo("pending");
        assertThat(posDraftRepository.findById(inChair).orElseThrow().getStatus()).isEqualTo("pending");
        assertThat(board().get("tickets")).hasSize(2);
    }

    @Test
    void aPriceChangeUpdatesTheTicketInsteadOfMakingItUnpayable() throws Exception {
        String id = createTicket(UUID.randomUUID().toString(), john).get("id").asText();
        act(id, "start", "{\"performerStaffProfileId\":\"" + john + "\",\"stationNo\":1}", 200);
        mockMvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/services/" + haircut), TENANT, owner, ROLE_OWNER)
                        .content("{\"price\":350}"))
                .andExpect(status().isOk());

        // The first attempt brings the ticket up to date and takes no money.
        payTicket(id, "rp-" + UUID.randomUUID(), "300", 409);
        assertThat(saleRepository.count()).isZero();
        assertThat(posDraftRepository.findById(id).orElseThrow().getGrandTotal()).isEqualByComparingTo("350.00");
        assertThat(board().get("tickets").get(0).get("total").decimalValue()).isEqualByComparingTo("350.00");
        // Then it pays at the new price, by a seller with no price-override permission.
        JsonNode paid = payTicket(id, "rp-" + UUID.randomUUID(), "350", 200);
        assertThat(paid.get("sale").get("grandTotal").decimalValue()).isEqualByComparingTo("350.00");
    }

    @Test
    void aTicketCannotBeStartedBySomeoneWhoDoesNotDoThatService() throws Exception {
        String peter = createStaff(TENANT, owner, "Peter");
        mockMvc.perform(as(put("/api/v1/services/" + haircut + "/staff"), TENANT, owner, ROLE_OWNER)
                        .content("{\"staffProfileIds\":[\"" + peter + "\"]}"))
                .andExpect(status().isOk());
        String id = createTicket(UUID.randomUUID().toString(), null).get("id").asText();
        act(id, "start", "{\"performerStaffProfileId\":\"" + john + "\",\"stationNo\":1}", 400);
        assertThat(posDraftRepository.findById(id).orElseThrow().getServiceStage()).isEqualTo("WAITING");
        act(id, "start", "{\"performerStaffProfileId\":\"" + peter + "\",\"stationNo\":1}", 200);
        payTicket(id, "ok-" + UUID.randomUUID(), "300", 200);
    }

    @Test
    void ticketNumbersRunPerBranch() throws Exception {
        long first = createTicket(UUID.randomUUID().toString(), null).get("ticketNumber").asLong();
        long second = createTicket(UUID.randomUUID().toString(), null).get("ticketNumber").asLong();
        assertThat(second).isEqualTo(first + 1);

        // A second branch starts its own numbering; the same number at another branch is fine.
        zelisline.ub.tenancy.domain.Branch other = new zelisline.ub.tenancy.domain.Branch();
        other.setBusinessId(TENANT);
        other.setName("Second");
        branchRepository.save(other);
        MvcResult result = mockMvc.perform(as(post("/api/v1/service-tickets"), TENANT, owner, ROLE_OWNER)
                        .content("{\"branchId\":\"" + other.getId() + "\",\"clientTicketId\":\"" + UUID.randomUUID()
                                + "\",\"lines\":[{\"serviceId\":\"" + haircut + "\"}]}"))
                .andExpect(status().isCreated()).andReturn();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).get("ticketNumber").asLong())
                .isEqualTo(first);
        assertThat(posDraftRepository.count()).isEqualTo(3);

        // Within one branch a number can never be used twice: the database refuses it.
        zelisline.ub.posdraft.domain.PosDraft clash = new zelisline.ub.posdraft.domain.PosDraft();
        clash.setBusinessId(TENANT);
        clash.setBranchId(branchId);
        clash.setTicketNumber(first);
        clash.setCreatedBy(cashier.getId());
        clash.setClientDraftId(UUID.randomUUID().toString());
        assertThatThrownBy(() -> posDraftRepository.saveAndFlush(clash))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode createTicket(String clientId, String requestedStaff) throws Exception {
        String staff = requestedStaff == null ? "" : ",\"requestedStaffProfileId\":\"" + requestedStaff + "\"";
        MvcResult result = mockMvc.perform(as(post("/api/v1/service-tickets"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"branchId\":\"" + branchId + "\",\"clientTicketId\":\"" + clientId + "\"" + staff
                                + ",\"lines\":[{\"serviceId\":\"" + haircut + "\"}]}"))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode act(String id, String action, String body, int expected) throws Exception {
        MvcResult result = mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/" + action), TENANT, cashier, ROLE_CASHIER)
                        .content(body))
                .andExpect(status().is(expected)).andReturn();
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? null : objectMapper.readTree(content);
    }

    private JsonNode payTicket(String id, String key, String cash, int expected) throws Exception {
        MvcResult result = mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/pay"), TENANT, cashier, ROLE_CASHIER)
                        .header("Idempotency-Key", key)
                        .content("{\"payments\":[{\"method\":\"cash\",\"amount\":" + cash + "}]}"))
                .andExpect(status().is(expected)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode board() throws Exception {
        MvcResult result = mockMvc.perform(as(get("/api/v1/service-tickets").param("branchId", branchId),
                        TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
