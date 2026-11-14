package zelisline.ub.serviceops.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;

import zelisline.ub.identity.domain.User;
import zelisline.ub.payroll.domain.StaffProfile;
import zelisline.ub.till.domain.TillDevice;
import zelisline.ub.till.repository.TillAccessRequestRepository;
import zelisline.ub.till.repository.TillDeviceRepository;

/** Team members as people who can sign in, and each one seeing only their own earnings. */
class ServiceTeamIT extends ServiceSaleTestSupport {

    private static final String ROLE_STAFF = "5a1e2222-2222-4222-8222-222222222203";

    @Autowired
    private TillDeviceRepository tillDeviceRepository;
    @Autowired
    private TillAccessRequestRepository tillAccessRequestRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private zelisline.ub.serviceops.application.StaffEarningsService staffEarningsService;
    private int callers;

    @BeforeEach
    void theServiceStaffRole() throws Exception {
        role(ROLE_STAFF, "service_staff");
        // commissions.read_own is the 14th permission the fixtures create.
        grant(ROLE_STAFF, "5a1e1111-1111-4111-8111-111111111113");
        permissionRepository.save(perm("5a1e1111-1111-4111-8111-111111111190", "users.create"));
        grant(ROLE_OWNER, "5a1e1111-1111-4111-8111-111111111190");
        mockMvc.perform(as(put("/api/v1/commissions/plans/" + john), TENANT, owner, ROLE_OWNER)
                        .content("{\"type\":\"PERCENT\",\"rate\":40}"))
                .andExpect(status().isOk());
    }

    @Test
    void aTeamMemberAddedWithAnEmailAndPinCanSignInAndIsOnTheTeam() throws Exception {
        // A name is enough. He is an account like everyone else, just one that cannot sign in yet.
        JsonNode nameOnly = add("{\"displayName\":\"Peter\"}", 201);
        assertThat(nameOnly.get("canSignIn").asBoolean()).isFalse();
        assertThat(nameOnly.get("email").isNull()).isTrue();
        User peter = userRepository.findById(nameOnly.get("userId").asText()).orElseThrow();
        assertThat(peter.getBusinessId()).isEqualTo(TENANT);
        assertThat(peter.getRoleId()).isEqualTo(ROLE_STAFF);
        assertThat(peter.getEmail()).endsWith("@team.invalid");
        assertThat(peter.getPinHash()).isNull();
        // The database wants a credential on every active account, so he holds one nobody knows.
        assertThat(peter.getPasswordHash()).isNotBlank();
        assertThat(staffProfileRepository.findById(nameOnly.get("staffProfileId").asText()).orElseThrow().getUserId())
                .isEqualTo(peter.getId());
        // With nothing stored to check against, nobody gets in as him, whatever they type.
        pinLoginAs(peter.getEmail(), "0000", 401);
        mockMvc.perform(post("/api/v1/auth/login").header("X-Tenant-Id", TENANT).header("X-Forwarded-For", "10.77.2.1")
                        .with(r -> {
                            r.setRemoteAddr("10.77.2.1");
                            return r;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + peter.getEmail() + "\",\"password\":\"anything-at-all\"}"))
                .andExpect(status().isUnauthorized());
        // The team list says so: an account is not a sign-in.
        MvcResult plans = mockMvc.perform(as(get("/api/v1/commissions/plans"), TENANT, owner, ROLE_OWNER))
                .andExpect(status().isOk()).andReturn();
        for (JsonNode plan : objectMapper.readTree(plans.getResponse().getContentAsString())) {
            if (nameOnly.get("staffProfileId").asText().equals(plan.get("staffProfileId").asText())) {
                assertThat(plan.get("canSignIn").asBoolean()).isFalse();
            }
        }
        // Given an email and PIN later, it is the same account, now able to sign in. Once only.
        MvcResult given = mockMvc.perform(as(post("/api/v1/service-team/" + nameOnly.get("staffProfileId").asText() + "/login"),
                        TENANT, owner, ROLE_OWNER).content("{\"email\":\"peter@salon.test\",\"pin\":\"7731\"}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(objectMapper.readTree(given.getResponse().getContentAsString()).get("userId").asText()).isEqualTo(peter.getId());
        User peterNow = userRepository.findById(peter.getId()).orElseThrow();
        assertThat(peterNow.getEmail()).isEqualTo("peter@salon.test");
        assertThat(peterNow.getPinHash()).isNotBlank();
        assertThat(peterNow.getPasswordHash()).isNull();
        pinLoginAs("peter@salon.test", "7731", 200);
        mockMvc.perform(as(post("/api/v1/service-team/" + nameOnly.get("staffProfileId").asText() + "/login"),
                        TENANT, owner, ROLE_OWNER).content("{\"email\":\"peter2@salon.test\",\"pin\":\"7731\"}"))
                .andExpect(status().isConflict());

        JsonNode mary = add("{\"displayName\":\"Mary Wanjiku\",\"email\":\"Mary@Salon.test\",\"pin\":\"4821\","
                + "\"phone\":\"0711000222\"}", 201);
        assertThat(mary.get("canSignIn").asBoolean()).isTrue();
        User user = userRepository.findById(mary.get("userId").asText()).orElseThrow();
        assertThat(user.getEmail()).isEqualTo("mary@salon.test");
        assertThat(user.getRoleId()).isEqualTo(ROLE_STAFF);
        assertThat(user.getBusinessId()).isEqualTo(TENANT);
        assertThat(user.getPinHash()).isNotBlank();
        StaffProfile profile = staffProfileRepository.findById(mary.get("staffProfileId").asText()).orElseThrow();
        assertThat(profile.getUserId()).isEqualTo(user.getId());
        assertThat(profile.isBookable()).isTrue();
        // She can be chosen at the till like anyone else on the team.
        MvcResult performers = mockMvc.perform(as(get("/api/v1/services/performers"), TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isOk()).andReturn();
        assertThat(performers.getResponse().getContentAsString()).contains(profile.getId());

        // Half a login is refused; so is the same email twice, and a seller adding people.
        add("{\"displayName\":\"Half\",\"email\":\"half@salon.test\"}", 400);
        add("{\"displayName\":\"Half\",\"pin\":\"1234\"}", 400);
        add("{\"displayName\":\"Twin\",\"email\":\"mary@salon.test\",\"pin\":\"1234\"}", 409);
        mockMvc.perform(as(post("/api/v1/service-team"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"displayName\":\"Sneaky\"}"))
                .andExpect(status().isForbidden());
        assertThat(userRepository.findAll()).noneMatch(u -> "half@salon.test".equals(u.getEmail()));
    }

    @Test
    void someoneAddedByNameCanBeGivenALoginAndKeepsWhatTheyEarned() throws Exception {
        postSale("k-" + UUID.randomUUID(), serviceLine(haircut, john, 1, "300"), "300", 201);

        // Another business cannot reach this person.
        mockMvc.perform(as(post("/api/v1/service-team/" + john + "/login"), OTHER, otherOwner, ROLE_OWNER)
                        .content("{\"email\":\"john@kinyozi.test\",\"pin\":\"1234\"}"))
                .andExpect(status().isNotFound());
        MvcResult result = mockMvc.perform(as(post("/api/v1/service-team/" + john + "/login"), TENANT, owner, ROLE_OWNER)
                        .content("{\"email\":\"john@kinyozi.test\",\"pin\":\"1234\"}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode linked = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(linked.get("staffProfileId").asText()).isEqualTo(john);
        User johnUser = userRepository.findById(linked.get("userId").asText()).orElseThrow();
        assertThat(staffProfileRepository.findById(john).orElseThrow().getUserId()).isEqualTo(johnUser.getId());
        // Once only.
        mockMvc.perform(as(post("/api/v1/service-team/" + john + "/login"), TENANT, owner, ROLE_OWNER)
                        .content("{\"email\":\"john2@kinyozi.test\",\"pin\":\"1234\"}"))
                .andExpect(status().isConflict());

        // Signed in, he sees the haircut he did before he had a login.
        JsonNode mine = mine(johnUser, ROLE_STAFF, 200);
        assertThat(mine.get("staffProfileId").asText()).isEqualTo(john);
        assertThat(mine.get("servicesToday").asLong()).isEqualTo(1);
        assertThat(mine.get("commissionToday").decimalValue()).isEqualByComparingTo("120.00");
        assertThat(mine.get("owed").decimalValue()).isEqualByComparingTo("120.00");
        assertThat(mine.get("entries")).hasSize(1);
        assertThat(mine.get("bookingsToday").isArray()).isTrue();
    }

    @Test
    void eachPersonSeesOnlyTheirOwnEarnings() throws Exception {
        JsonNode mary = add("{\"displayName\":\"Mary\",\"email\":\"mary@salon.test\",\"pin\":\"4821\"}", 201);
        User maryUser = userRepository.findById(mary.get("userId").asText()).orElseThrow();
        postSale("k-" + UUID.randomUUID(), serviceLine(haircut, john, 1, "300"), "300", 201);

        // John earned; Mary's page shows nothing of it.
        JsonNode hers = mine(maryUser, ROLE_STAFF, 200);
        assertThat(hers.get("owed").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(hers.get("entries")).isEmpty();
        assertThat(hers.get("servicesToday").asLong()).isZero();
        // She cannot ask for his entries, the whole team's statement, or the report.
        mockMvc.perform(as(get("/api/v1/commissions/staff/" + john + "/entries"), TENANT, maryUser, ROLE_STAFF))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(get("/api/v1/commissions/statement"), TENANT, maryUser, ROLE_STAFF))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(get("/api/v1/commissions/reports/services"), TENANT, maryUser, ROLE_STAFF))
                .andExpect(status().isForbidden());
        // Nor ring a sale or run the floor.
        mockMvc.perform(as(get("/api/v1/service-tickets").param("branchId", branchId), TENANT, maryUser, ROLE_STAFF))
                .andExpect(status().isForbidden());
        // Someone who does not serve customers has no earnings page.
        mine(cashier, ROLE_CASHIER, 404);
    }

    @Test
    void aTeamMemberSignsInWithTheirPinOnTheirOwnPhoneButACashierStillNeedsARegisteredTill() throws Exception {
        // The shop has one registered till, so PIN sign-in is limited to it for people who sell.
        TillDevice till = new TillDevice();
        till.setBusinessId(TENANT);
        till.setBranchId(branchId);
        till.setDeviceKey("counter-till-device-01");
        till.setLabel("Counter");
        till.setCashierTemplate("floor");
        till.setRegisteredBy(owner.getId());
        till.setRegisteredAt(Instant.now());
        till.setUpdatedAt(Instant.now());
        tillDeviceRepository.save(till);

        // Mary is added with no branch: service staff may work at any.
        JsonNode mary = add("{\"displayName\":\"Mary Wanjiku\",\"email\":\"mary@salon.test\",\"pin\":\"4821\"}", 201);
        assertThat(userRepository.findById(mary.get("userId").asText()).orElseThrow().getBranchId()).isNull();

        // Another business's sign-in page does not know her.
        mockMvc.perform(post("/api/v1/auth/login-pin").header("X-Tenant-Id", OTHER)
                        .header("X-Forwarded-For", "10.77.1.1")
                        .with(r -> {
                            r.setRemoteAddr("10.77.1.1");
                            return r;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"mary@salon.test\",\"pin\":\"4821\"}"))
                .andExpect(status().isUnauthorized());

        // Her own phone, which is no till: she is let in, and nobody is asked to register it.
        pinLogin("mary@salon.test", "4821", "marys-own-phone-0001", 200);
        pinLogin("mary@salon.test", "4821", null, 200);
        assertThat(tillAccessRequestRepository.count()).isZero();
        // The wrong PIN is still the wrong PIN.
        pinLogin("mary@salon.test", "0000", "marys-own-phone-0001", 401);

        // A cashier can sell, so the rule holds: refused on a phone, let in on the till.
        cashier.setPinHash(passwordEncoder.encode(TENANT + ":2468"));
        userRepository.save(cashier);
        pinLogin(cashier.getEmail(), "2468", "some-other-phone-0001", 403);
        pinLogin(cashier.getEmail(), "2468", "counter-till-device-01", 200);
    }

    @Test
    void peopleAddedBeforeEveryoneWasAnAccountAreGivenOneAndKeepWhatTheyEarned() throws Exception {
        // John is on the team from the fixtures with no account, and has earned commission.
        postSale("k-" + UUID.randomUUID(), serviceLine(haircut, john, 1, "300"), "300", 201);
        assertThat(staffProfileRepository.findById(john).orElseThrow().getUserId()).isNull();
        long usersBefore = userRepository.findByBusinessIdAndDeletedAtIsNull(TENANT).size();
        long otherBefore = userRepository.findByBusinessIdAndDeletedAtIsNull(OTHER).size();

        // A seller cannot do it.
        mockMvc.perform(as(post("/api/v1/service-team/sync-accounts"), TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isForbidden());

        JsonNode synced = sync();
        assertThat(synced.get("created").asInt()).isEqualTo(1);
        assertThat(synced.get("skipped")).isEmpty();
        StaffProfile profile = staffProfileRepository.findById(john).orElseThrow();
        User account = userRepository.findById(profile.getUserId()).orElseThrow();
        assertThat(account.getBusinessId()).isEqualTo(TENANT);
        assertThat(account.getName()).isEqualTo("John");
        assertThat(account.getEmail()).endsWith("@team.invalid");
        assertThat(profile.isIncludeInPayroll()).isTrue();
        assertThat(userRepository.findByBusinessIdAndDeletedAtIsNull(TENANT)).hasSize((int) usersBefore + 1);
        // Same person, same earnings: the profile did not change, only gained its account.
        assertThat(staffEarningsService.balance(TENANT, john)).isEqualByComparingTo("120.00");
        // He is one of the shop's users now, which is what Users and Payroll list.
        assertThat(userRepository.findByBusinessIdAndDeletedAtIsNull(TENANT)).extracting(User::getId).contains(account.getId());

        // Run again: nothing more to do. And the other business was never touched.
        assertThat(sync().get("created").asInt()).isZero();
        assertThat(userRepository.findByBusinessIdAndDeletedAtIsNull(OTHER)).hasSize((int) otherBefore);
    }

    private JsonNode sync() throws Exception {
        MvcResult result = mockMvc.perform(as(post("/api/v1/service-team/sync-accounts"), TENANT, owner, ROLE_OWNER))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void pinLoginAs(String email, String pin, int expected) throws Exception {
        pinLogin(email, pin, null, expected);
    }

    private void pinLogin(String email, String pin, String deviceKey, int expected) throws Exception {
        // Sign-in is limited to a few tries a minute per address; each call here is its own caller.
        String address = "10.77.0." + (++callers);
        var request = post("/api/v1/auth/login-pin").header("X-Tenant-Id", TENANT)
                .header("X-Forwarded-For", address)
                .with(r -> {
                    r.setRemoteAddr(address);
                    return r;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"pin\":\"" + pin + "\"}");
        if (deviceKey != null) {
            request = request.header("X-Till-Device-Id", deviceKey);
        }
        mockMvc.perform(request).andExpect(status().is(expected));
    }

    private JsonNode add(String body, int expected) throws Exception {
        MvcResult result = mockMvc.perform(as(post("/api/v1/service-team"), TENANT, owner, ROLE_OWNER).content(body))
                .andExpect(status().is(expected)).andReturn();
        return expected >= 400 ? null : objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode mine(User user, String role, int expected) throws Exception {
        MvcResult result = mockMvc.perform(as(get("/api/v1/service-team/me"), TENANT, user, role))
                .andExpect(status().is(expected)).andReturn();
        return expected >= 400 ? null : objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
