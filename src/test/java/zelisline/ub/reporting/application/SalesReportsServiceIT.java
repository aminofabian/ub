package zelisline.ub.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import zelisline.ub.identity.domain.Permission;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.RolePermission;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.PermissionRepository;
import zelisline.ub.identity.repository.RolePermissionRepository;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.reporting.api.dto.SalesRegisterResponse;
import zelisline.ub.reporting.repository.MvSalesDailyRepository;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.domain.SaleItem;
import zelisline.ub.sales.repository.SaleItemRepository;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class SalesReportsServiceIT {

    private static final String TENANT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb7";
    private static final String ROLE = "22222222-0000-0000-0000-0000000000af";
    private static final String P_SR = "11111111-0000-0000-0000-000000000101";

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
    private SaleRepository saleRepository;
    @Autowired
    private SaleItemRepository saleItemRepository;
    @Autowired
    private MvSalesDailyRepository mvRepository;
    @Autowired
    private MvSalesDailyRefresher refresher;
    @Autowired
    private SalesReportsService salesReportsService;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private String branchId;
    private String userId;

    @BeforeEach
    void seed() {
        mvRepository.deleteAll();
        saleItemRepository.deleteAll();
        saleRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        branchRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("Sales Reports Shop");
        b.setSlug("sales-reports-shop");
        businessRepository.save(b);

        Branch br = new Branch();
        br.setBusinessId(TENANT);
        br.setName("Main");
        branchRepository.save(br);
        branchId = br.getId();

        permissionRepository.save(perm(P_SR, "reports.sales.read", "r"));
        Role role = new Role();
        role.setId(ROLE);
        role.setBusinessId(null);
        role.setRoleKey("sales_reports_test");
        role.setName("Sales Reports Test");
        role.setSystem(true);
        roleRepository.save(role);
        RolePermission rp = new RolePermission();
        rp.setId(new RolePermission.Id(ROLE, P_SR));
        rolePermissionRepository.save(rp);

        User user = new User();
        user.setBusinessId(TENANT);
        user.setEmail("salesreports@test");
        user.setName("Sales Reports User");
        user.setRoleId(ROLE);
        user.setBranchId(branchId);
        user.setStatus(UserStatus.ACTIVE);
        user.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(user);
        userId = user.getId();
    }

    @Test
    void refreshThenSalesRegister_returnsRollupForPastDayAndIncludesTodayFromOltp() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate yesterday = today.minusDays(1);
        Instant yesterdayNoon = yesterday.atTime(12, 0).toInstant(ZoneOffset.UTC);
        Instant todayNoon = today.atTime(12, 0).toInstant(ZoneOffset.UTC);
        String itemA = UUID.randomUUID().toString();
        String itemB = UUID.randomUUID().toString();

        // Yesterday: 2 lines, revenue 200, cost 120, profit 80.
        seedSale("idem-y-1", yesterdayNoon, itemA,
                "2.0000", "100.0000", "200.00",
                "60.0000", "120.00", "80.00");

        // Today: 1 line, revenue 50, cost 30, profit 20 — only OLTP sees this until refresh.
        seedSale("idem-t-1", todayNoon, itemB,
                "1.0000", "50.0000", "50.00",
                "30.0000", "30.00", "20.00");

        // Refresh covers historical days; today's row in the MV is acceptable but the
        // facade always treats today as OLTP regardless.
        long rows = refresher.refresh(TENANT);
        assertThat(rows).isEqualTo(2L);

        SalesRegisterResponse register = salesReportsService.salesRegister(
                TENANT, yesterday, today, branchId);

        assertThat(register.days()).hasSize(2);
        SalesRegisterResponse.Day y = register.days().stream()
                .filter(d -> d.day().equals(yesterday))
                .findFirst()
                .orElseThrow();
        assertThat(y.revenue()).isEqualByComparingTo("200.00");
        assertThat(y.cost()).isEqualByComparingTo("120.00");
        assertThat(y.profit()).isEqualByComparingTo("80.00");

        SalesRegisterResponse.Day t = register.days().stream()
                .filter(d -> d.day().equals(today))
                .findFirst()
                .orElseThrow();
        assertThat(t.revenue()).isEqualByComparingTo("50.00");
        assertThat(t.cost()).isEqualByComparingTo("30.00");
        assertThat(t.profit()).isEqualByComparingTo("20.00");

        assertThat(register.totalRevenue()).isEqualByComparingTo("250.00");
        assertThat(register.totalCost()).isEqualByComparingTo("150.00");
        assertThat(register.totalProfit()).isEqualByComparingTo("100.00");
    }

    @Test
    void refresh_idempotent_secondRunDoesNotDuplicate() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant noon = today.atTime(12, 0).toInstant(ZoneOffset.UTC);
        seedSale("idem-2x-1", noon, UUID.randomUUID().toString(),
                "1.0000", "100.0000", "100.00",
                "60.0000", "60.00", "40.00");

        long first = refresher.refresh(TENANT);
        long second = refresher.refresh(TENANT);

        assertThat(first).isEqualTo(1L);
        assertThat(second).isEqualTo(1L);
        assertThat(mvRepository.count()).isEqualTo(1L);
    }

    private void seedSale(
            String idemKey,
            Instant soldAt,
            String itemId,
            String qty,
            String unitPrice,
            String lineTotal,
            String unitCost,
            String costTotal,
            String profit
    ) {
        Sale sale = new Sale();
        sale.setBusinessId(TENANT);
        sale.setBranchId(branchId);
        sale.setShiftId("shift-fixture");
        sale.setStatus(SalesConstants.SALE_STATUS_COMPLETED);
        sale.setIdempotencyKey(idemKey);
        sale.setGrandTotal(new BigDecimal(lineTotal));
        sale.setSoldBy(userId);
        sale.setSoldAt(soldAt);
        saleRepository.save(sale);

        SaleItem line = new SaleItem();
        line.setSaleId(sale.getId());
        line.setLineIndex(0);
        line.setItemId(itemId);
        line.setBatchId(UUID.randomUUID().toString());
        line.setQuantity(new BigDecimal(qty));
        line.setUnitPrice(new BigDecimal(unitPrice));
        line.setLineTotal(new BigDecimal(lineTotal));
        line.setUnitCost(new BigDecimal(unitCost));
        line.setCostTotal(new BigDecimal(costTotal));
        line.setProfit(new BigDecimal(profit));
        saleItemRepository.save(line);
    }

    private static Permission perm(String id, String key, String desc) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(desc);
        return p;
    }
}
