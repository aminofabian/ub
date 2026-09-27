package zelisline.ub.integrations.pickupmtaani.api;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import zelisline.ub.identity.domain.Permission;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.RolePermission;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.PermissionRepository;
import zelisline.ub.identity.repository.RolePermissionRepository;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.integrations.pickupmtaani.infrastructure.PickupMtaaniClient;
import zelisline.ub.integrations.pickupmtaani.infrastructure.PickupMtaaniClient.CreatedPackage;
import zelisline.ub.integrations.pickupmtaani.infrastructure.PickupMtaaniClient.PackageView;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.platform.security.TestAuthenticationFilter;
import zelisline.ub.storefront.WebOrderStatuses;
import zelisline.ub.storefront.domain.WebOrder;
import zelisline.ub.storefront.domain.WebOrderLine;
import zelisline.ub.storefront.domain.WebOrderShipment;
import zelisline.ub.storefront.repository.WebOrderLineRepository;
import zelisline.ub.storefront.repository.WebOrderRepository;
import zelisline.ub.storefront.repository.WebOrderShipmentRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class PickupMtaaniBookingIT {

    private static final String TENANT = "abcdefab-0000-0000-0000-0000000000a1";
    private static final String P_READ = "11111111-0000-0000-0000-0000000000a1";
    private static final String ROLE_ID = "22222222-0000-0000-0000-0000000000a1";
    private static final String API_KEY = "tenant-pum-key";
    private static final long ORIGIN = 362L;
    private static final long DEST = 454L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private BusinessRepository businessRepository;
    @Autowired
    private BranchRepository branchRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PermissionRepository permissionRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private RolePermissionRepository rolePermissionRepository;
    @Autowired
    private WebOrderRepository webOrderRepository;
    @Autowired
    private WebOrderLineRepository webOrderLineRepository;
    @Autowired
    private WebOrderShipmentRepository webOrderShipmentRepository;
    @Autowired
    private CredentialEncryptionService credentialEncryptionService;

    @MockitoBean
    @SuppressWarnings("unused")
    private PickupMtaaniClient pickupMtaaniClient;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User staff;
    private String orderId;

    @BeforeEach
    void seed() {
        webOrderShipmentRepository.deleteAll();
        webOrderLineRepository.deleteAll();
        webOrderRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        branchRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("PUM Booking Co");
        b.setSlug("pum-booking-co");
        b.setCurrency("KES");
        businessRepository.save(b);

        Branch br = new Branch();
        br.setBusinessId(TENANT);
        br.setName("Pickup Branch");
        br.setActive(true);
        String branchId = branchRepository.save(br).getId();

        String enc = credentialEncryptionService.encryptSecret(API_KEY);
        b.setSettings("""
                {"pickupMtaani":{"enabled":true,"apiKeyEnc":"%s","accountMode":"single_business",
                 "originAgentId":%d,"originAgentName":"Kahawa West","agent":true,"doorstep":true,
                 "feeMode":"pass_through","markupKes":0}}
                """.formatted(enc, ORIGIN));
        businessRepository.save(b);

        Permission p = new Permission();
        p.setId(P_READ);
        p.setPermissionKey("storefront.orders.read");
        p.setDescription("read web orders");
        permissionRepository.save(p);

        Role r = new Role();
        r.setId(ROLE_ID);
        r.setBusinessId(null);
        r.setRoleKey("pum_booking_it");
        r.setName("PUM Booking IT");
        r.setSystem(true);
        roleRepository.save(r);

        RolePermission rp = new RolePermission();
        rp.setId(new RolePermission.Id(ROLE_ID, P_READ));
        rolePermissionRepository.save(rp);

        staff = new User();
        staff.setBusinessId(TENANT);
        staff.setEmail("pum-booking@test");
        staff.setName("Staff");
        staff.setRoleId(ROLE_ID);
        staff.setBranchId(branchId);
        staff.setStatus(UserStatus.ACTIVE);
        staff.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(staff);

        WebOrder o = new WebOrder();
        o.setBusinessId(TENANT);
        o.setCartId("cccccccc-cccc-cccc-cccc-cccccccccc01");
        o.setCatalogBranchId(branchId);
        o.setStatus(WebOrderStatuses.PAID);
        o.setCurrency("KES");
        o.setGrandTotal(new BigDecimal("192.50"));
        o.setCustomerName("Buyer");
        o.setCustomerPhone("0712345678");
        o.setCreatedAt(Instant.parse("2026-03-01T10:00:00Z"));
        o.setUpdatedAt(Instant.parse("2026-03-01T10:00:00Z"));
        webOrderRepository.save(o);
        orderId = o.getId();

        WebOrderLine line = new WebOrderLine();
        line.setOrderId(orderId);
        line.setItemId("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1");
        line.setItemName("Sample SKU");
        line.setQuantity(new BigDecimal("2"));
        line.setUnitPrice(new BigDecimal("21.2500"));
        line.setLineTotal(new BigDecimal("42.50"));
        line.setLineIndex(0);
        webOrderLineRepository.save(line);

        WebOrderShipment shipment = new WebOrderShipment();
        shipment.setId("ship-it-1");
        shipment.setBusinessId(TENANT);
        shipment.setWebOrderId(orderId);
        shipment.setCarrier(WebOrderShipment.CARRIER_PICKUP_MTAANI);
        shipment.setMode("agent");
        shipment.setOriginAgentId(ORIGIN);
        shipment.setDestinationAgentId(DEST);
        shipment.setDestinationLabel("Westlands Agent");
        shipment.setQuotedFeeKes(new BigDecimal("150.00"));
        shipment.setShopperFeeKes(new BigDecimal("150.00"));
        shipment.setFeeMode("pass_through");
        shipment.setPackageValueKes(42);
        shipment.setBookStatus(WebOrderShipment.BOOK_PENDING);
        webOrderShipmentRepository.save(shipment);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder auth(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder rb) {
        return rb.header("X-Tenant-Id", TENANT)
                .header(TestAuthenticationFilter.HEADER_USER_ID, staff.getId())
                .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_ID);
    }

    @Test
    void detail_includesShipmentSummary() throws Exception {
        mockMvc.perform(auth(get("/api/v1/web-orders/" + orderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shipment.carrier").value("pickup_mtaani"))
                .andExpect(jsonPath("$.shipment.mode").value("agent"))
                .andExpect(jsonPath("$.shipment.destinationLabel").value("Westlands Agent"))
                .andExpect(jsonPath("$.shipment.shopperFeeKes").value(150.00))
                .andExpect(jsonPath("$.shipment.bookStatus").value("pending"));
    }

    @Test
    void book_paidOrder_returnsBookedShipment() throws Exception {
        when(pickupMtaaniClient.createAgentPackage(eq(API_KEY), eq(ORIGIN), eq(DEST), eq("Buyer"),
                eq("+254712345678"), anyString(), eq(42)))
                .thenReturn(new CreatedPackage(99L, "PUM-AG-PHL-1", "PMT-PHL-1", "Not Paid", "{}"));
        when(pickupMtaaniClient.getAgentPackage(API_KEY, 99L)).thenReturn(new PackageView(
                "request", "PUM-AG-PHL-1", "PMT-PHL-1", "Not Paid", null, "Parcel created", "{}"));

        mockMvc.perform(auth(post("/api/v1/web-orders/" + orderId + "/shipments/pickup-mtaani")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shipment.bookStatus").value("booked"))
                .andExpect(jsonPath("$.shipment.receiptNo").value("PMT-PHL-1"))
                .andExpect(jsonPath("$.shipment.upstreamState").value("request"))
                .andExpect(jsonPath("$.shipment.paymentStatus").value("Not Paid"));
    }

    @Test
    void book_pendingOrder_conflict() throws Exception {
        WebOrder o = webOrderRepository.findById(orderId).orElseThrow();
        o.setStatus(WebOrderStatuses.PENDING_PAYMENT);
        webOrderRepository.save(o);

        mockMvc.perform(auth(post("/api/v1/web-orders/" + orderId + "/shipments/pickup-mtaani")))
                .andExpect(status().isConflict());
    }

    @Test
    void cancel_whileRequest_marksCancelled() throws Exception {
        WebOrderShipment s = webOrderShipmentRepository.findByWebOrderId(orderId).orElseThrow();
        s.setBookStatus(WebOrderShipment.BOOK_BOOKED);
        s.setUpstreamPackageId(99L);
        s.setUpstreamState("request");
        webOrderShipmentRepository.save(s);

        mockMvc.perform(auth(post("/api/v1/web-orders/" + orderId + "/shipments/pickup-mtaani/cancel")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shipment.bookStatus").value("cancelled"));
    }

    @Test
    void cancel_afterMoved_conflict() throws Exception {
        WebOrderShipment s = webOrderShipmentRepository.findByWebOrderId(orderId).orElseThrow();
        s.setBookStatus(WebOrderShipment.BOOK_BOOKED);
        s.setUpstreamPackageId(99L);
        s.setUpstreamState("in_transit");
        webOrderShipmentRepository.save(s);

        mockMvc.perform(auth(post("/api/v1/web-orders/" + orderId + "/shipments/pickup-mtaani/cancel")))
                .andExpect(status().isConflict());
    }
}
