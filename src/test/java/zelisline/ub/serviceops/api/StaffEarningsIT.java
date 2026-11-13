package zelisline.ub.serviceops.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;

import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.application.LedgerBalanceQuery;
import zelisline.ub.finance.domain.JournalLine;
import zelisline.ub.identity.domain.User;
import zelisline.ub.payroll.application.StaffProfileService;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.serviceops.application.StaffEarningsService;
import zelisline.ub.serviceops.domain.StaffLedgerEntry;

/** Commission, tips, adjustments and payouts: the staff ledger and its agreement with the GL (ADR 0014). */
class StaffEarningsIT extends ServiceSaleTestSupport {

    @Autowired
    private StaffEarningsService staffEarningsService;
    @Autowired
    private StaffProfileService staffProfileService;
    @Autowired
    private LedgerBalanceQuery ledgerBalanceQuery;

    @Test
    void aServiceEarnsItsPerformerTheirPercentage() throws Exception {
        setPlan(john, "PERCENT", "40", null);
        String saleId = postSale("c1-" + UUID.randomUUID(), serviceLine(haircut, john, 1, "300"), "300", 201)
                .get("id").asText();

        JsonNode row = statementRow(john);
        assertThat(row.get("servicesDone").asLong()).isEqualTo(1);
        assertThat(row.get("serviceRevenue").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(row.get("commission").decimalValue()).isEqualByComparingTo("120.00");
        assertThat(row.get("closing").decimalValue()).isEqualByComparingTo("120.00");
        assertReconciled();

        StaffLedgerEntry entry = staffLedgerEntryRepository
                .findByBusinessIdAndSaleIdOrderByCreatedAtAsc(TENANT, saleId).getFirst();
        assertThat(entry.getRule()).isEqualTo("PERCENT 40.00 (staff)");
        assertThat(entry.getBaseAmount()).isEqualByComparingTo("300.00");
        List<JournalLine> journal = journalLineRepository.findByJournalEntryId(entry.getJournalEntryId());
        assertBalanced(journal);
        assertThat(debit(journal, LedgerAccountCodes.COMMISSION_EXPENSE)).isEqualByComparingTo("120.00");
        assertThat(credit(journal, LedgerAccountCodes.COMMISSIONS_PAYABLE)).isEqualByComparingTo("120.00");

        // A plan change does not rewrite what was already earned.
        setPlan(john, "PERCENT", "10", null);
        assertThat(statementRow(john).get("commission").decimalValue()).isEqualByComparingTo("120.00");
    }

    @Test
    void commissionIsWholeShillingsAndTheServiceOverrideBeatsThePlan() throws Exception {
        setPlan(john, "PERCENT", "40", null);
        String braids = createService(TENANT, owner,
                "{\"name\":\"Braids\",\"durationMinutes\":180,\"price\":0,\"priceIsVariable\":true}");
        postSale("r1-" + UUID.randomUUID(), serviceLine(braids, john, 1, "333"), "333", 201);
        // 40 % of 333 is 133.20: the 20 cents stay with the shop.
        assertThat(statementRow(john).get("commission").decimalValue()).isEqualByComparingTo("133.00");

        String colour = createService(TENANT, owner, "{\"name\":\"Colour\",\"durationMinutes\":90,"
                + "\"price\":1500,\"commissionType\":\"FIXED\",\"commissionValue\":200}");
        postSale("r2-" + UUID.randomUUID(), serviceLine(colour, john, 2, "1500"), "3000", 201);
        // Fixed KSh 200 per service, two services.
        assertThat(statementRow(john).get("commission").decimalValue()).isEqualByComparingTo("533.00");

        // A fixed commission can never exceed what the customer paid.
        String shave = createService(TENANT, owner, "{\"name\":\"Shave\",\"durationMinutes\":15,"
                + "\"price\":100,\"commissionType\":\"FIXED\",\"commissionValue\":500}");
        postSale("r3-" + UUID.randomUUID(), serviceLine(shave, john, 1, "100"), "100", 201);
        assertThat(statementRow(john).get("commission").decimalValue()).isEqualByComparingTo("633.00");
        assertReconciled();
    }

    @Test
    void staffWithNoPlanAreCountedButEarnNothing() throws Exception {
        postSale("n1-" + UUID.randomUUID(), serviceLine(haircut, john, 1, "300"), "300", 201);
        JsonNode row = statementRow(john);
        assertThat(row.get("servicesDone").asLong()).isEqualTo(1);
        assertThat(row.get("commission").decimalValue()).isEqualByComparingTo("0");
        assertThat(row.get("closing").decimalValue()).isEqualByComparingTo("0");
        assertThat(journalEntryRepository.count()).isEqualTo(1);
        assertReconciled();
    }

    @Test
    void aTipIsCollectedWithTheSaleAndOwedInFullToThePerformer() throws Exception {
        setPlan(john, "PERCENT", "40", null);
        String body = saleBodyWithTips(serviceLine(haircut, john, 1, "300"), "350",
                "{\"staffProfileId\":\"" + john + "\",\"amount\":50}");
        String key = "t1-" + UUID.randomUUID();
        MvcResult result = mockMvc.perform(as(post("/api/v1/sales"), TENANT, cashier, ROLE_CASHIER)
                        .header("Idempotency-Key", key).content(body))
                .andExpect(status().isCreated()).andReturn();
        JsonNode sale = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(sale.get("grandTotal").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(sale.get("tipTotal").decimalValue()).isEqualByComparingTo("50.00");

        Sale stored = saleRepository.findById(sale.get("id").asText()).orElseThrow();
        List<JournalLine> journal = journalLineRepository.findByJournalEntryId(stored.getJournalEntryId());
        assertBalanced(journal);
        assertThat(debit(journal, LedgerAccountCodes.OPERATING_CASH)).isEqualByComparingTo("350.00");
        assertThat(credit(journal, LedgerAccountCodes.SERVICE_REVENUE)).isEqualByComparingTo("300.00");
        assertThat(credit(journal, LedgerAccountCodes.TIPS_PAYABLE)).isEqualByComparingTo("50.00");

        JsonNode row = statementRow(john);
        assertThat(row.get("tips").decimalValue()).isEqualByComparingTo("50.00");
        // Commission is on the service, never on the tip.
        assertThat(row.get("commission").decimalValue()).isEqualByComparingTo("120.00");
        assertThat(row.get("closing").decimalValue()).isEqualByComparingTo("170.00");
        // The till holds the tip too until it is paid out.
        assertThat(shiftRepository.findAll().getFirst().getExpectedClosingCash()).isEqualByComparingTo("450.00");

        // Replaying the same sale writes nothing twice.
        mockMvc.perform(as(post("/api/v1/sales"), TENANT, cashier, ROLE_CASHIER)
                        .header("Idempotency-Key", key).content(body))
                .andExpect(status().isOk());
        assertThat(staffLedgerEntryRepository.count()).isEqualTo(2);
        assertReconciled();
    }

    @Test
    void tipsMustBePaidForAndBelongToSomeoneOnTheSale() throws Exception {
        String peter = createStaff(TENANT, owner, "Peter");
        // Payment does not cover the tip.
        mockMvc.perform(as(post("/api/v1/sales"), TENANT, cashier, ROLE_CASHIER)
                        .header("Idempotency-Key", "tb1-" + UUID.randomUUID())
                        .content(saleBodyWithTips(serviceLine(haircut, john, 1, "300"), "300",
                                "{\"staffProfileId\":\"" + john + "\",\"amount\":50}")))
                .andExpect(status().isBadRequest());
        // Peter did nothing on this sale.
        mockMvc.perform(as(post("/api/v1/sales"), TENANT, cashier, ROLE_CASHIER)
                        .header("Idempotency-Key", "tb2-" + UUID.randomUUID())
                        .content(saleBodyWithTips(serviceLine(haircut, john, 1, "300"), "350",
                                "{\"staffProfileId\":\"" + peter + "\",\"amount\":50}")))
                .andExpect(status().isBadRequest());
        // A product-only sale has no one to tip.
        mockMvc.perform(as(post("/api/v1/sales"), TENANT, cashier, ROLE_CASHIER)
                        .header("Idempotency-Key", "tb3-" + UUID.randomUUID())
                        .content(saleBodyWithTips("{\"itemId\":\"" + itemId + "\",\"quantity\":2,\"unitPrice\":5}",
                                "60", "{\"staffProfileId\":\"" + john + "\",\"amount\":50}")))
                .andExpect(status().isBadRequest());
        for (String bad : List.of("0", "-5")) {
            mockMvc.perform(as(post("/api/v1/sales"), TENANT, cashier, ROLE_CASHIER)
                            .header("Idempotency-Key", "tb4-" + UUID.randomUUID())
                            .content(saleBodyWithTips(serviceLine(haircut, john, 1, "300"), "300",
                                    "{\"staffProfileId\":\"" + john + "\",\"amount\":" + bad + "}")))
                    .andExpect(status().isBadRequest());
        }
        assertThat(saleRepository.count()).isZero();
        assertThat(staffLedgerEntryRepository.count()).isZero();
    }

    @Test
    void voidingASaleTakesBackCommissionAndTips() throws Exception {
        setPlan(john, "PERCENT", "40", null);
        MvcResult result = mockMvc.perform(as(post("/api/v1/sales"), TENANT, cashier, ROLE_CASHIER)
                        .header("Idempotency-Key", "v1-" + UUID.randomUUID())
                        .content(saleBodyWithTips(serviceLine(haircut, john, 1, "300"), "350",
                                "{\"staffProfileId\":\"" + john + "\",\"amount\":50}")))
                .andExpect(status().isCreated()).andReturn();
        String saleId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
        assertThat(staffEarningsService.balance(TENANT, john)).isEqualByComparingTo("170.00");

        mockMvc.perform(as(post("/api/v1/sales/{saleId}/void", saleId), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"notes\":\"rang twice\"}"))
                .andExpect(status().isOk());

        assertThat(staffEarningsService.balance(TENANT, john)).isEqualByComparingTo("0.00");
        Sale voided = saleRepository.findById(saleId).orElseThrow();
        List<JournalLine> journal = journalLineRepository.findByJournalEntryId(voided.getVoidJournalEntryId());
        assertBalanced(journal);
        assertThat(debit(journal, LedgerAccountCodes.TIPS_PAYABLE)).isEqualByComparingTo("50.00");
        assertThat(credit(journal, LedgerAccountCodes.OPERATING_CASH)).isEqualByComparingTo("350.00");
        assertThat(shiftRepository.findAll().getFirst().getExpectedClosingCash()).isEqualByComparingTo("100.00");
        JsonNode row = statementRow(john);
        assertThat(row.get("reversals").decimalValue()).isEqualByComparingTo("-170.00");
        assertReconciled();
        // Voiding again changes nothing.
        mockMvc.perform(as(post("/api/v1/sales/{saleId}/void", saleId), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"notes\":\"again\"}"))
                .andExpect(status().isOk());
        assertThat(staffEarningsService.balance(TENANT, john)).isEqualByComparingTo("0.00");
    }

    @Test
    void theSellerEarnsOnProductsAndGivesItBackOnARefund() throws Exception {
        String cashierProfile = staffProfileService.ensureProfile(TENANT, cashier.getId()).getId();
        setPlan(cashierProfile, null, null, "10");
        JsonNode sale = postSale("p1-" + UUID.randomUUID(),
                "{\"itemId\":\"" + itemId + "\",\"quantity\":10,\"unitPrice\":5}", "50", 201);
        assertThat(staffEarningsService.balance(TENANT, cashierProfile)).isEqualByComparingTo("5.00");
        // Products are not "services done".
        assertThat(statementRow(cashierProfile).get("servicesDone").asLong()).isZero();

        String lineId = sale.get("items").get(0).get("id").asText();
        mockMvc.perform(as(post("/api/v1/sales/{saleId}/refund", sale.get("id").asText()), TENANT, cashier, ROLE_CASHIER)
                        .header("Idempotency-Key", "pr-" + UUID.randomUUID())
                        .content("{\"lines\":[{\"saleItemId\":\"" + lineId
                                + "\",\"quantity\":4}],\"payments\":[{\"method\":\"cash\",\"amount\":20}],\"reason\":\"returned\"}"))
                .andExpect(status().is2xxSuccessful());
        // 4 of 10 came back: 2 of the 5 shillings.
        assertThat(staffEarningsService.balance(TENANT, cashierProfile)).isEqualByComparingTo("3.00");
        assertReconciled();
    }

    @Test
    void payoutsArePartialTipsFirstAndNeverMoreThanOwedUnlessAllowed() throws Exception {
        setPlan(john, "PERCENT", "40", null);
        mockMvc.perform(as(post("/api/v1/sales"), TENANT, cashier, ROLE_CASHIER)
                        .header("Idempotency-Key", "po-" + UUID.randomUUID())
                        .content(saleBodyWithTips(serviceLine(haircut, john, 1, "300"), "350",
                                "{\"staffProfileId\":\"" + john + "\",\"amount\":50}")))
                .andExpect(status().isCreated());

        String key = "pay-" + UUID.randomUUID();
        String body = "{\"amount\":100,\"method\":\"MPESA\",\"reference\":\"QK12ABC\"}";
        MvcResult first = mockMvc.perform(as(post("/api/v1/commissions/staff/" + john + "/payouts"), TENANT, owner, ROLE_OWNER)
                        .header("Idempotency-Key", key).content(body))
                .andExpect(status().isOk()).andReturn();
        JsonNode rows = objectMapper.readTree(first.getResponse().getContentAsString());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("bucket").asText()).isEqualTo("TIPS");
        assertThat(rows.get(0).get("amount").decimalValue()).isEqualByComparingTo("-50.00");
        assertThat(rows.get(1).get("amount").decimalValue()).isEqualByComparingTo("-50.00");
        assertThat(staffEarningsService.balance(TENANT, john)).isEqualByComparingTo("70.00");

        // The same request again (double tap) pays nothing more.
        mockMvc.perform(as(post("/api/v1/commissions/staff/" + john + "/payouts"), TENANT, owner, ROLE_OWNER)
                        .header("Idempotency-Key", key).content(body))
                .andExpect(status().isOk());
        assertThat(staffEarningsService.balance(TENANT, john)).isEqualByComparingTo("70.00");

        // No key, more than owed, zero: all refused.
        mockMvc.perform(as(post("/api/v1/commissions/staff/" + john + "/payouts"), TENANT, owner, ROLE_OWNER).content(body))
                .andExpect(status().isBadRequest());
        mockMvc.perform(as(post("/api/v1/commissions/staff/" + john + "/payouts"), TENANT, owner, ROLE_OWNER)
                        .header("Idempotency-Key", "pay-" + UUID.randomUUID())
                        .content("{\"amount\":100,\"method\":\"MPESA\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(as(post("/api/v1/commissions/staff/" + john + "/payouts"), TENANT, owner, ROLE_OWNER)
                        .header("Idempotency-Key", "pay-" + UUID.randomUUID())
                        .content("{\"amount\":0,\"method\":\"MPESA\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(as(post("/api/v1/commissions/staff/" + john + "/payouts"), TENANT, owner, ROLE_OWNER)
                        .header("Idempotency-Key", "pay-" + UUID.randomUUID())
                        .content("{\"amount\":10,\"method\":\"CHEQUE\"}"))
                .andExpect(status().isBadRequest());
        assertThat(staffEarningsService.balance(TENANT, john)).isEqualByComparingTo("70.00");

        // Paying ahead is allowed when asked for explicitly; the balance goes negative.
        mockMvc.perform(as(post("/api/v1/commissions/staff/" + john + "/payouts"), TENANT, owner, ROLE_OWNER)
                        .header("Idempotency-Key", "pay-" + UUID.randomUUID())
                        .content("{\"amount\":100,\"method\":\"OTHER\",\"allowOverpay\":true}"))
                .andExpect(status().isOk());
        assertThat(staffEarningsService.balance(TENANT, john)).isEqualByComparingTo("-30.00");
        JsonNode row = statementRow(john);
        assertThat(row.get("paid").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(row.get("closing").decimalValue()).isEqualByComparingTo("-30.00");
        assertReconciled();
    }

    @Test
    void aDrawerPayoutComesOutOfTheTillAndCanBeReversed() throws Exception {
        setPlan(john, "PERCENT", "40", null);
        postSale("d1-" + UUID.randomUUID(), serviceLine(haircut, john, 1, "300"), "300", 201);
        assertThat(shiftRepository.findAll().getFirst().getExpectedClosingCash()).isEqualByComparingTo("400.00");

        // A drawer payout needs the branch.
        mockMvc.perform(as(post("/api/v1/commissions/staff/" + john + "/payouts"), TENANT, owner, ROLE_OWNER)
                        .header("Idempotency-Key", "dr-" + UUID.randomUUID())
                        .content("{\"amount\":100,\"method\":\"DRAWER\"}"))
                .andExpect(status().isBadRequest());

        MvcResult paid = mockMvc.perform(as(post("/api/v1/commissions/staff/" + john + "/payouts"), TENANT, owner, ROLE_OWNER)
                        .header("Idempotency-Key", "dr-" + UUID.randomUUID())
                        .content("{\"amount\":100,\"method\":\"DRAWER\",\"branchId\":\"" + branchId + "\"}"))
                .andExpect(status().isOk()).andReturn();
        String entryId = objectMapper.readTree(paid.getResponse().getContentAsString()).get(0).get("id").asText();
        assertThat(shiftRepository.findAll().getFirst().getExpectedClosingCash()).isEqualByComparingTo("300.00");
        assertThat(staffEarningsService.balance(TENANT, john)).isEqualByComparingTo("20.00");
        assertReconciled();

        mockMvc.perform(as(post("/api/v1/commissions/payouts/" + entryId + "/reverse"), TENANT, owner, ROLE_OWNER))
                .andExpect(status().isOk());
        assertThat(shiftRepository.findAll().getFirst().getExpectedClosingCash()).isEqualByComparingTo("400.00");
        assertThat(staffEarningsService.balance(TENANT, john)).isEqualByComparingTo("120.00");
        // Once only.
        mockMvc.perform(as(post("/api/v1/commissions/payouts/" + entryId + "/reverse"), TENANT, owner, ROLE_OWNER))
                .andExpect(status().isConflict());
        assertThat(staffEarningsService.balance(TENANT, john)).isEqualByComparingTo("120.00");
        assertReconciled();
    }

    @Test
    void adjustmentsNeedAReasonAndAreRecordedOnce() throws Exception {
        String key = "adj-" + UUID.randomUUID();
        mockMvc.perform(as(post("/api/v1/commissions/staff/" + john + "/adjustments"), TENANT, owner, ROLE_OWNER)
                        .header("Idempotency-Key", key).content("{\"amount\":500,\"reason\":\"Busy Saturday bonus\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(as(post("/api/v1/commissions/staff/" + john + "/adjustments"), TENANT, owner, ROLE_OWNER)
                        .header("Idempotency-Key", key).content("{\"amount\":500,\"reason\":\"Busy Saturday bonus\"}"))
                .andExpect(status().isOk());
        assertThat(staffEarningsService.balance(TENANT, john)).isEqualByComparingTo("500.00");

        mockMvc.perform(as(post("/api/v1/commissions/staff/" + john + "/adjustments"), TENANT, owner, ROLE_OWNER)
                        .header("Idempotency-Key", "adj-" + UUID.randomUUID())
                        .content("{\"amount\":-120,\"reason\":\"Broken clipper guard\"}"))
                .andExpect(status().isOk());
        assertThat(staffEarningsService.balance(TENANT, john)).isEqualByComparingTo("380.00");

        for (String bad : List.of("{\"amount\":100}", "{\"amount\":100,\"reason\":\"  \"}",
                "{\"amount\":0,\"reason\":\"nothing\"}")) {
            mockMvc.perform(as(post("/api/v1/commissions/staff/" + john + "/adjustments"), TENANT, owner, ROLE_OWNER)
                            .header("Idempotency-Key", "adj-" + UUID.randomUUID()).content(bad))
                    .andExpect(status().isBadRequest());
        }
        JsonNode row = statementRow(john);
        assertThat(row.get("adjustments").decimalValue()).isEqualByComparingTo("380.00");
        assertReconciled();
    }

    @Test
    void earningsArePrivateToManagersAndTheStaffMemberThemself() throws Exception {
        setPlan(john, "PERCENT", "40", null);
        postSale("priv-" + UUID.randomUUID(), serviceLine(haircut, john, 1, "300"), "300", 201);
        String cashierProfile = staffProfileService.ensureProfile(TENANT, cashier.getId()).getId();

        // The cashier holds commissions.read_own only.
        mockMvc.perform(as(get("/api/v1/commissions/statement"), TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(get("/api/v1/commissions/plans"), TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(get("/api/v1/commissions/staff/" + john + "/entries"), TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(get("/api/v1/commissions/staff/" + cashierProfile + "/entries"), TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isOk());
        mockMvc.perform(as(put("/api/v1/commissions/plans/" + john), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"type\":\"PERCENT\",\"rate\":90}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(post("/api/v1/commissions/staff/" + john + "/payouts"), TENANT, cashier, ROLE_CASHIER)
                        .header("Idempotency-Key", "x-" + UUID.randomUUID()).content("{\"amount\":10,\"method\":\"OTHER\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(post("/api/v1/commissions/staff/" + john + "/adjustments"), TENANT, cashier, ROLE_CASHIER)
                        .header("Idempotency-Key", "x-" + UUID.randomUUID()).content("{\"amount\":10,\"reason\":\"mine\"}"))
                .andExpect(status().isForbidden());

        // Another business sees nothing and can change nothing.
        mockMvc.perform(as(get("/api/v1/commissions/staff/" + john + "/entries"), OTHER, otherOwner, ROLE_OWNER))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(put("/api/v1/commissions/plans/" + john), OTHER, otherOwner, ROLE_OWNER)
                        .content("{\"type\":\"PERCENT\",\"rate\":90}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(post("/api/v1/commissions/staff/" + john + "/payouts"), OTHER, otherOwner, ROLE_OWNER)
                        .header("Idempotency-Key", "x-" + UUID.randomUUID()).content("{\"amount\":10,\"method\":\"OTHER\"}"))
                .andExpect(status().isNotFound());
        MvcResult other = mockMvc.perform(as(get("/api/v1/commissions/statement"), OTHER, otherOwner, ROLE_OWNER))
                .andExpect(status().isOk()).andReturn();
        assertThat(other.getResponse().getContentAsString()).doesNotContain(john);
        assertThat(staffEarningsService.balance(TENANT, john)).isEqualByComparingTo("120.00");
    }

    @Test
    void planRulesAreValidated() throws Exception {
        for (String bad : List.of("{\"type\":\"PERCENT\",\"rate\":101}", "{\"type\":\"PERCENT\"}",
                "{\"type\":\"BONUS\",\"rate\":10}", "{\"productRate\":150}", "{\"type\":\"FIXED\",\"rate\":-1}")) {
            mockMvc.perform(as(put("/api/v1/commissions/plans/" + john), TENANT, owner, ROLE_OWNER).content(bad))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(as(put("/api/v1/commissions/plans/" + john), TENANT, owner, ROLE_OWNER)
                        .content("{\"type\":\"percent\",\"rate\":40,\"productRate\":5}"))
                .andExpect(status().isOk());
        MvcResult plans = mockMvc.perform(as(get("/api/v1/commissions/plans"), TENANT, owner, ROLE_OWNER))
                .andExpect(status().isOk()).andReturn();
        JsonNode list = objectMapper.readTree(plans.getResponse().getContentAsString());
        JsonNode mine = null;
        for (JsonNode plan : list) {
            if (john.equals(plan.get("staffProfileId").asText())) {
                mine = plan;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(mine.get("type").asText()).isEqualTo("PERCENT");
        assertThat(mine.get("rate").decimalValue()).isEqualByComparingTo("40.00");
        assertThat(mine.get("productRate").decimalValue()).isEqualByComparingTo("5.00");
        // Clearing the plan.
        mockMvc.perform(as(put("/api/v1/commissions/plans/" + john), TENANT, owner, ROLE_OWNER).content("{}"))
                .andExpect(status().isOk());
        postSale("np-" + UUID.randomUUID(), serviceLine(haircut, john, 1, "300"), "300", 201);
        assertThat(staffEarningsService.balance(TENANT, john)).isEqualByComparingTo("0.00");
    }

    // ------------------------------------------------------------------ helpers

    private void setPlan(String profileId, String type, String rate, String productRate) throws Exception {
        StringBuilder json = new StringBuilder("{");
        if (type != null) {
            json.append("\"type\":\"").append(type).append("\",\"rate\":").append(rate);
        }
        if (productRate != null) {
            json.append(json.length() > 1 ? "," : "").append("\"productRate\":").append(productRate);
        }
        json.append("}");
        mockMvc.perform(as(put("/api/v1/commissions/plans/" + profileId), TENANT, owner, ROLE_OWNER)
                        .content(json.toString()))
                .andExpect(status().isOk());
    }

    private String saleBodyWithTips(String lines, String cash, String tips) {
        return "{\"branchId\":\"" + branchId + "\",\"lines\":[" + lines + "],\"payments\":[{\"method\":\"cash\",\"amount\":"
                + cash + "}],\"tips\":[" + tips + "]}";
    }

    private JsonNode statement() throws Exception {
        MvcResult result = mockMvc.perform(as(get("/api/v1/commissions/statement"), TENANT, owner, ROLE_OWNER))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode statementRow(String profileId) throws Exception {
        for (JsonNode row : statement().get("rows")) {
            if (profileId.equals(row.get("staffProfileId").asText())) {
                return row;
            }
        }
        throw new AssertionError("No statement row for " + profileId);
    }

    /** The staff ledger and the two payable accounts must always tell the same story. */
    private void assertReconciled() throws Exception {
        JsonNode statement = statement();
        assertThat(statement.get("reconciled").asBoolean())
                .as("ledger %s vs GL %s", statement.get("ledgerTotal"), statement.get("generalLedgerTotal"))
                .isTrue();
        BigDecimal gl = ledgerBalanceQuery.creditBalance(TENANT,
                List.of(LedgerAccountCodes.COMMISSIONS_PAYABLE, LedgerAccountCodes.TIPS_PAYABLE));
        BigDecimal ledger = staffLedgerEntryRepository.findAll().stream()
                .filter(e -> TENANT.equals(e.getBusinessId()))
                .map(StaffLedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(ledger).isEqualByComparingTo(gl);
    }

    @SuppressWarnings("unused")
    private static User unused(User user) {
        return user;
    }
}
