package zelisline.ub.finance.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import zelisline.ub.finance.FinanceConstants;
import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.domain.ExpenseSchedule;
import zelisline.ub.finance.repository.ExpenseRepository;
import zelisline.ub.finance.repository.ExpenseScheduleOccurrenceRepository;
import zelisline.ub.finance.repository.ExpenseScheduleRepository;
import zelisline.ub.identity.domain.Permission;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.RolePermission;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.PermissionRepository;
import zelisline.ub.identity.repository.RolePermissionRepository;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.sales.repository.ShiftRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class RecurringExpenseServiceIT {

    private static final String TENANT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb3";
    private static final String ROLE = "22222222-0000-0000-0000-0000000000ac";
    private static final String P_EW = "11111111-0000-0000-0000-000000000290";

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
    private ExpenseRepository expenseRepository;
    @Autowired
    private ExpenseScheduleRepository expenseScheduleRepository;
    @Autowired
    private ExpenseScheduleOccurrenceRepository occurrenceRepository;
    @Autowired
    private RecurringExpenseService recurringExpenseService;
    @Autowired
    private LedgerBootstrapService ledgerBootstrapService;
    @Autowired
    private zelisline.ub.finance.repository.LedgerAccountRepository ledgerAccountRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private String userId;
    private String branchId;
    private String expenseAccountId;

    @BeforeEach
    void seed() {
        occurrenceRepository.deleteAll();
        expenseScheduleRepository.deleteAll();
        expenseRepository.deleteAll();
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
        b.setName("Recurring Shop");
        b.setSlug("recurring-shop");
        b.setTimezone("Africa/Nairobi");
        businessRepository.save(b);

        Branch br = new Branch();
        br.setBusinessId(TENANT);
        br.setName("Main");
        branchRepository.save(br);
        branchId = br.getId();

        permissionRepository.save(perm(P_EW, "finance.expenses.write", "w"));
        Role role = new Role();
        role.setId(ROLE);
        role.setBusinessId(null);
        role.setRoleKey("recurring_test");
        role.setName("Recurring Test");
        role.setSystem(true);
        roleRepository.save(role);
        RolePermission rp = new RolePermission();
        rp.setId(new RolePermission.Id(ROLE, P_EW));
        rolePermissionRepository.save(rp);

        User user = new User();
        user.setBusinessId(TENANT);
        user.setEmail("recurring@test");
        user.setName("Recurring User");
        user.setRoleId(ROLE);
        user.setBranchId(branchId);
        user.setStatus(UserStatus.ACTIVE);
        user.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(user);
        userId = user.getId();

        ledgerBootstrapService.ensureStandardAccounts(TENANT);
        expenseAccountId = ledgerAccountRepository
                .findByBusinessIdAndCode(TENANT, LedgerAccountCodes.OPERATING_EXPENSES)
                .orElseThrow()
                .getId();
    }

    @Test
    void processBusinessForDate_twice_doesNotDoublePostSameOccurrence() {
        ExpenseSchedule schedule = new ExpenseSchedule();
        schedule.setBusinessId(TENANT);
        schedule.setBranchId(branchId);
        schedule.setName("Weekly rent");
        schedule.setCategoryType("fixed");
        schedule.setAmount(new BigDecimal("1000.00"));
        schedule.setPaymentMethod("bank");
        schedule.setIncludeInCashDrawer(false);
        schedule.setExpenseLedgerAccountId(expenseAccountId);
        schedule.setFrequency(FinanceConstants.EXPENSE_FREQUENCY_WEEKLY);
        schedule.setStartDate(LocalDate.of(2026, 5, 3));
        schedule.setCreatedBy(userId);
        expenseScheduleRepository.save(schedule);

        int first = recurringExpenseService.processBusinessForDate(TENANT, LocalDate.of(2026, 5, 10));
        int second = recurringExpenseService.processBusinessForDate(TENANT, LocalDate.of(2026, 5, 10));

        assertThat(first).isEqualTo(2); // 2026-05-03 and 2026-05-10
        assertThat(second).isEqualTo(0);
        assertThat(occurrenceRepository.countByScheduleId(schedule.getId())).isEqualTo(2);
        assertThat(expenseRepository.count()).isEqualTo(2);
    }

    @Test
    void monthlyDateLogic_usesMonthEndForAnchor31() {
        ExpenseSchedule schedule = new ExpenseSchedule();
        schedule.setFrequency(FinanceConstants.EXPENSE_FREQUENCY_MONTHLY);
        schedule.setStartDate(LocalDate.of(2026, 1, 31));

        LocalDate feb = RecurringExpenseService.nextDueDate(schedule, LocalDate.of(2026, 1, 31));
        LocalDate mar = RecurringExpenseService.nextDueDate(schedule, feb);
        LocalDate apr = RecurringExpenseService.nextDueDate(schedule, mar);

        assertThat(feb).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(mar).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(apr).isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(RecurringExpenseService.isDueOn(schedule, LocalDate.of(2026, 2, 28))).isTrue();
        assertThat(RecurringExpenseService.isDueOn(schedule, LocalDate.of(2026, 2, 27))).isFalse();
    }

    private static Permission perm(String id, String key, String desc) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(desc);
        return p;
    }
}

