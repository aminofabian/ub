package zelisline.ub.serviceops.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;

import zelisline.ub.identity.domain.User;
import zelisline.ub.serviceops.application.StaffEarningsService;
import zelisline.ub.serviceops.repository.ServiceHelperRoleRepository;

/**
 * The usual helpers on a service, set beforehand by the owner (an after-shave at 50 shillings, a
 * groomer at 20% of the price), and a cashier naming who did each at the till.
 */
class ServiceHelperRolesIT extends ServiceSaleTestSupport {

    private static final String ROLES = "/api/v1/service-helper-roles";

    @Autowired
    private StaffEarningsService staffEarningsService;

    @Autowired
    private ServiceHelperRoleRepository helperRoleRepository;

    private String mike;
    private String lenah;
    private String peter;

    @BeforeEach
    void threePeopleOnFortyPercent() throws Exception {
        helperRoleRepository.deleteAll();
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
    void theOwnerSetsTheRolesAndOnlyTheOwner() throws Exception {
        String afterShave = role(owner, ROLE_OWNER, "After shave", "AMOUNT", "50", 201);
        role(owner, ROLE_OWNER, "Groomer", "PERCENT", "20", 201);

        // What a helper is paid is a commission decision: not the cashier's.
        role(cashier, ROLE_CASHIER, "Wash", "AMOUNT", "30", 403);
        // One name once; a share that leaves the lead something; whole shillings; a known kind.
        role(owner, ROLE_OWNER, "After shave", "AMOUNT", "80", 409);
        role(owner, ROLE_OWNER, "Everything", "PERCENT", "100", 400);
        role(owner, ROLE_OWNER, "Odd", "AMOUNT", "10.50", 400);
        role(owner, ROLE_OWNER, "Nothing", "AMOUNT", "0", 400);
        role(owner, ROLE_OWNER, "Strange", "HOURLY", "10", 400);
        role(owner, ROLE_OWNER, " ", "AMOUNT", "10", 400);

        // The cashier reads them at the till; another shop sees none of them and cannot touch one.
        assertThat(list(TENANT, cashier, ROLE_CASHIER)).hasSize(2);
        assertThat(list(OTHER, otherOwner, ROLE_OWNER)).isEmpty();
        mockMvc.perform(as(put(ROLES + "/" + afterShave), OTHER, otherOwner, ROLE_OWNER)
                        .content(body("After shave", "AMOUNT", "1")))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(delete(ROLES + "/" + afterShave), OTHER, otherOwner, ROLE_OWNER))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(delete(ROLES + "/" + afterShave), TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isForbidden());

        mockMvc.perform(as(put(ROLES + "/" + afterShave), TENANT, owner, ROLE_OWNER)
                        .content(body("After shave", "AMOUNT", "60")))
                .andExpect(status().isOk());
        JsonNode roles = list(TENANT, owner, ROLE_OWNER);
        assertThat(roles.get(0).get("name").asText()).isEqualTo("After shave");
        assertThat(roles.get(0).get("payValue").decimalValue()).isEqualByComparingTo("60");

        mockMvc.perform(as(delete(ROLES + "/" + afterShave), TENANT, owner, ROLE_OWNER))
                .andExpect(status().isNoContent());
        assertThat(list(TENANT, owner, ROLE_OWNER)).hasSize(1);
    }

    @Test
    void aCashierNamesTheHelpersAndTheirPayComesFromTheRoles() throws Exception {
        String afterShave = role(owner, ROLE_OWNER, "After shave", "AMOUNT", "50", 201);
        String groomer = role(owner, ROLE_OWNER, "Groomer", "PERCENT", "20", 201);
        JsonNode visit = open();
        String id = visit.get("id").asText();
        String line = visit.get("lines").get(0).get("id").asText();

        // Mike leads on 40%, so he earns 120 on the 300 haircut. Lenah did the after-shave for 50
        // shillings and Peter groomed for 20% of what Mike earns: 24.
        JsonNode set = helpers(id, line, cashier, ROLE_CASHIER,
                "{\"helpers\":[" + byRole(lenah, afterShave) + "," + byRole(peter, groomer) + "]}", 200);
        JsonNode stored = set.get("lines").get(0).get("helpers");
        assertThat(stored).hasSize(2);
        assertThat(stored.get(0).get("staffProfileId").asText()).isEqualTo(lenah);
        assertThat(stored.get(0).get("amount").decimalValue()).isEqualByComparingTo("50");
        assertThat(stored.get(0).get("sharePercent").decimalValue()).isEqualByComparingTo("1.00");
        assertThat(stored.get(1).get("amount").decimalValue()).isEqualByComparingTo("24");

        // The role decides the pay, whatever else the till sends with it.
        JsonNode ignored = helpers(id, line, cashier, ROLE_CASHIER,
                "{\"helpers\":[{\"staffProfileId\":\"" + lenah + "\",\"roleId\":\"" + afterShave
                        + "\",\"amount\":290,\"sharePercent\":90}," + byRole(peter, groomer) + "]}", 200);
        assertThat(ignored.get("lines").get(0).get("helpers").get(0).get("amount").decimalValue())
                .isEqualByComparingTo("50");

        // A cashier cannot type a different amount, but may send back what is already agreed.
        helpers(id, line, cashier, ROLE_CASHIER,
                "{\"helpers\":[" + byAmount(lenah, "70") + "," + byAmount(peter, "24") + "]}", 403);
        helpers(id, line, cashier, ROLE_CASHIER,
                "{\"helpers\":[" + byAmount(lenah, "50") + "," + byAmount(peter, "24") + "]}", 200);

        // The helpers are paid out of Mike's 120, never out of the shop's part: Mike keeps 46 and
        // the shop keeps its 180 exactly as if he had worked alone.
        JsonNode preview = earnings(id);
        BigDecimal mikes = BigDecimal.ZERO;
        for (JsonNode person : preview.get("staff")) {
            String who = person.get("staffProfileId").asText();
            BigDecimal commission = person.get("commission").decimalValue();
            if (who.equals(lenah)) {
                assertThat(commission).isEqualByComparingTo("50");
            } else if (who.equals(peter)) {
                assertThat(commission).isEqualByComparingTo("24");
            } else {
                mikes = commission;
            }
        }
        assertThat(mikes).isEqualByComparingTo("46");
        assertThat(preview.get("commissionTotal").decimalValue()).isEqualByComparingTo("120");
        assertThat(preview.get("shopShare").decimalValue()).isEqualByComparingTo("180");

        // Removing a role afterwards does not change a visit that already used it.
        mockMvc.perform(as(delete(ROLES + "/" + afterShave), TENANT, owner, ROLE_OWNER))
                .andExpect(status().isNoContent());
        // A tip can go to a helper as well as to the person who led, but not to someone who was not on it.
        String stranger = createStaff(TENANT, owner, "Stranger");
        payWithTips(id, "{\"staffProfileId\":\"" + stranger + "\",\"amount\":40}", 340, 400);
        payWithTips(id, "{\"staffProfileId\":\"" + lenah + "\",\"amount\":40},{\"staffProfileId\":\"" + mike
                + "\",\"amount\":20}", 360, 200);
        assertThat(staffEarningsService.balance(TENANT, lenah)).isEqualByComparingTo("90.00");
        assertThat(staffEarningsService.balance(TENANT, peter)).isEqualByComparingTo("24.00");
        assertThat(staffEarningsService.balance(TENANT, mike)).isEqualByComparingTo(mikes.add(new BigDecimal("20")));
        assertBalanced(journalLineRepository.findAll());
    }

    @Test
    void aRoleCannotTakeTheWholeServiceOrComeFromAnotherShop() throws Exception {
        String most = role(owner, ROLE_OWNER, "Colour", "AMOUNT", "250", 201);
        String groomer = role(owner, ROLE_OWNER, "Groomer", "PERCENT", "20", 201);
        String whole = role(owner, ROLE_OWNER, "Whole job", "AMOUNT", "300", 201);
        MvcResult foreign = mockMvc.perform(as(post(ROLES), OTHER, otherOwner, ROLE_OWNER)
                        .content(body("After shave", "AMOUNT", "50")))
                .andExpect(status().isCreated()).andReturn();
        String theirs = objectMapper.readTree(foreign.getResponse().getContentAsString()).get("id").asText();
        JsonNode visit = open();
        String id = visit.get("id").asText();
        String line = visit.get("lines").get(0).get("id").asText();

        // Helpers cannot be given more than the 120 Mike earns on it.
        helpers(id, line, cashier, ROLE_CASHIER,
                "{\"helpers\":[" + byRole(lenah, most) + "," + byRole(peter, groomer) + "]}", 400);
        helpers(id, line, cashier, ROLE_CASHIER, "{\"helpers\":[" + byRole(lenah, whole) + "]}", 400);
        // Another shop's role is not a role here, and a made-up one is nothing.
        helpers(id, line, cashier, ROLE_CASHIER, "{\"helpers\":[" + byRole(lenah, theirs) + "]}", 404);
        helpers(id, line, cashier, ROLE_CASHIER, "{\"helpers\":[" + byRole(lenah, UUID.randomUUID().toString()) + "]}", 404);
        // Nobody twice, and not the person already leading it.
        helpers(id, line, cashier, ROLE_CASHIER,
                "{\"helpers\":[" + byRole(lenah, groomer) + "," + byRole(lenah, groomer) + "]}", 400);
        helpers(id, line, cashier, ROLE_CASHIER, "{\"helpers\":[" + byRole(mike, groomer) + "]}", 400);

        // Nothing was written by any of those.
        JsonNode after = helpers(id, line, cashier, ROLE_CASHIER, "{\"helpers\":[" + byRole(lenah, groomer) + "]}", 200);
        assertThat(after.get("lines").get(0).get("helpers")).hasSize(1);
        // An owner may still type an amount for someone without a role.
        JsonNode typed = helpers(id, line, owner, ROLE_OWNER,
                "{\"helpers\":[" + byRole(lenah, groomer) + "," + byAmount(peter, "45") + "]}", 200);
        assertThat(typed.get("lines").get(0).get("helpers").get(1).get("amount").decimalValue())
                .isEqualByComparingTo("45");
        assertThat(typed.get("lines").get(0).get("helpers").get(1).get("sharePercent").decimalValue())
                .isEqualByComparingTo("1.00");
    }

    private static String body(String name, String kind, String value) {
        return "{\"name\":\"" + name + "\",\"payKind\":\"" + kind + "\",\"payValue\":" + value + "}";
    }

    private static String byRole(String staff, String roleId) {
        return "{\"staffProfileId\":\"" + staff + "\",\"roleId\":\"" + roleId + "\"}";
    }

    private static String byAmount(String staff, String amount) {
        return "{\"staffProfileId\":\"" + staff + "\",\"amount\":" + amount + "}";
    }

    private String role(User user, String roleKey, String name, String kind, String value, int expected) throws Exception {
        MvcResult result = mockMvc.perform(as(post(ROLES), TENANT, user, roleKey).content(body(name, kind, value)))
                .andExpect(status().is(expected)).andReturn();
        return expected >= 400 ? null : objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private JsonNode list(String tenant, User user, String roleKey) throws Exception {
        return objectMapper.readTree(mockMvc.perform(as(get(ROLES), tenant, user, roleKey))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("roles");
    }

    /** A haircut on a visit, led by Mike. */
    private JsonNode open() throws Exception {
        MvcResult result = mockMvc.perform(as(post("/api/v1/service-tickets"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"branchId\":\"" + branchId + "\",\"clientTicketId\":\"" + UUID.randomUUID()
                                + "\",\"lines\":[{\"serviceId\":\"" + haircut + "\"}]}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode visit = objectMapper.readTree(result.getResponse().getContentAsString());
        String id = visit.get("id").asText();
        String line = visit.get("lines").get(0).get("id").asText();
        mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/lines/" + line + "/team"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"members\":[{\"staffProfileId\":\"" + mike + "\"}]}"))
                .andExpect(status().isOk());
        return visit;
    }

    private JsonNode helpers(String id, String line, User user, String roleKey, String body, int expected) throws Exception {
        MvcResult result = mockMvc.perform(
                        as(post("/api/v1/service-tickets/" + id + "/lines/" + line + "/helpers"), TENANT, user, roleKey)
                                .content(body))
                .andExpect(status().is(expected)).andReturn();
        return expected >= 400 ? null : objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode earnings(String id) throws Exception {
        return objectMapper.readTree(mockMvc.perform(as(get("/api/v1/service-tickets/" + id + "/earnings"), TENANT, owner, ROLE_OWNER))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private void payWithTips(String id, String tips, int cash, int expected) throws Exception {
        mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/pay"), TENANT, cashier, ROLE_CASHIER)
                        .header("Idempotency-Key", "pay-" + UUID.randomUUID())
                        .content("{\"payments\":[{\"method\":\"cash\",\"amount\":" + cash + "}],\"tips\":[" + tips + "]}"))
                .andExpect(status().is(expected));
    }
}
