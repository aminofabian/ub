package zelisline.ub.storefront.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import zelisline.ub.platform.security.TestAuthenticationFilter;
import zelisline.ub.storefront.WebOrderStatuses;
import zelisline.ub.storefront.domain.WebOrder;
import zelisline.ub.storefront.domain.WebOrderLine;
import zelisline.ub.storefront.repository.WebOrderLineRepository;
import zelisline.ub.storefront.repository.WebOrderRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class WebOrdersAdminIT {

    private static final String TENANT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String P_READ = "11111111-0000-0000-0000-000000000077";
    private static final String ROLE_ID = "22222222-0000-0000-0000-0000000000ff";

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

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User staff;
    private String branchId;
    private String orderId;

    @BeforeEach
    void seed() {
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
        b.setName("Web Order Shop");
        b.setSlug("web-order-shop");
        businessRepository.save(b);

        Branch br = new Branch();
        br.setBusinessId(TENANT);
        br.setName("Pickup Branch");
        br.setActive(true);
        branchId = branchRepository.save(br).getId();

        Permission p = new Permission();
        p.setId(P_READ);
        p.setPermissionKey("storefront.orders.read");
        p.setDescription("read web orders");
        permissionRepository.save(p);

        Role r = new Role();
        r.setId(ROLE_ID);
        r.setBusinessId(null);
        r.setRoleKey("web_orders_it");
        r.setName("Web Orders IT");
        r.setSystem(true);
        roleRepository.save(r);

        RolePermission rp = new RolePermission();
        rp.setId(new RolePermission.Id(ROLE_ID, P_READ));
        rolePermissionRepository.save(rp);

        staff = new User();
        staff.setBusinessId(TENANT);
        staff.setEmail("web-orders@test");
        staff.setName("Staff");
        staff.setRoleId(ROLE_ID);
        staff.setBranchId(branchId);
        staff.setStatus(UserStatus.ACTIVE);
        staff.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(staff);

        WebOrder o = new WebOrder();
        o.setBusinessId(TENANT);
        o.setCartId("cccccccc-cccc-cccc-cccc-cccccccccccc");
        o.setCatalogBranchId(branchId);
        o.setStatus(WebOrderStatuses.PENDING_PAYMENT);
        o.setCurrency("KES");
        o.setGrandTotal(new BigDecimal("42.50"));
        o.setCustomerName("Buyer");
        o.setCustomerPhone("0712345678");
        o.setCustomerEmail(null);
        o.setNotes("Gate B");
        o.setCreatedAt(Instant.parse("2026-03-01T10:00:00Z"));
        o.setUpdatedAt(Instant.parse("2026-03-01T10:00:00Z"));
        webOrderRepository.save(o);
        orderId = o.getId();

        WebOrderLine line = new WebOrderLine();
        line.setOrderId(orderId);
        line.setItemId("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        line.setItemName("Sample SKU");
        line.setVariantName(null);
        line.setQuantity(new BigDecimal("2"));
        line.setUnitPrice(new BigDecimal("21.2500"));
        line.setLineTotal(new BigDecimal("42.50"));
        line.setLineIndex(0);
        webOrderLineRepository.save(line);
    }

    @Test
    void list_requiresPermission_andReturnsRows() throws Exception {
        mockMvc.perform(get("/api/v1/web-orders").header("X-Tenant-Id", TENANT))
                .andExpect(status().isForbidden());

        mockMvc.perform(
                        get("/api/v1/web-orders")
                                .header("X-Tenant-Id", TENANT)
                                .header(TestAuthenticationFilter.HEADER_USER_ID, staff.getId())
                                .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(orderId))
                .andExpect(jsonPath("$.content[0].catalogBranchName").value("Pickup Branch"))
                .andExpect(jsonPath("$.content[0].grandTotal").value(42.5));
    }

    @Test
    void detail_returnsLines() throws Exception {
        mockMvc.perform(
                        get("/api/v1/web-orders/" + orderId)
                                .header("X-Tenant-Id", TENANT)
                                .header(TestAuthenticationFilter.HEADER_USER_ID, staff.getId())
                                .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].itemName").value("Sample SKU"))
                .andExpect(jsonPath("$.notes").value("Gate B"));
    }

    @Test
    void detail_otherTenant_returns404() throws Exception {
        mockMvc.perform(
                        get("/api/v1/web-orders/" + orderId)
                                .header("X-Tenant-Id", "ffffffff-ffff-ffff-ffff-ffffffffffff")
                                .header(TestAuthenticationFilter.HEADER_USER_ID, staff.getId())
                                .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_ID))
                .andExpect(status().isNotFound());
    }
}
