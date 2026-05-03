package zelisline.ub.finance.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.domain.CashDrawerDailySummary;
import zelisline.ub.finance.domain.Expense;
import zelisline.ub.finance.domain.JournalEntry;
import zelisline.ub.finance.repository.CashDrawerDailySummaryRepository;
import zelisline.ub.finance.repository.ExpenseRepository;
import zelisline.ub.finance.repository.JournalEntryRepository;
import zelisline.ub.finance.repository.LedgerAccountRepository;
import zelisline.ub.identity.domain.Permission;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.RolePermission;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.PermissionRepository;
import zelisline.ub.identity.repository.RolePermissionRepository;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.api.dto.PostCloseShiftRequest;
import zelisline.ub.sales.api.dto.PostOpenShiftRequest;
import zelisline.ub.sales.domain.Refund;
import zelisline.ub.sales.domain.RefundPayment;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.domain.SalePayment;
import zelisline.ub.sales.domain.Shift;
import zelisline.ub.sales.repository.RefundPaymentRepository;
import zelisline.ub.sales.repository.RefundRepository;
import zelisline.ub.sales.repository.SalePaymentRepository;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.sales.repository.ShiftRepository;
import zelisline.ub.sales.application.ShiftService;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class CashDrawerSummaryServiceIT {

    private static final String TENANT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb4";
    private static final String ROLE = "22222222-0000-0000-0000-0000000000ad";
    private static final String P_SO = "11111111-0000-0000-0000-000000000364";
    private static final String P_SC = "11111111-0000-0000-0000-000000000365";

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
    private ShiftRepository shiftRepository;
    @Autowired
    private SaleRepository saleRepository;
    @Autowired
    private SalePaymentRepository salePaymentRepository;
    @Autowired
    private RefundRepository refundRepository;
    @Autowired
    private RefundPaymentRepository refundPaymentRepository;
    @Autowired
    private ExpenseRepository expenseRepository;
    @Autowired
    private JournalEntryRepository journalEntryRepository;
    @Autowired
    private CashDrawerDailySummaryRepository summaryRepository;
    @Autowired
    private LedgerBootstrapService ledgerBootstrapService;
    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;
    @Autowired
    private ShiftService shiftService;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private String branchId;
    private String userId;

    @BeforeEach
    void seed() {
        summaryRepository.deleteAll();
        refundPaymentRepository.deleteAll();
        refundRepository.deleteAll();
        salePaymentRepository.deleteAll();
        saleRepository.deleteAll();
        expenseRepository.deleteAll();
        journalEntryRepository.deleteAll();
        shiftRepository.deleteAll();
        ledgerAccountRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        branchRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("Drawer Summary Shop");
        b.setSlug("drawer-summary-shop");
        businessRepository.save(b);

        Branch br = new Branch();
        br.setBusinessId(TENANT);
        br.setName("Main");
        branchRepository.save(br);
        branchId = br.getId();

        permissionRepository.save(perm(P_SO, "shifts.open", "o"));
        permissionRepository.save(perm(P_SC, "shifts.close", "c"));
        Role r = new Role();
        r.setId(ROLE);
        r.setBusinessId(null);
        r.setRoleKey("drawer_summary_test");
        r.setName("Drawer Summary Test");
        r.setSystem(true);
        roleRepository.save(r);
        for (String pid : new String[] {P_SO, P_SC}) {
            RolePermission rp = new RolePermission();
            rp.setId(new RolePermission.Id(ROLE, pid));
            rolePermissionRepository.save(rp);
        }

        User user = new User();
        user.setBusinessId(TENANT);
        user.setEmail("drawer@test");
        user.setName("Drawer User");
        user.setRoleId(ROLE);
        user.setBranchId(branchId);
        user.setStatus(UserStatus.ACTIVE);
        user.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(user);
        userId = user.getId();

        ledgerBootstrapService.ensureStandardAccounts(TENANT);
    }

    @Test
    void closeShift_persistsSummarySnapshotWithExpectedFormulaComponents() {
        var opened = shiftService.openShift(TENANT, new PostOpenShiftRequest(branchId, new BigDecimal("100.00"), "open"), userId);
        Shift shift = shiftRepository.findById(opened.id()).orElseThrow();
        Instant openedAt = shift.getOpenedAt();

        Sale sale = new Sale();
        sale.setBusinessId(TENANT);
        sale.setBranchId(branchId);
        sale.setShiftId(shift.getId());
        sale.setStatus(SalesConstants.SALE_STATUS_COMPLETED);
        sale.setIdempotencyKey("sale-cash-1");
        sale.setGrandTotal(new BigDecimal("50.00"));
        sale.setSoldBy(userId);
        sale.setSoldAt(openedAt);
        saleRepository.save(sale);

        SalePayment salePayment = new SalePayment();
        salePayment.setSaleId(sale.getId());
        salePayment.setMethod("cash");
        salePayment.setAmount(new BigDecimal("50.00"));
        salePayment.setSortOrder(0);
        salePaymentRepository.save(salePayment);

        Refund refund = new Refund();
        refund.setBusinessId(TENANT);
        refund.setSaleId(sale.getId());
        refund.setIdempotencyKey("refund-cash-1");
        refund.setRefundedBy(userId);
        refund.setRefundedAt(openedAt);
        refund.setTotalRefunded(new BigDecimal("10.00"));
        refund.setStatus(SalesConstants.REFUND_STATUS_COMPLETED);
        refundRepository.save(refund);

        RefundPayment refundPayment = new RefundPayment();
        refundPayment.setRefundId(refund.getId());
        refundPayment.setMethod("cash");
        refundPayment.setAmount(new BigDecimal("10.00"));
        refundPayment.setSortOrder(0);
        refundPaymentRepository.save(refundPayment);

        JournalEntry je = new JournalEntry();
        je.setBusinessId(TENANT);
        je.setEntryDate(LocalDate.now(ZoneOffset.UTC));
        je.setSourceType("expense_test");
        je.setSourceId(sale.getId());
        journalEntryRepository.save(je);

        Expense expense = new Expense();
        expense.setBusinessId(TENANT);
        expense.setBranchId(branchId);
        expense.setExpenseDate(LocalDate.now(ZoneOffset.UTC));
        expense.setName("Drawer cleaning");
        expense.setCategoryType("variable");
        expense.setAmount(new BigDecimal("5.00"));
        expense.setPaymentMethod("cash");
        expense.setIncludeInCashDrawer(true);
        expense.setExpenseLedgerAccountId(ledgerAccountRepository.findByBusinessIdAndCode(TENANT, LedgerAccountCodes.OPERATING_EXPENSES)
                .orElseThrow()
                .getId());
        expense.setJournalEntryId(je.getId());
        expense.setCreatedBy(userId);
        expense.setCreatedAt(openedAt);
        expenseRepository.save(expense);

        shift.setExpectedClosingCash(new BigDecimal("135.00"));
        shiftRepository.save(shift);

        shiftService.closeShift(TENANT, shift.getId(), new PostCloseShiftRequest(new BigDecimal("130.00"), "close"), userId);

        CashDrawerDailySummary summary = summaryRepository.findByShiftId(shift.getId()).orElseThrow();
        assertThat(summary.getOpeningCash()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(summary.getCashSales()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(summary.getCashRefunds()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(summary.getDrawerExpenses()).isEqualByComparingTo(new BigDecimal("5.00"));
        assertThat(summary.getSupplierCashFromDrawer()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(summary.getExpectedClosingCash()).isEqualByComparingTo(new BigDecimal("135.00"));
        assertThat(summary.getCountedClosingCash()).isEqualByComparingTo(new BigDecimal("130.00"));
        assertThat(summary.getClosingVariance()).isEqualByComparingTo(new BigDecimal("-5.00"));
    }

    private static Permission perm(String id, String key, String desc) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(desc);
        return p;
    }
}

