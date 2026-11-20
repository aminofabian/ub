package zelisline.ub.pharmacy.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import zelisline.ub.identity.domain.Permission;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.RolePermission;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.PermissionRepository;
import zelisline.ub.identity.repository.RolePermissionRepository;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.pharmacy.domain.DispensingRecord;
import zelisline.ub.pharmacy.domain.ItemMedicineDetails;
import zelisline.ub.pharmacy.domain.PharmacyProfile;
import zelisline.ub.pharmacy.repository.DispensingRecordRepository;
import zelisline.ub.pharmacy.repository.ItemMedicineDetailsRepository;
import zelisline.ub.pharmacy.repository.PharmacyProfileRepository;
import zelisline.ub.platform.security.TestAuthenticationFilter;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * A second shop must not see or change another shop's pharmacy data (AGENTS.md: one denial test
 * per endpoint family). Everything here belongs to tenant B; every call is made as tenant A.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class PharmacyTenantIsolationIT {

    private static final String TENANT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0033";
    private static final String TENANT_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbb0033";
    private static final String ROLE_OWNER = "22222222-0000-0000-0000-000000000001";
    private static final String BRANCH_B = "bbbbbbbb-0000-0000-0000-0000000000b1";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private BusinessRepository businessRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PermissionRepository permissionRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private RolePermissionRepository rolePermissionRepository;
    @Autowired
    private DispensingRecordRepository dispensingRecordRepository;
    @Autowired
    private PharmacyProfileRepository pharmacyProfileRepository;
    @Autowired
    private ItemMedicineDetailsRepository medicineDetailsRepository;
    @Autowired
    private InventoryBatchRepository inventoryBatchRepository;
    @Autowired
    private zelisline.ub.pharmacy.repository.PharmacyPatientRepository patientRepository;
    @Autowired
    private zelisline.ub.pharmacy.repository.PharmacyAdverseEventRepository adverseEventRepository;
    @Autowired
    private zelisline.ub.pharmacy.repository.PharmacySchemeRepository schemeRepository;
    @Autowired
    private zelisline.ub.pharmacy.repository.PharmacySchemeClaimRepository claimRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User ownerA;
    private User ownerB;

    @BeforeEach
    void seed() {
        claimRepository.deleteAll();
        schemeRepository.deleteAll();
        dispensingRecordRepository.deleteAll();
        patientRepository.deleteAll();
        adverseEventRepository.deleteAll();
        pharmacyProfileRepository.deleteAll();
        medicineDetailsRepository.deleteAll();
        inventoryBatchRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        businessRepository.deleteAll();

        insertBusiness(TENANT_A, "tenant-a-rx");
        insertBusiness(TENANT_B, "tenant-b-rx");

        Role ownerRole = new Role();
        ownerRole.setId(ROLE_OWNER);
        ownerRole.setBusinessId(null);
        ownerRole.setRoleKey("owner");
        ownerRole.setName("Owner");
        ownerRole.setSystem(true);
        roleRepository.save(ownerRole);
        String[] keys = {
            "catalog.items.read", "inventory.read", "inventory.write",
            "pharmacy.manage", "pharmacy.dispense", "pharmacy.records.read", "sales.sell",
            "pharmacy.claims.manage",
        };
        for (int i = 0; i < keys.length; i++) {
            String id = "11111111-0000-0000-0000-0000000033" + String.format("%02d", i);
            Permission p = new Permission();
            p.setId(id);
            p.setPermissionKey(keys[i]);
            p.setDescription(keys[i]);
            permissionRepository.save(p);
            RolePermission rp = new RolePermission();
            rp.setId(new RolePermission.Id(ROLE_OWNER, id));
            rolePermissionRepository.save(rp);
        }
        ownerA = userRepository.save(user("owner-a@rx", TENANT_A));
        ownerB = userRepository.save(user("owner-b@rx", TENANT_B));
    }

    @Test
    void aShopCannotOpenAnotherShopsDispensingRecord() throws Exception {
        String recordB = dispensingRecordB().getId();
        mockMvc.perform(asA(get("/api/v1/pharmacy/dispensing/records/" + recordB)))
                .andExpect(status().isNotFound());
    }

    @Test
    void theDispensingLogListsOnlyTheShopsOwnRecords() throws Exception {
        DispensingRecord recordB = dispensingRecordB();
        mockMvc.perform(asA(get("/api/v1/pharmacy/dispensing/records")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mockMvc.perform(asA(get("/api/v1/pharmacy/dispensing/records/by-sale/" + recordB.getSaleId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aShopCannotQuarantineReleaseOrDisposeOfAnotherShopsBatch() throws Exception {
        String batchB = batchB();
        for (String action : new String[] {"quarantine", "release", "dispose"}) {
            mockMvc.perform(asA(post("/api/v1/inventory/batch-safety/batches/" + batchB + "/" + action)
                            .contentType(APPLICATION_JSON)
                            .content("{\"reason\":\"not mine\"}")))
                    .andExpect(status().isNotFound());
        }
        assertEquals("active", inventoryBatchRepository.findById(batchB).orElseThrow().getStatus());
    }

    @Test
    void aRecallTraceDoesNotReachAnotherShopsLots() throws Exception {
        batchB();
        MvcResult r = mockMvc.perform(asA(get("/api/v1/inventory/batch-safety/recall").param("lot", "LOT-B-1")))
                .andExpect(status().isOk())
                .andReturn();
        assertFalse(r.getResponse().getContentAsString().contains(BRANCH_B));
    }

    @Test
    void theProfileShowsOnlyTheShopsOwnLicence() throws Exception {
        PharmacyProfile p = new PharmacyProfile();
        p.setBusinessId(TENANT_B);
        p.setPremisesLicenceNo("LICENCE-OF-B");
        p.setLicenceExpiresOn(LocalDate.now().plusYears(1));
        pharmacyProfileRepository.save(p);
        MvcResult r = mockMvc.perform(asA(get("/api/v1/pharmacy/profile")))
                .andExpect(status().isOk())
                .andReturn();
        assertFalse(r.getResponse().getContentAsString().contains("LICENCE-OF-B"));
    }

    @Test
    void anotherShopsMedicineCannotBeReadOrClassified() throws Exception {
        String itemB = UUID.randomUUID().toString();
        ItemMedicineDetails d = new ItemMedicineDetails();
        d.setItemId(itemB);
        d.setBusinessId(TENANT_B);
        medicineDetailsRepository.save(d);
        mockMvc.perform(asA(get("/api/v1/pharmacy/medicines/" + itemB)))
                .andExpect(status().isNotFound());
        MvcResult r = mockMvc.perform(asA(get("/api/v1/pharmacy/dispensing/counter-only-item-ids")))
                .andExpect(status().isOk())
                .andReturn();
        assertFalse(r.getResponse().getContentAsString().contains(itemB));
    }

    @Test
    void aShopWithMedicinesCannotLoadItsCatalogueOntoADesktopTill() throws Exception {
        ItemMedicineDetails d = new ItemMedicineDetails();
        d.setItemId(UUID.randomUUID().toString());
        d.setBusinessId(TENANT_B);
        medicineDetailsRepository.save(d);
        mockMvc.perform(get("/api/v1/desktop/sync/master-data")
                        .header("X-Tenant-Id", TENANT_B)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerB.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isConflict());
    }

    @Test
    void aShopCannotSeeAnotherShopsPatients() throws Exception {
        zelisline.ub.pharmacy.domain.PharmacyPatient p = new zelisline.ub.pharmacy.domain.PharmacyPatient();
        p.setBusinessId(TENANT_B);
        p.setName("Patient Of B");
        p.setPhone("0700000033");
        String patientB = patientRepository.save(p).getId();

        mockMvc.perform(asA(get("/api/v1/pharmacy/patients/" + patientB)))
                .andExpect(status().isNotFound());
        mockMvc.perform(asA(get("/api/v1/pharmacy/patients")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mockMvc.perform(asA(get("/api/v1/pharmacy/patients/lookup").param("phone", "0700000033")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(asA(get("/api/v1/pharmacy/follow-ups/owings/for-patient").param("patientId", patientB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void anotherShopsControlledRegisterAndNotesAreOutOfReach() throws Exception {
        String itemB = UUID.randomUUID().toString();
        ItemMedicineDetails d = new ItemMedicineDetails();
        d.setItemId(itemB);
        d.setBusinessId(TENANT_B);
        d.setDispensingClass(zelisline.ub.pharmacy.domain.DispensingClass.CONTROLLED);
        medicineDetailsRepository.save(d);
        mockMvc.perform(asA(get("/api/v1/pharmacy/controlled-register/" + itemB)))
                .andExpect(status().isNotFound());
        mockMvc.perform(asA(get("/api/v1/pharmacy/controlled-register/items")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        zelisline.ub.pharmacy.domain.PharmacyAdverseEvent e = new zelisline.ub.pharmacy.domain.PharmacyAdverseEvent();
        e.setBusinessId(TENANT_B);
        e.setOccurredOn(LocalDate.now());
        e.setProductName("Product of B");
        e.setDescription("Reaction reported to B");
        e.setSeverity("MILD");
        e.setRecordedBy(ownerB.getId());
        String eventB = adverseEventRepository.save(e).getId();
        mockMvc.perform(asA(get("/api/v1/pharmacy/compliance/adverse-events")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(asA(post("/api/v1/pharmacy/compliance/adverse-events/" + eventB + "/reported")
                        .contentType(APPLICATION_JSON)
                        .content("{}")))
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherShopsSchemesClaimsAndCentralOrdersAreOutOfReach() throws Exception {
        zelisline.ub.pharmacy.domain.PharmacyScheme scheme = new zelisline.ub.pharmacy.domain.PharmacyScheme();
        scheme.setBusinessId(TENANT_B);
        scheme.setName("Scheme Of B");
        String schemeB = schemeRepository.save(scheme).getId();
        DispensingRecord recordB = dispensingRecordB();
        zelisline.ub.pharmacy.domain.PharmacySchemeClaim claim = new zelisline.ub.pharmacy.domain.PharmacySchemeClaim();
        claim.setBusinessId(TENANT_B);
        claim.setSchemeId(schemeB);
        claim.setRecordId(recordB.getId());
        claim.setSaleId(recordB.getSaleId());
        claim.setBranchId(BRANCH_B);
        claim.setMemberNo("MEMBER-OF-B");
        claim.setAmountClaimed(new BigDecimal("100.00"));
        claim.setCreatedBy(ownerB.getId());
        String claimB = claimRepository.save(claim).getId();

        MvcResult schemes = mockMvc.perform(asA(get("/api/v1/pharmacy/schemes")))
                .andExpect(status().isOk())
                .andReturn();
        assertFalse(schemes.getResponse().getContentAsString().contains("Scheme Of B"));
        MvcResult claims = mockMvc.perform(asA(get("/api/v1/pharmacy/claims")))
                .andExpect(status().isOk())
                .andReturn();
        assertFalse(claims.getResponse().getContentAsString().contains("MEMBER-OF-B"));
        mockMvc.perform(asA(post("/api/v1/pharmacy/claims/" + claimB + "/status")
                        .contentType(APPLICATION_JSON)
                        .content("{\"status\":\"SUBMITTED\"}")))
                .andExpect(status().isNotFound());
        // A's own claim against B's scheme or B's record is refused.
        mockMvc.perform(asA(post("/api/v1/pharmacy/claims")
                        .contentType(APPLICATION_JSON)
                        .content("{\"schemeId\":\"" + schemeB + "\",\"recordId\":\"" + recordB.getId()
                                + "\",\"memberNo\":\"M\",\"amountClaimed\":10}")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(asA(post("/api/v1/pharmacy/ordering/splits/" + UUID.randomUUID() + "/transfers")))
                .andExpect(status().isNotFound());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder asA(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
        return request
                .header("X-Tenant-Id", TENANT_A)
                .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER);
    }

    private DispensingRecord dispensingRecordB() {
        DispensingRecord r = new DispensingRecord();
        r.setBusinessId(TENANT_B);
        r.setBranchId(BRANCH_B);
        r.setSaleId(UUID.randomUUID().toString());
        r.setDispensedBy(ownerB.getId());
        r.setPrescriberName("Prescriber for B");
        return dispensingRecordRepository.save(r);
    }

    private String batchB() {
        InventoryBatch batch = new InventoryBatch();
        batch.setId(UUID.randomUUID().toString());
        batch.setBusinessId(TENANT_B);
        batch.setBranchId(BRANCH_B);
        batch.setItemId(UUID.randomUUID().toString());
        batch.setBatchNumber("B-RX-1");
        batch.setLotNumber("LOT-B-1");
        batch.setSourceType("test");
        batch.setSourceId(UUID.randomUUID().toString());
        batch.setInitialQuantity(new BigDecimal("10.0000"));
        batch.setQuantityRemaining(new BigDecimal("10.0000"));
        batch.setUnitCost(new BigDecimal("1.0000"));
        batch.setReceivedAt(Instant.parse("2026-01-01T00:00:00Z"));
        batch.setStatus("active");
        return inventoryBatchRepository.save(batch).getId();
    }

    private void insertBusiness(String id, String slug) {
        Business b = new Business();
        b.setId(id);
        b.setName("B " + id.substring(0, 8));
        b.setSlug(slug);
        businessRepository.save(b);
    }

    private static User user(String email, String tenant) {
        User u = new User();
        u.setBusinessId(tenant);
        u.setEmail(email);
        u.setName(email);
        u.setRoleId(ROLE_OWNER);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        return u;
    }
}
