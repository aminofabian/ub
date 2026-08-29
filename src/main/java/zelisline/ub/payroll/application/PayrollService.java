package zelisline.ub.payroll.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.payroll.api.dto.CreateSalaryAdvanceRequest;
import zelisline.ub.payroll.api.dto.CreateSalaryRequest;
import zelisline.ub.payroll.api.dto.PayAllRunRequest;
import zelisline.ub.payroll.api.dto.PayAllRunResponse;
import zelisline.ub.payroll.api.dto.PayRunRequest;
import zelisline.ub.payroll.api.dto.PayrollAdvanceLedgerRowResponse;
import zelisline.ub.payroll.api.dto.PayrollCalendarMonthResponse;
import zelisline.ub.payroll.api.dto.PayrollCalendarResponse;
import zelisline.ub.payroll.api.dto.PayrollRunRowResponse;
import zelisline.ub.payroll.api.dto.PayslipResponse;
import zelisline.ub.payroll.api.dto.SalaryAdvanceResponse;
import zelisline.ub.payroll.api.dto.SalaryResponse;
import zelisline.ub.payroll.api.dto.StaffPaySelfAdvanceRow;
import zelisline.ub.payroll.api.dto.StaffPaySelfPayslipRow;
import zelisline.ub.payroll.api.dto.StaffPaySelfResponse;
import zelisline.ub.finance.FinanceConstants;
import zelisline.ub.finance.api.dto.PostExpenseRequest;
import zelisline.ub.finance.application.ExpenseService;
import zelisline.ub.credits.domain.KenyanPhoneForms;
import zelisline.ub.payroll.application.KenyaPayrollStatutoryCalculator.StatutoryBreakdown;
import zelisline.ub.payroll.application.AdvanceRepaymentAllocator.AdvanceBalance;
import zelisline.ub.payroll.application.AdvanceRepaymentAllocator.Allocation;
import zelisline.ub.payroll.domain.AdvanceStatus;
import zelisline.ub.payroll.domain.SalaryAdvanceRepayment;
import zelisline.ub.payroll.repository.SalaryAdvanceRepaymentRepository;
import zelisline.ub.payroll.domain.EmploymentStatus;
import zelisline.ub.payroll.domain.Payslip;
import zelisline.ub.payroll.domain.Salary;
import zelisline.ub.payroll.domain.SalaryAdvance;
import zelisline.ub.payroll.domain.StaffProfile;
import zelisline.ub.payroll.repository.PayslipRepository;
import zelisline.ub.payroll.repository.SalaryAdvanceRepository;
import zelisline.ub.payroll.repository.SalaryRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Monthly payroll: salaries, advances, and payslips.
 *
 * <p>Advance repayment on payday: deduct {@code min(outstanding_total, max(0, base - other_deductions))}
 * as a pool; mark outstanding advances repaid oldest-first while the pool covers each advance
 * in full. Remaining pool can partially repay the next oldest advance.
 * {@code payslips.expense_id} links to finance when {@code postExpense} is requested.
 */
@Service
@RequiredArgsConstructor
public class PayrollService {

    private final StaffProfileService staffProfileService;
    private final SalaryRepository salaryRepository;
    private final SalaryAdvanceRepository salaryAdvanceRepository;
    private final SalaryAdvanceRepaymentRepository salaryAdvanceRepaymentRepository;
    private final PayslipRepository payslipRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final BranchRepository branchRepository;
    private final BusinessRepository businessRepository;
    private final ExpenseService expenseService;

    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    @Transactional
    public List<SalaryResponse> listSalaries(String businessId, String userId) {
        StaffProfile profile = staffProfileService.ensureProfile(businessId, userId);
        return salaryRepository
                .findByBusinessIdAndStaffProfileIdOrderByEffectiveFromDescCreatedAtDesc(businessId, profile.getId())
                .stream()
                .map(s -> toSalaryResponse(s, userId))
                .toList();
    }

    @Transactional
    public SalaryResponse addSalary(String businessId, String userId, CreateSalaryRequest body, String actorId) {
        StaffProfile profile = staffProfileService.ensureProfile(businessId, userId);
        Salary salary = new Salary();
        salary.setBusinessId(businessId);
        salary.setStaffProfileId(profile.getId());
        salary.setAmount(money(body.amount()));
        salary.setEffectiveFrom(body.effectiveFrom());
        salary.setCreatedBy(actorId);
        salaryRepository.save(salary);
        return toSalaryResponse(salary, userId);
    }

    @Transactional
    public List<SalaryAdvanceResponse> listAdvances(String businessId, String userId) {
        StaffProfile profile = staffProfileService.ensureProfile(businessId, userId);
        return salaryAdvanceRepository
                .findByBusinessIdAndStaffProfileIdOrderByAdvancedOnDescCreatedAtDesc(businessId, profile.getId())
                .stream()
                .map(a -> toAdvanceResponse(a, userId))
                .toList();
    }

    @Transactional
    public SalaryAdvanceResponse addAdvance(
            String businessId,
            String userId,
            CreateSalaryAdvanceRequest body,
            String actorId
    ) {
        StaffProfile profile = staffProfileService.ensureProfile(businessId, userId);
        SalaryAdvance advance = new SalaryAdvance();
        advance.setBusinessId(businessId);
        advance.setStaffProfileId(profile.getId());
        advance.setAmount(money(body.amount()));
        advance.setAdvancedOn(body.advancedOn());
        advance.setNote(blankToNull(body.note()));
        advance.setStatus(AdvanceStatus.OUTSTANDING);
        advance.setCreatedBy(actorId);
        salaryAdvanceRepository.save(advance);
        return toAdvanceResponse(advance, userId);
    }

    @Transactional
    public List<PayslipResponse> listPayslips(String businessId, String userId) {
        StaffProfile profile = staffProfileService.ensureProfile(businessId, userId);
        String display = displayName(profile, userId, businessId);
        return payslipRepository
                .findByBusinessIdAndStaffProfileIdOrderByPeriodYearDescPeriodMonthDesc(businessId, profile.getId())
                .stream()
                .map(p -> toPayslipResponse(p, userId, display))
                .toList();
    }

    @Transactional
    public List<PayrollRunRowResponse> previewRun(
            String businessId,
            int year,
            int month,
            String branchId,
            boolean statutory
    ) {
        validatePeriod(year, month);
        LocalDate asOf = LocalDate.of(year, month, 1).withDayOfMonth(
                LocalDate.of(year, month, 1).lengthOfMonth()
        );

        List<User> users = userRepository.pageByBusiness(businessId, Pageable.unpaged()).getContent();
        Map<String, Branch> branches = branchRepository
                .findByBusinessIdAndDeletedAtIsNullOrderByNameAsc(businessId)
                .stream()
                .collect(Collectors.toMap(Branch::getId, Function.identity(), (a, b) -> a));
        Map<String, String> roleKeys = roleRepository.findVisibleForTenant(businessId).stream()
                .collect(Collectors.toMap(Role::getId, Role::getRoleKey, (a, b) -> a));

        String branchFilter = blankToNull(branchId);
        List<PayrollRunRowResponse> rows = new ArrayList<>();
        for (User user : users) {
            if ("buyer".equalsIgnoreCase(roleKeys.getOrDefault(user.getRoleId(), ""))) {
                continue;
            }
            if (branchFilter != null && !branchFilter.equals(user.getBranchId())) {
                continue;
            }
            StaffProfile profile = staffProfileService.ensureProfile(businessId, user.getId());
            if (EmploymentStatus.TERMINATED.equals(profile.getEmploymentStatus())) {
                continue;
            }

            BigDecimal base = salaryRepository.findCurrent(businessId, profile.getId(), asOf)
                    .map(Salary::getAmount)
                    .orElse(ZERO_MONEY);

            StatutoryBreakdown statutoryBreakdown = statutory && base.signum() > 0
                    ? KenyaPayrollStatutoryCalculator.calculate(base)
                    : null;
            BigDecimal statutoryTotal = statutoryBreakdown != null
                    ? statutoryBreakdown.total()
                    : ZERO_MONEY;

            BigDecimal outstanding = outstandingTotal(businessId, profile.getId());
            BigDecimal suggestedNet = base
                    .subtract(statutoryTotal)
                    .subtract(outstanding)
                    .max(ZERO_MONEY);

            var existing = payslipRepository.findByBusinessIdAndStaffProfileIdAndPeriodYearAndPeriodMonth(
                    businessId, profile.getId(), year, month
            );

            String branchName = user.getBranchId() == null ? null
                    : branches.containsKey(user.getBranchId()) ? branches.get(user.getBranchId()).getName() : null;

            rows.add(new PayrollRunRowResponse(
                    user.getId(),
                    profile.getId(),
                    displayName(profile, user),
                    profile.getTitle(),
                    profile.getEmploymentStatus(),
                    branchName,
                    user.getBranchId(),
                    base,
                    outstanding,
                    statutoryTotal,
                    statutoryBreakdown != null ? statutoryBreakdown.paye() : ZERO_MONEY,
                    statutoryBreakdown != null ? statutoryBreakdown.nssf() : ZERO_MONEY,
                    statutoryBreakdown != null ? statutoryBreakdown.shif() : ZERO_MONEY,
                    statutoryBreakdown != null ? statutoryBreakdown.housingLevy() : ZERO_MONEY,
                    suggestedNet,
                    existing.isPresent(),
                    existing.map(Payslip::getId).orElse(null),
                    existing.map(Payslip::getPaidAt).orElse(null)
            ));
        }

        rows.sort(Comparator.comparing(PayrollRunRowResponse::displayName, String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    @Transactional
    public PayslipResponse pay(
            String businessId,
            String userId,
            PayRunRequest body,
            String actorId
    ) {
        int year = body.year();
        int month = body.month();
        validatePeriod(year, month);

        StaffProfile profile = staffProfileService.ensureProfile(businessId, userId);
        if (EmploymentStatus.TERMINATED.equals(profile.getEmploymentStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot pay a terminated employee");
        }
        if (EmploymentStatus.ON_LEAVE.equals(profile.getEmploymentStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot pay an employee on leave");
        }

        if (payslipRepository.findByBusinessIdAndStaffProfileIdAndPeriodYearAndPeriodMonth(
                businessId, profile.getId(), year, month
        ).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Payslip already exists for this period");
        }

        LocalDate asOf = LocalDate.of(year, month, 1).withDayOfMonth(
                LocalDate.of(year, month, 1).lengthOfMonth()
        );
        BigDecimal base = salaryRepository.findCurrent(businessId, profile.getId(), asOf)
                .map(Salary::getAmount)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "No salary effective for this period"
                ));

        BigDecimal other = body.otherDeductions() == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : money(body.otherDeductions());
        if (other.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "otherDeductions cannot be negative");
        }

        StatutoryBreakdown statutoryBreakdown = body.applyStatutory()
                ? KenyaPayrollStatutoryCalculator.calculate(base)
                : null;
        BigDecimal paye = statutoryBreakdown != null ? statutoryBreakdown.paye() : ZERO_MONEY;
        BigDecimal nssf = statutoryBreakdown != null ? statutoryBreakdown.nssf() : ZERO_MONEY;
        BigDecimal shif = statutoryBreakdown != null ? statutoryBreakdown.shif() : ZERO_MONEY;
        BigDecimal housing = statutoryBreakdown != null ? statutoryBreakdown.housingLevy() : ZERO_MONEY;
        BigDecimal statutoryTotal = statutoryBreakdown != null ? statutoryBreakdown.total() : ZERO_MONEY;

        BigDecimal availableForAdvances = base.subtract(statutoryTotal).subtract(other).max(ZERO_MONEY);
        List<SalaryAdvance> outstandingAdvances = salaryAdvanceRepository
                .findByBusinessIdAndStaffProfileIdAndStatusOrderByAdvancedOnAscCreatedAtAsc(
                        businessId, profile.getId(), AdvanceStatus.OUTSTANDING
                );
        List<AdvanceBalance> balances = outstandingAdvances.stream()
                .map(a -> new AdvanceBalance(a.getId(), advanceBalance(a)))
                .toList();
        List<Allocation> allocations = AdvanceRepaymentAllocator.allocate(availableForAdvances, balances);
        BigDecimal deducted = AdvanceRepaymentAllocator.totalAllocated(allocations);

        BigDecimal net = base.subtract(statutoryTotal).subtract(deducted).subtract(other).max(ZERO_MONEY);

        User user = userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(userId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        String display = displayName(profile, user);

        Payslip payslip = new Payslip();
        payslip.setBusinessId(businessId);
        payslip.setStaffProfileId(profile.getId());
        payslip.setPeriodYear(year);
        payslip.setPeriodMonth(month);
        payslip.setBaseSalary(base);
        payslip.setAdvancesDeducted(deducted);
        payslip.setOtherDeductions(other);
        payslip.setPayeDeducted(paye);
        payslip.setNssfDeducted(nssf);
        payslip.setShifDeducted(shif);
        payslip.setHousingLevyDeducted(housing);
        payslip.setNetPaid(net);
        payslip.setPaidAt(Instant.now());
        payslip.setNote(blankToNull(body.note()));
        payslip.setCreatedBy(actorId);
        payslipRepository.save(payslip);

        if (body.postExpense() && net.signum() > 0) {
            String paymentMethod = normalizePaymentMethod(body.paymentMethod());
            String expenseBranch = blankToNull(body.branchId()) != null
                    ? body.branchId().trim()
                    : user.getBranchId();
            var expense = expenseService.recordExpense(
                    businessId,
                    new PostExpenseRequest(
                            LocalDate.now(),
                            "Salary — " + display + " — " + month + "/" + year,
                            FinanceConstants.EXPENSE_CATEGORY_FIXED,
                            net,
                            paymentMethod,
                            false,
                            expenseBranch,
                            null,
                            null,
                            payslip.getPaidAt()
                    ),
                    actorId,
                    "payroll-" + payslip.getId()
            );
            payslip.setExpenseId(expense.id());
            payslip.setPaymentMethod(paymentMethod);
            payslipRepository.save(payslip);
        }

        applyAdvanceRepayments(businessId, outstandingAdvances, allocations, payslip.getId());

        return toPayslipResponse(payslip, userId, display);
    }

    @Transactional(readOnly = true)
    public List<PayrollAdvanceLedgerRowResponse> listBusinessAdvances(String businessId, String statusFilter) {
        List<SalaryAdvance> advances = statusFilter == null || statusFilter.isBlank()
                ? salaryAdvanceRepository.findByBusinessIdOrderByAdvancedOnDescCreatedAtDesc(businessId)
                : salaryAdvanceRepository.findByBusinessIdAndStatusOrderByAdvancedOnDescCreatedAtDesc(
                        businessId,
                        statusFilter.trim().toLowerCase()
                );

        Map<String, StaffProfile> profilesById = staffProfileService
                .findProfilesByBusiness(businessId)
                .stream()
                .collect(Collectors.toMap(StaffProfile::getId, Function.identity(), (a, b) -> a));

        Map<String, User> usersById = userRepository
                .pageByBusiness(businessId, Pageable.unpaged())
                .getContent()
                .stream()
                .collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));

        Map<String, Branch> branches = branchRepository
                .findByBusinessIdAndDeletedAtIsNullOrderByNameAsc(businessId)
                .stream()
                .collect(Collectors.toMap(Branch::getId, Function.identity(), (a, b) -> a));

        List<PayrollAdvanceLedgerRowResponse> rows = new ArrayList<>();
        for (SalaryAdvance advance : advances) {
            StaffProfile profile = profilesById.get(advance.getStaffProfileId());
            if (profile == null) {
                continue;
            }
            User user = usersById.get(profile.getUserId());
            if (user == null) {
                continue;
            }
            String branchName = user.getBranchId() == null ? null
                    : branches.containsKey(user.getBranchId())
                            ? branches.get(user.getBranchId()).getName()
                            : null;
            rows.add(new PayrollAdvanceLedgerRowResponse(
                    advance.getId(),
                    profile.getId(),
                    profile.getUserId(),
                    displayName(profile, user),
                    branchName,
                    advance.getAmount(),
                    advance.getAmountRepaid(),
                    advanceBalance(advance),
                    advance.getAdvancedOn(),
                    advance.getNote(),
                    advance.getStatus(),
                    advance.getRepaidInPayslipId(),
                    advance.getCreatedAt()
            ));
        }
        return rows;
    }

    @Transactional(readOnly = true)
    public List<PayslipResponse> listPeriodPayslips(String businessId, int year, int month) {
        validatePeriod(year, month);
        Map<String, StaffProfile> profilesById = staffProfileService
                .findProfilesByBusiness(businessId)
                .stream()
                .collect(Collectors.toMap(StaffProfile::getId, Function.identity(), (a, b) -> a));

        return payslipRepository
                .findByBusinessIdAndPeriodYearAndPeriodMonth(businessId, year, month)
                .stream()
                .map(payslip -> {
                    StaffProfile profile = profilesById.get(payslip.getStaffProfileId());
                    String userId = profile != null ? profile.getUserId() : null;
                    String display = profile != null
                            ? displayName(profile, userId, businessId)
                            : "Staff";
                    return toPayslipResponse(payslip, userId, display);
                })
                .sorted(Comparator.comparing(PayslipResponse::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Transactional(readOnly = true)
    public PayrollCalendarResponse calendarYear(String businessId, int year, String branchId) {
        if (year < 2000 || year > 2100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid year");
        }

        LocalDate today = LocalDate.now();
        Map<Integer, BigDecimal> netPaidByMonth = payslipRepository
                .findByBusinessIdAndPeriodYearOrderByPeriodMonthAsc(businessId, year)
                .stream()
                .collect(Collectors.groupingBy(
                        Payslip::getPeriodMonth,
                        Collectors.reducing(ZERO_MONEY, Payslip::getNetPaid, (a, b) -> money(a.add(b)))
                ));

        List<PayrollCalendarMonthResponse> months = new ArrayList<>();
        for (int month = 1; month <= 12; month++) {
            List<PayrollRunRowResponse> rows = previewRun(businessId, year, month, branchId, false);
            BigDecimal totalNetPaid = netPaidByMonth.getOrDefault(month, ZERO_MONEY);
            months.add(PayrollCalendarSummarizer.summarize(year, month, rows, totalNetPaid, today));
        }
        return new PayrollCalendarResponse(year, months);
    }

    @Transactional(readOnly = true)
    public StaffPaySelfResponse getSelfPortal(String businessId, String userId) {
        StaffProfile profile = staffProfileService.ensureProfile(businessId, userId);
        if (EmploymentStatus.TERMINATED.equals(profile.getEmploymentStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Profile is not active");
        }

        User user = userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(userId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        String display = displayName(profile, user);

        Business business = businessRepository.findById(businessId).orElse(null);
        String shopName = business != null && business.getName() != null
                ? business.getName().trim()
                : "Shop";

        LocalDate today = LocalDate.now();
        BigDecimal currentSalary = salaryRepository.findCurrent(businessId, profile.getId(), today)
                .map(Salary::getAmount)
                .orElse(ZERO_MONEY);

        BigDecimal advancesOutstanding = outstandingTotal(businessId, profile.getId());
        List<StaffPaySelfAdvanceRow> advances = salaryAdvanceRepository
                .findByBusinessIdAndStaffProfileIdOrderByAdvancedOnDescCreatedAtDesc(businessId, profile.getId())
                .stream()
                .filter(a -> AdvanceStatus.OUTSTANDING.equals(a.getStatus())
                        || advanceBalance(a).signum() > 0)
                .map(a -> new StaffPaySelfAdvanceRow(
                        a.getId(),
                        a.getAmount(),
                        a.getAmountRepaid(),
                        advanceBalance(a),
                        a.getAdvancedOn(),
                        a.getStatus(),
                        a.getNote()
                ))
                .toList();

        List<StaffPaySelfPayslipRow> payslips = payslipRepository
                .findByBusinessIdAndStaffProfileIdOrderByPeriodYearDescPeriodMonthDesc(
                        businessId, profile.getId()
                )
                .stream()
                .map(this::toSelfPayslipRow)
                .toList();

        String phone = resolveStaffPhone(profile, user);
        String sharePath = buildStaffPaySharePath(phone);

        return new StaffPaySelfResponse(
                display,
                profile.getTitle(),
                profile.getEmploymentStatus(),
                profile.getStartDate(),
                phone,
                shopName,
                currentSalary,
                advancesOutstanding,
                advances,
                payslips,
                sharePath
        );
    }

    @Transactional
    public PayAllRunResponse payAll(String businessId, PayAllRunRequest body, String actorId) {
        int year = body.year();
        int month = body.month();
        List<PayrollRunRowResponse> preview = previewRun(
                businessId, year, month, body.branchId(), Boolean.TRUE.equals(body.applyStatutory())
        );
        int paid = 0;
        int skipped = 0;
        List<PayAllRunResponse.PayAllRunFailure> failures = new ArrayList<>();

        for (PayrollRunRowResponse row : preview) {
            if (row.alreadyPaid()) {
                skipped++;
                continue;
            }
            if (EmploymentStatus.ON_LEAVE.equals(row.employmentStatus())) {
                skipped++;
                continue;
            }
            if (row.baseSalary().signum() <= 0) {
                skipped++;
                failures.add(new PayAllRunResponse.PayAllRunFailure(
                        row.userId(),
                        row.displayName(),
                        "No salary effective for this period"
                ));
                continue;
            }
            try {
                pay(businessId, row.userId(), new PayRunRequest(
                        year,
                        month,
                        null,
                        null,
                        body.applyStatutory(),
                        body.postExpense(),
                        body.paymentMethod(),
                        body.branchId()
                ), actorId);
                paid++;
            } catch (ResponseStatusException ex) {
                failures.add(new PayAllRunResponse.PayAllRunFailure(
                        row.userId(),
                        row.displayName(),
                        ex.getReason() != null ? ex.getReason() : ex.getMessage()
                ));
            }
        }

        return new PayAllRunResponse(paid, skipped, failures);
    }

    private BigDecimal outstandingTotal(String businessId, String staffProfileId) {
        return salaryAdvanceRepository
                .findByBusinessIdAndStaffProfileIdAndStatusOrderByAdvancedOnAscCreatedAtAsc(
                        businessId, staffProfileId, AdvanceStatus.OUTSTANDING
                )
                .stream()
                .map(this::advanceBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal advanceBalance(SalaryAdvance advance) {
        BigDecimal repaid = advance.getAmountRepaid() == null
                ? ZERO_MONEY
                : money(advance.getAmountRepaid());
        return money(advance.getAmount()).subtract(repaid).max(ZERO_MONEY);
    }

    private void applyAdvanceRepayments(
            String businessId,
            List<SalaryAdvance> outstandingAdvances,
            List<Allocation> allocations,
            String payslipId
    ) {
        if (allocations.isEmpty()) {
            return;
        }
        Map<String, SalaryAdvance> byId = outstandingAdvances.stream()
                .collect(Collectors.toMap(SalaryAdvance::getId, Function.identity(), (a, b) -> a));
        for (Allocation allocation : allocations) {
            SalaryAdvance advance = byId.get(allocation.advanceId());
            if (advance == null || allocation.amount().signum() <= 0) {
                continue;
            }
            SalaryAdvanceRepayment repayment = new SalaryAdvanceRepayment();
            repayment.setBusinessId(businessId);
            repayment.setAdvanceId(advance.getId());
            repayment.setPayslipId(payslipId);
            repayment.setAmount(allocation.amount());
            salaryAdvanceRepaymentRepository.save(repayment);

            BigDecimal repaid = money(advance.getAmountRepaid()).add(allocation.amount());
            advance.setAmountRepaid(repaid);
            if (repaid.compareTo(money(advance.getAmount())) >= 0) {
                advance.setStatus(AdvanceStatus.REPAID);
                advance.setRepaidInPayslipId(payslipId);
            }
            salaryAdvanceRepository.save(advance);
        }
    }

    private static void validatePeriod(int year, int month) {
        if (year < 2000 || year > 2100 || month < 1 || month > 12) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid year/month");
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private String displayName(StaffProfile profile, String userId, String businessId) {
        User user = userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(userId, businessId).orElse(null);
        return displayName(profile, user);
    }

    private static String displayName(StaffProfile profile, User user) {
        if (profile.getDisplayName() != null && !profile.getDisplayName().isBlank()) {
            return profile.getDisplayName();
        }
        return user != null ? user.getName() : "Staff";
    }

    private SalaryResponse toSalaryResponse(Salary s, String userId) {
        return new SalaryResponse(
                s.getId(),
                s.getStaffProfileId(),
                userId,
                s.getAmount(),
                s.getEffectiveFrom(),
                s.getCreatedBy(),
                s.getCreatedAt()
        );
    }

    private SalaryAdvanceResponse toAdvanceResponse(SalaryAdvance a, String userId) {
        return new SalaryAdvanceResponse(
                a.getId(),
                a.getStaffProfileId(),
                userId,
                a.getAmount(),
                a.getAmountRepaid(),
                advanceBalance(a),
                a.getAdvancedOn(),
                a.getNote(),
                a.getStatus(),
                a.getRepaidInPayslipId(),
                a.getCreatedAt()
        );
    }

    private PayslipResponse toPayslipResponse(Payslip p, String userId, String displayName) {
        return new PayslipResponse(
                p.getId(),
                p.getStaffProfileId(),
                userId,
                displayName,
                p.getPeriodYear(),
                p.getPeriodMonth(),
                p.getBaseSalary(),
                p.getAdvancesDeducted(),
                p.getOtherDeductions(),
                p.getPayeDeducted(),
                p.getNssfDeducted(),
                p.getShifDeducted(),
                p.getHousingLevyDeducted(),
                p.getNetPaid(),
                p.getPaidAt(),
                p.getNote(),
                p.getExpenseId(),
                p.getPaymentMethod()
        );
    }

    private StaffPaySelfPayslipRow toSelfPayslipRow(Payslip p) {
        return new StaffPaySelfPayslipRow(
                p.getId(),
                p.getPeriodYear(),
                p.getPeriodMonth(),
                p.getBaseSalary(),
                p.getAdvancesDeducted(),
                p.getOtherDeductions(),
                p.getPayeDeducted(),
                p.getNssfDeducted(),
                p.getShifDeducted(),
                p.getHousingLevyDeducted(),
                p.getNetPaid(),
                p.getPaidAt(),
                p.getNote(),
                p.getPaymentMethod()
        );
    }

    private static String resolveStaffPhone(StaffProfile profile, User user) {
        String raw = profile.getPhone();
        if (raw == null || raw.isBlank()) {
            raw = user.getPhone();
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String local = KenyanPhoneForms.toLocal07(raw);
        return local != null ? local : raw.trim();
    }

    private static String buildStaffPaySharePath(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String local = KenyanPhoneForms.toLocal07(phone);
        return local != null ? "/pay/" + local : "/pay/" + phone.trim();
    }

    private static String normalizePaymentMethod(String raw) {
        if (raw == null || raw.isBlank()) {
            return FinanceConstants.EXPENSE_PAY_METHOD_MPESA_MANUAL;
        }
        String normalized = raw.trim().toLowerCase();
        if (FinanceConstants.EXPENSE_PAY_METHOD_CASH.equals(normalized)
                || FinanceConstants.EXPENSE_PAY_METHOD_MPESA_MANUAL.equals(normalized)
                || FinanceConstants.EXPENSE_PAY_METHOD_BANK.equals(normalized)) {
            return normalized;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "paymentMethod must be cash, mpesa_manual, or bank");
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
