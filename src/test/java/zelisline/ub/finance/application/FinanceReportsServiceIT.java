package zelisline.ub.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.api.dto.BalanceSheetResponse;
import zelisline.ub.finance.api.dto.FinancePulseResponse;
import zelisline.ub.finance.api.dto.ProfitAndLossResponse;
import zelisline.ub.finance.domain.Expense;
import zelisline.ub.finance.domain.JournalEntry;
import zelisline.ub.finance.domain.JournalLine;
import zelisline.ub.finance.repository.ExpenseRepository;
import zelisline.ub.finance.repository.JournalEntryRepository;
import zelisline.ub.finance.repository.JournalLineRepository;
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
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.domain.SaleItem;
import zelisline.ub.sales.domain.Shift;
import zelisline.ub.sales.repository.SaleItemRepository;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.sales.repository.ShiftRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class FinanceReportsServiceIT {

    private static final String TENANT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb5";
    private static final String ROLE = "22222222-0000-0000-0000-0000000000ae";
    private static final String P_RR = "11111111-0000-0000-0000-000000000100";

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
    private SaleItemRepository saleItemRepository;
    @Autowired
    private JournalEntryRepository journalEntryRepository;
    @Autowired
    private JournalLineRepository journalLineRepository;
    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;
    @Autowired
    private ExpenseRepository expenseRepository;
    @Autowired
    private LedgerBootstrapService ledgerBootstrapService;
    @Autowired
    private FinanceReportsService financeReportsService;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private String branchId;
    private String userId;

    @BeforeEach
    void seed() {
        expenseRepository.deleteAll();
        saleItemRepository.deleteAll();
        saleRepository.deleteAll();
        journalLineRepository.deleteAll();
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
        b.setName("Reports Shop");
        b.setSlug("reports-shop");
        businessRepository.save(b);

        Branch br = new Branch();
        br.setBusinessId(TENANT);
        br.setName("Main");
        branchRepository.save(br);
        branchId = br.getId();

        permissionRepository.save(perm(P_RR, "finance.reports.read", "r"));
        Role r = new Role();
        r.setId(ROLE);
        r.setBusinessId(null);
        r.setRoleKey("reports_test");
        r.setName("Reports Test");
        r.setSystem(true);
        roleRepository.save(r);
        RolePermission rp = new RolePermission();
        rp.setId(new RolePermission.Id(ROLE, P_RR));
        rolePermissionRepository.save(rp);

        User user = new User();
        user.setBusinessId(TENANT);
        user.setEmail("reports@test");
        user.setName("Reports User");
        user.setRoleId(ROLE);
        user.setBranchId(branchId);
        user.setStatus(UserStatus.ACTIVE);
        user.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(user);
        userId = user.getId();

        ledgerBootstrapService.ensureStandardAccounts(TENANT);
    }

    @Test
    void pulseAndPlAndBalanceSheet_balanceOnFixturedDay() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant noon = today.atTime(12, 0).toInstant(ZoneOffset.UTC);

        // Opening inventory: Dr Inventory 100 / Cr Opening balance equity 100
        postJournal(today, "opening", List.of(
                debit(LedgerAccountCodes.INVENTORY, "100.00"),
                credit(LedgerAccountCodes.OPENING_BALANCE_EQUITY, "100.00")
        ));

        // Cash sale: revenue 100, COGS 60, profit 40.
        postJournal(today, "sale", List.of(
                debit(LedgerAccountCodes.OPERATING_CASH, "100.00"),
                credit(LedgerAccountCodes.SALES_REVENUE, "100.00")
        ));
        postJournal(today, "sale_cogs", List.of(
                debit(LedgerAccountCodes.COST_OF_GOODS_SOLD, "60.00"),
                credit(LedgerAccountCodes.INVENTORY, "60.00")
        ));
        Sale sale = new Sale();
        sale.setBusinessId(TENANT);
        sale.setBranchId(branchId);
        sale.setShiftId("shift-fixture");
        sale.setStatus(SalesConstants.SALE_STATUS_COMPLETED);
        sale.setIdempotencyKey("sale-fixture-1");
        sale.setGrandTotal(new BigDecimal("100.00"));
        sale.setSoldBy(userId);
        sale.setSoldAt(noon);
        saleRepository.save(sale);
        SaleItem line = new SaleItem();
        line.setSaleId(sale.getId());
        line.setLineIndex(0);
        line.setItemId(UUID.randomUUID().toString());
        line.setBatchId(UUID.randomUUID().toString());
        line.setQuantity(new BigDecimal("1.0000"));
        line.setUnitPrice(new BigDecimal("100.0000"));
        line.setLineTotal(new BigDecimal("100.00"));
        line.setUnitCost(new BigDecimal("60.0000"));
        line.setCostTotal(new BigDecimal("60.00"));
        line.setProfit(new BigDecimal("40.00"));
        saleItemRepository.save(line);

        // Operating expense paid in cash: 30.
        String expenseJournalId = postJournal(today, "expense", List.of(
                debit(LedgerAccountCodes.OPERATING_EXPENSES, "30.00"),
                credit(LedgerAccountCodes.OPERATING_CASH, "30.00")
        ));
        Expense expense = new Expense();
        expense.setBusinessId(TENANT);
        expense.setBranchId(branchId);
        expense.setExpenseDate(today);
        expense.setName("Pulse fixture expense");
        expense.setCategoryType("variable");
        expense.setAmount(new BigDecimal("30.00"));
        expense.setPaymentMethod("cash");
        expense.setIncludeInCashDrawer(false);
        expense.setExpenseLedgerAccountId(accountId(LedgerAccountCodes.OPERATING_EXPENSES));
        expense.setJournalEntryId(expenseJournalId);
        expense.setCreatedBy(userId);
        expenseRepository.save(expense);

        // One open shift so pulse.openShifts == 1.
        Shift shift = new Shift();
        shift.setBusinessId(TENANT);
        shift.setBranchId(branchId);
        shift.setOpenedBy(userId);
        shift.setStatus(SalesConstants.SHIFT_STATUS_OPEN);
        shift.setOpenedAt(noon);
        shift.setOpeningCash(new BigDecimal("0.00"));
        shift.setExpectedClosingCash(new BigDecimal("0.00"));
        shiftRepository.save(shift);

        // --- Pulse (today, all branches) ---
        FinancePulseResponse pulse = financeReportsService.pulse(TENANT, today, null);
        assertThat(pulse.salesCount()).isEqualTo(1L);
        assertThat(pulse.revenue()).isEqualByComparingTo("100.00");
        assertThat(pulse.cogs()).isEqualByComparingTo("60.00");
        assertThat(pulse.grossProfit()).isEqualByComparingTo("40.00");
        assertThat(pulse.grossMarginPct()).isEqualByComparingTo("40.00");
        assertThat(pulse.expensesTotal()).isEqualByComparingTo("30.00");
        assertThat(pulse.netOperating()).isEqualByComparingTo("10.00");
        assertThat(pulse.openShifts()).isEqualTo(1L);

        // --- P&L (single-day window) ---
        ProfitAndLossResponse pl = financeReportsService.profitAndLoss(TENANT, today, today, null);
        assertThat(pl.revenue()).isEqualByComparingTo("100.00");
        assertThat(pl.cogs()).isEqualByComparingTo("60.00");
        assertThat(pl.grossProfit()).isEqualByComparingTo("40.00");
        assertThat(pl.operatingExpenses()).isEqualByComparingTo("30.00");
        assertThat(pl.netOperating()).isEqualByComparingTo("10.00");
        assertThat(pl.expenseLines()).extracting(ProfitAndLossResponse.LineItem::accountCode)
                .containsExactly(LedgerAccountCodes.OPERATING_EXPENSES);

        // --- Balance sheet (as-of today) ---
        BalanceSheetResponse bs = financeReportsService.balanceSheet(TENANT, today, null);
        assertThat(bs.totalAssets()).isEqualByComparingTo("110.00");
        assertThat(bs.totalLiabilities()).isEqualByComparingTo("0.00");
        assertThat(bs.totalEquity()).isEqualByComparingTo("110.00");
        assertThat(bs.totalLiabilitiesAndEquity()).isEqualByComparingTo("110.00");
        assertThat(bs.balanced()).isTrue();
        // Equity must include the rolled-up current-period earnings line (10.00).
        BalanceSheetResponse.LineItem retained = bs.equity().stream()
                .filter(li -> "RE".equals(li.accountCode()))
                .findFirst()
                .orElseThrow();
        assertThat(retained.amount()).isEqualByComparingTo("10.00");
    }

    @Test
    void pulse_emptyDay_returnsZeros() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        FinancePulseResponse pulse = financeReportsService.pulse(TENANT, today, null);
        assertThat(pulse.salesCount()).isZero();
        assertThat(pulse.revenue()).isEqualByComparingTo("0.00");
        assertThat(pulse.cogs()).isEqualByComparingTo("0.00");
        assertThat(pulse.grossProfit()).isEqualByComparingTo("0.00");
        assertThat(pulse.grossMarginPct()).isEqualByComparingTo("0.00");
        assertThat(pulse.expensesTotal()).isEqualByComparingTo("0.00");
        assertThat(pulse.openShifts()).isZero();
    }

    @Test
    void profitAndLoss_rejectsInvertedRange() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        assertThatThrownBy(() -> financeReportsService.profitAndLoss(TENANT, today, today.minusDays(1), null))
                .isInstanceOf(ResponseStatusException.class);
    }

    private String postJournal(LocalDate day, String sourceType, List<JournalLine> lines) {
        JournalEntry je = new JournalEntry();
        je.setBusinessId(TENANT);
        je.setEntryDate(day);
        je.setSourceType(sourceType);
        je.setSourceId(UUID.randomUUID().toString());
        je.setMemo(sourceType + " fixture");
        journalEntryRepository.save(je);
        for (JournalLine line : lines) {
            line.setJournalEntryId(je.getId());
            journalLineRepository.save(line);
        }
        return je.getId();
    }

    private JournalLine debit(String code, String amount) {
        JournalLine l = new JournalLine();
        l.setLedgerAccountId(accountId(code));
        l.setDebit(new BigDecimal(amount));
        l.setCredit(BigDecimal.ZERO);
        return l;
    }

    private JournalLine credit(String code, String amount) {
        JournalLine l = new JournalLine();
        l.setLedgerAccountId(accountId(code));
        l.setDebit(BigDecimal.ZERO);
        l.setCredit(new BigDecimal(amount));
        return l;
    }

    private String accountId(String code) {
        return ledgerAccountRepository.findByBusinessIdAndCode(TENANT, code).orElseThrow().getId();
    }

    @SuppressWarnings("unused")
    private static Comparator<BalanceSheetResponse.LineItem> byBsCode() {
        return Comparator.comparing(BalanceSheetResponse.LineItem::accountCode);
    }

    private static Permission perm(String id, String key, String desc) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(desc);
        return p;
    }
}
