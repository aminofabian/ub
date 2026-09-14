package zelisline.ub.payroll.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.DataIntegrityViolationException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.payroll.api.dto.CreateSalaryAdvanceRequest;
import zelisline.ub.payroll.api.dto.CreateSalaryRequest;
import zelisline.ub.payroll.api.dto.PatchSalaryAdvanceRequest;
import zelisline.ub.payroll.api.dto.PayAllRunRequest;
import zelisline.ub.payroll.api.dto.PayAllRunResponse;
import zelisline.ub.payroll.api.dto.PayRunRequest;
import zelisline.ub.payroll.api.dto.PayrollAdvanceLedgerRowResponse;
import zelisline.ub.payroll.api.dto.PayrollArrearPeriodResponse;
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
import zelisline.ub.payroll.domain.JoinPayMode;
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
    private final ObjectMapper objectMapper;
    /** Proxied self-reference so {@link #payAll} pays each person in its own transaction. */
    private final ObjectProvider<PayrollService> self;

    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final int MAX_ARREAR_MONTHS = 12;

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
        BigDecimal amount = money(body.amount());
        LocalDate effectiveFrom = body.effectiveFrom();

        List<Salary> history = salaryRepository
                .findByBusinessIdAndStaffProfileIdOrderByEffectiveFromDescCreatedAtDesc(
                        businessId, profile.getId());

        // First salary must cover the join month: if they joined on the 15th, do not let
        // "effective from" slip to next month (which would skip the join month entirely).
        if (history.isEmpty() && profile.getStartDate() != null
                && effectiveFrom.isAfter(profile.getStartDate())) {
            effectiveFrom = profile.getStartDate();
        }
        // Keep join date and first salary aligned when start date was never set.
        if (profile.getStartDate() == null) {
            profile.setStartDate(effectiveFrom);
            staffProfileService.save(profile);
        }

        if (!history.isEmpty()) {
            Salary latest = history.get(0);
            // Same amount as the latest row → correct that row's effective date (do not append a duplicate).
            if (latest.getAmount().compareTo(amount) == 0) {
                for (Salary other : history) {
                    if (!other.getId().equals(latest.getId())
                            && other.getEffectiveFrom().equals(effectiveFrom)) {
                        salaryRepository.delete(other);
                    }
                }
                latest.setEffectiveFrom(effectiveFrom);
                salaryRepository.save(latest);
                return toSalaryResponse(latest, userId);
            }
            // Same effective date, different amount → amend that history row in place.
            for (Salary existing : history) {
                if (existing.getEffectiveFrom().equals(effectiveFrom)) {
                    existing.setAmount(amount);
                    salaryRepository.save(existing);
                    return toSalaryResponse(existing, userId);
                }
            }
        }

        Salary salary = new Salary();
        salary.setBusinessId(businessId);
        salary.setStaffProfileId(profile.getId());
        salary.setAmount(amount);
        salary.setEffectiveFrom(effectiveFrom);
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
        BigDecimal total = money(body.amount());
        BigDecimal repaid = body.amountRepaid() == null
                ? ZERO_MONEY
                : money(body.amountRepaid());
        if (repaid.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amountRepaid cannot be negative");
        }
        if (repaid.compareTo(total) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amountRepaid cannot exceed advance amount");
        }
        SalaryAdvance advance = new SalaryAdvance();
        advance.setBusinessId(businessId);
        advance.setStaffProfileId(profile.getId());
        advance.setAmount(total);
        advance.setAmountRepaid(repaid);
        advance.setAdvancedOn(body.advancedOn());
        advance.setNote(blankToNull(body.note()));
        String mode = AdvanceRepaymentPlanner.normalizeMode(body.repaymentMode());
        AdvanceRepaymentPlanner.validateValue(mode, body.repaymentValue());
        advance.setRepaymentMode(mode);
        advance.setRepaymentValue(body.repaymentValue());
        advance.setStatus(repaid.compareTo(total) >= 0 ? AdvanceStatus.REPAID : AdvanceStatus.OUTSTANDING);
        advance.setCreatedBy(actorId);
        salaryAdvanceRepository.save(advance);
        return toAdvanceResponse(advance, userId);
    }

    @Transactional
    public SalaryAdvanceResponse patchAdvance(
            String businessId,
            String userId,
            String advanceId,
            PatchSalaryAdvanceRequest body
    ) {
        StaffProfile profile = staffProfileService.ensureProfile(businessId, userId);
        SalaryAdvance advance = salaryAdvanceRepository.findById(advanceId)
                .filter(a -> businessId.equals(a.getBusinessId())
                        && profile.getId().equals(a.getStaffProfileId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Advance not found"));

        if (body.repaymentMode() != null && !body.repaymentMode().isBlank()) {
            String mode = AdvanceRepaymentPlanner.normalizeMode(body.repaymentMode());
            BigDecimal value = body.repaymentValue() != null
                    ? body.repaymentValue()
                    : advance.getRepaymentValue();
            AdvanceRepaymentPlanner.validateValue(mode, value);
            advance.setRepaymentMode(mode);
            advance.setRepaymentValue(value);
        } else if (body.repaymentValue() != null) {
            AdvanceRepaymentPlanner.validateValue(advance.getRepaymentMode(), body.repaymentValue());
            advance.setRepaymentValue(body.repaymentValue());
        }
        if (body.note() != null) {
            advance.setNote(blankToNull(body.note()));
        }
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
        LocalDate asOf = PayrollPeriod.asOf(year, month);
        LocalDate today = LocalDate.now();
        boolean salaryReleased = PayrollPeriod.isReleased(year, month, today);

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
            UserStatus userStatus = user.statusAsEnum();
            if (userStatus == UserStatus.INVITED || userStatus == UserStatus.SUSPENDED) {
                // Never-joined or deactivated accounts must not appear in runs.
                continue;
            }
            if (branchFilter != null && !branchFilter.equals(user.getBranchId())) {
                continue;
            }
            StaffProfile profile = staffProfileService.ensureProfile(businessId, user.getId());
            if (EmploymentStatus.TERMINATED.equals(profile.getEmploymentStatus())) {
                continue;
            }
            if (!profile.isIncludeInPayroll()) {
                continue;
            }

            var currentSalary = salaryRepository.findCurrent(businessId, profile.getId(), asOf);
            BigDecimal monthly = currentSalary.map(Salary::getAmount).map(PayrollService::money).orElse(ZERO_MONEY);
            LocalDate salaryEffectiveFrom = currentSalary.map(Salary::getEffectiveFrom).orElse(null);
            LocalDate joinDate = SalaryProration.resolveJoinDate(profile.getStartDate(), salaryEffectiveFrom);
            String joinPayMode = resolveJoinPayMode(profile);
            SalaryProration.Result proration = SalaryProration.apply(
                    monthly, year, month, joinDate, joinPayMode);
            if (!salaryReleased) {
                proration = proration.locked();
            }
            BigDecimal base = proration.payableAmount();

            StatutoryBreakdown statutoryBreakdown = statutory && base.signum() > 0
                    ? KenyaPayrollStatutoryCalculator.calculate(base)
                    : null;
            BigDecimal statutoryTotal = statutoryBreakdown != null
                    ? statutoryBreakdown.total()
                    : ZERO_MONEY;

            BigDecimal outstanding = outstandingTotal(businessId, profile.getId());

            List<PayrollArrearPeriodResponse> arrearPeriods = findArrearPeriods(
                    businessId,
                    profile.getId(),
                    profile.getStartDate(),
                    joinPayMode,
                    year,
                    month,
                    statutory
            );
            BigDecimal arrearsBaseTotal = arrearPeriods.stream()
                    .map(PayrollArrearPeriodResponse::baseSalary)
                    .reduce(ZERO_MONEY, BigDecimal::add);
            BigDecimal arrearsStatutoryTotal = arrearPeriods.stream()
                    .map(PayrollArrearPeriodResponse::statutoryTotal)
                    .reduce(ZERO_MONEY, BigDecimal::add);

            BigDecimal combinedBase = base.add(arrearsBaseTotal);
            BigDecimal combinedStatutory = statutoryTotal.add(arrearsStatutoryTotal);
            // Arrears are disbursed in full as their own payslips, so advances can only
            // be recovered from the current month's pay — keep this in sync with pay().
            BigDecimal availableForAdvances = base.subtract(statutoryTotal).max(ZERO_MONEY);
            BigDecimal scheduled = allocatedAdvanceDeduction(
                    businessId, profile.getId(), availableForAdvances
            );
            BigDecimal suggestedNet = combinedBase
                    .subtract(combinedStatutory)
                    .subtract(scheduled)
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
                    proration.monthlyAmount(),
                    proration.prorationFactor(),
                    joinPayMode,
                    salaryReleased,
                    profile.getStartDate(),
                    salaryEffectiveFrom,
                    arrearsBaseTotal,
                    arrearPeriods,
                    outstanding,
                    statutoryTotal,
                    statutoryBreakdown != null ? statutoryBreakdown.paye() : ZERO_MONEY,
                    statutoryBreakdown != null ? statutoryBreakdown.nssf() : ZERO_MONEY,
                    statutoryBreakdown != null ? statutoryBreakdown.shif() : ZERO_MONEY,
                    statutoryBreakdown != null ? statutoryBreakdown.housingLevy() : ZERO_MONEY,
                    arrearsStatutoryTotal,
                    scheduled,
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
        if (!profile.isIncludeInPayroll()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This person is not included in payroll");
        }

        if (payslipRepository.findByBusinessIdAndStaffProfileIdAndPeriodYearAndPeriodMonth(
                businessId, profile.getId(), year, month
        ).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Payslip already exists for this period");
        }

        if (!PayrollPeriod.isReleased(year, month, LocalDate.now())) {
            // Fail fast, before any arrear payslips are touched.
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Salaries for this month unlock on the 25th — pay is not available yet"
            );
        }

        boolean includeArrears = body.includeArrears() == null || Boolean.TRUE.equals(body.includeArrears());
        boolean applyStatutory = Boolean.TRUE.equals(body.applyStatutory());
        String joinPayMode = Boolean.TRUE.equals(body.skipProration())
                ? JoinPayMode.FULL
                : resolveJoinPayMode(profile);
        List<PayrollArrearPeriodResponse> arrearPeriods = includeArrears
                ? findArrearPeriods(
                        businessId,
                        profile.getId(),
                        profile.getStartDate(),
                        joinPayMode,
                        year,
                        month,
                        applyStatutory)
                : List.of();

        User user = userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(userId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        UserStatus userStatus = user.statusAsEnum();
        if (userStatus == UserStatus.INVITED || userStatus == UserStatus.SUSPENDED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This person's account is not active — reactivate them before paying");
        }
        String display = displayName(profile, user);

        BigDecimal other = body.otherDeductions() == null
                ? ZERO_MONEY
                : money(body.otherDeductions());
        if (other.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "otherDeductions cannot be negative");
        }

        for (PayrollArrearPeriodResponse arrear : arrearPeriods) {
            persistArrearPayslip(
                    businessId,
                    profile.getId(),
                    user,
                    display,
                    arrear,
                    body.note(),
                    actorId,
                    false,
                    null,
                    null
            );
        }

        LocalDate asOf = PayrollPeriod.asOf(year, month);
        Salary salary = salaryRepository.findCurrent(businessId, profile.getId(), asOf)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "No salary effective for this period"
                ));
        LocalDate joinDate = SalaryProration.resolveJoinDate(profile.getStartDate(), salary.getEffectiveFrom());
        SalaryProration.Result proration = SalaryProration.apply(
                money(salary.getAmount()), year, month, joinDate, joinPayMode);
        BigDecimal base = proration.payableAmount();
        if (base.signum() <= 0) {
            String reason = proration.monthlyAmount().signum() > 0
                    && JoinPayMode.DEFERRED.equals(proration.joinPayMode())
                    ? "Join-month pay is set to 'no salary until next payroll' — this month stays at zero"
                    : "No payable salary for this period";
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
        }

        StatutoryBreakdown statutoryBreakdown = applyStatutory
                ? KenyaPayrollStatutoryCalculator.calculate(base)
                : null;
        BigDecimal paye = statutoryBreakdown != null ? statutoryBreakdown.paye() : ZERO_MONEY;
        BigDecimal nssf = statutoryBreakdown != null ? statutoryBreakdown.nssf() : ZERO_MONEY;
        BigDecimal shif = statutoryBreakdown != null ? statutoryBreakdown.shif() : ZERO_MONEY;
        BigDecimal housing = statutoryBreakdown != null ? statutoryBreakdown.housingLevy() : ZERO_MONEY;
        BigDecimal statutoryTotal = statutoryBreakdown != null ? statutoryBreakdown.total() : ZERO_MONEY;

        BigDecimal arrearsBaseTotal = arrearPeriods.stream()
                .map(PayrollArrearPeriodResponse::baseSalary)
                .reduce(ZERO_MONEY, BigDecimal::add);
        BigDecimal arrearsStatutoryTotal = arrearPeriods.stream()
                .map(PayrollArrearPeriodResponse::statutoryTotal)
                .reduce(ZERO_MONEY, BigDecimal::add);
        BigDecimal arrearNetTotal = arrearPeriods.stream()
                .map(PayrollArrearPeriodResponse::netBeforeAdvances)
                .reduce(ZERO_MONEY, BigDecimal::add);

        BigDecimal combinedBase = base.add(arrearsBaseTotal);
        BigDecimal combinedStatutory = statutoryTotal.add(arrearsStatutoryTotal);
        // Arrear payslips are disbursed in full as separate payslips, so advances can only
        // be recovered from the current month's pay. Sizing the pool from combinedBase would
        // reduce advance balances without actually withholding that money.
        BigDecimal availableForAdvances = base.subtract(statutoryTotal).subtract(other).max(ZERO_MONEY);
        if (body.advancesToDeduct() != null) {
            BigDecimal cap = money(body.advancesToDeduct());
            if (cap.signum() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "advancesToDeduct cannot be negative");
            }
            availableForAdvances = availableForAdvances.min(cap);
        }
        final boolean includeManualAdvances;
        if (body.advancesToDeduct() != null) {
            BigDecimal requested = money(body.advancesToDeduct());
            BigDecimal scheduled = scheduledDeductionTotal(businessId, profile.getId());
            includeManualAdvances = requested.compareTo(scheduled) > 0;
        } else {
            includeManualAdvances = false;
        }
        List<SalaryAdvance> outstandingAdvances = salaryAdvanceRepository
                .findByBusinessIdAndStaffProfileIdAndStatusOrderByAdvancedOnAscCreatedAtAsc(
                        businessId, profile.getId(), AdvanceStatus.OUTSTANDING
                );
        List<AdvanceBalance> balances = outstandingAdvances.stream()
                .map(a -> {
                    BigDecimal balance = advanceBalance(a);
                    BigDecimal cap = AdvanceRepaymentPlanner.capForPayRun(a, balance, includeManualAdvances);
                    return new AdvanceBalance(a.getId(), balance, cap);
                })
                .toList();
        List<Allocation> allocations = AdvanceRepaymentAllocator.allocate(availableForAdvances, balances);
        BigDecimal deducted = AdvanceRepaymentAllocator.totalAllocated(allocations);

        BigDecimal net = base.subtract(statutoryTotal).subtract(deducted).subtract(other).max(ZERO_MONEY);
        BigDecimal totalDisbursement = arrearNetTotal.add(net);

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
        String payslipNote = buildPayslipNote(body.note(), arrearPeriods);
        payslip.setNote(payslipNote);
        payslip.setCreatedBy(actorId);
        try {
            // Flush now so a concurrent pay for the same person+period surfaces as a
            // clean 409 instead of a raw constraint violation at commit time.
            payslipRepository.saveAndFlush(payslip);
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Payslip already exists for this period");
        }

        if (Boolean.TRUE.equals(body.postExpense()) && totalDisbursement.signum() > 0) {
            String paymentMethod = normalizePaymentMethod(body.paymentMethod());
            String expenseBranch = blankToNull(body.branchId()) != null
                    ? body.branchId().trim()
                    : user.getBranchId();
            String expenseLabel = arrearPeriods.isEmpty()
                    ? "Salary — " + display + " — " + month + "/" + year
                    : "Salary — " + display + " — " + month + "/" + year + " (+ arrears)";
            var expense = expenseService.recordExpense(
                    businessId,
                    new PostExpenseRequest(
                            LocalDate.now(),
                            expenseLabel,
                            FinanceConstants.EXPENSE_CATEGORY_FIXED,
                            totalDisbursement,
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
                    advance.getRepaymentMode(),
                    advance.getRepaymentValue(),
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

    @Transactional
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

    @Transactional
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
                        a.getNote(),
                        a.getRepaymentMode(),
                        a.getRepaymentValue()
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

        Map<String, Object> bank = readBankDetails(profile.getBankDetails());

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
                sharePath,
                blankToNull(profile.getEmployeeCode()),
                blankToNull((String) bank.get("bankName")),
                maskBankAccount((String) bank.get("account"))
        );
    }

    /**
     * Deliberately not {@code @Transactional}: each staff member is paid through the
     * proxied {@link #pay} so a failure rolls back only that person. Running the loop
     * in one shared transaction would let a nested call (profile creation, expense
     * posting) mark it rollback-only and silently discard the whole run at commit.
     */
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
                String reason;
                if (!row.salaryReleased()) {
                    reason = "Salary unlocks on the 25th";
                } else if (JoinPayMode.DEFERRED.equals(row.joinPayMode())) {
                    reason = "Join-month pay is 'no salary until next payroll'";
                } else {
                    reason = "No salary effective for this period";
                }
                failures.add(new PayAllRunResponse.PayAllRunFailure(
                        row.userId(),
                        row.displayName(),
                        reason
                ));
                continue;
            }
            try {
                self.getObject().pay(businessId, row.userId(), new PayRunRequest(
                        year,
                        month,
                        null,
                        null,
                        body.applyStatutory(),
                        body.postExpense(),
                        body.paymentMethod(),
                        body.branchId(),
                        null,
                        null,
                        null
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

    private BigDecimal scheduledDeductionTotal(String businessId, String staffProfileId) {
        return salaryAdvanceRepository
                .findByBusinessIdAndStaffProfileIdAndStatusOrderByAdvancedOnAscCreatedAtAsc(
                        businessId, staffProfileId, AdvanceStatus.OUTSTANDING
                )
                .stream()
                .map(a -> AdvanceRepaymentPlanner.capForPayRun(a, advanceBalance(a), false))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** Scheduled deduction after pay-pool cap and oldest-first allocation. */
    private BigDecimal allocatedAdvanceDeduction(
            String businessId,
            String staffProfileId,
            BigDecimal pool
    ) {
        List<SalaryAdvance> outstandingAdvances = salaryAdvanceRepository
                .findByBusinessIdAndStaffProfileIdAndStatusOrderByAdvancedOnAscCreatedAtAsc(
                        businessId, staffProfileId, AdvanceStatus.OUTSTANDING
                );
        List<AdvanceBalance> balances = outstandingAdvances.stream()
                .map(a -> {
                    BigDecimal balance = advanceBalance(a);
                    BigDecimal cap = AdvanceRepaymentPlanner.capForPayRun(a, balance, false);
                    return new AdvanceBalance(a.getId(), balance, cap);
                })
                .toList();
        return AdvanceRepaymentAllocator.totalAllocated(
                AdvanceRepaymentAllocator.allocate(pool, balances)
        );
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

    /**
     * Consecutive unpaid months immediately before {@code targetYear}/{@code targetMonth}.
     * Stops at the first paid month, missing salary, or {@link #MAX_ARREAR_MONTHS} cap.
     */
    private List<PayrollArrearPeriodResponse> findArrearPeriods(
            String businessId,
            String staffProfileId,
            LocalDate profileStartDate,
            String joinPayMode,
            int targetYear,
            int targetMonth,
            boolean statutory
    ) {
        List<PayrollArrearPeriodResponse> arrears = new ArrayList<>();
        int year = targetYear;
        int month = targetMonth;
        LocalDate today = LocalDate.now();

        while (arrears.size() < MAX_ARREAR_MONTHS) {
            int[] prev = previousPeriod(year, month);
            year = prev[0];
            month = prev[1];

            if (!PayrollPeriod.isReleased(year, month, today)) {
                break;
            }

            if (payslipRepository.findByBusinessIdAndStaffProfileIdAndPeriodYearAndPeriodMonth(
                    businessId, staffProfileId, year, month
            ).isPresent()) {
                break;
            }

            LocalDate asOf = PayrollPeriod.asOf(year, month);
            var currentSalary = salaryRepository.findCurrent(businessId, staffProfileId, asOf);
            if (currentSalary.isEmpty()) {
                break;
            }
            Salary salary = currentSalary.get();
            LocalDate joinDate = SalaryProration.resolveJoinDate(profileStartDate, salary.getEffectiveFrom());
            SalaryProration.Result proration = SalaryProration.apply(
                    money(salary.getAmount()), year, month, joinDate, joinPayMode);
            BigDecimal base = proration.payableAmount();
            if (base.signum() <= 0) {
                break;
            }

            StatutoryBreakdown statutoryBreakdown = statutory
                    ? KenyaPayrollStatutoryCalculator.calculate(base)
                    : null;
            BigDecimal statutoryTotal = statutoryBreakdown != null
                    ? statutoryBreakdown.total()
                    : ZERO_MONEY;
            BigDecimal netBeforeAdvances = base.subtract(statutoryTotal).max(ZERO_MONEY);

            arrears.add(0, new PayrollArrearPeriodResponse(
                    year,
                    month,
                    base,
                    statutoryTotal,
                    statutoryBreakdown != null ? statutoryBreakdown.paye() : ZERO_MONEY,
                    statutoryBreakdown != null ? statutoryBreakdown.nssf() : ZERO_MONEY,
                    statutoryBreakdown != null ? statutoryBreakdown.shif() : ZERO_MONEY,
                    statutoryBreakdown != null ? statutoryBreakdown.housingLevy() : ZERO_MONEY,
                    netBeforeAdvances
            ));
        }

        return arrears;
    }

    private static String resolveJoinPayMode(StaffProfile profile) {
        String mode = profile.getJoinPayMode();
        if (JoinPayMode.isValid(mode)) {
            return JoinPayMode.normalize(mode);
        }
        return JoinPayMode.fromLegacyProrateFlag(profile.isProrateJoinMonth());
    }

    private void persistArrearPayslip(
            String businessId,
            String staffProfileId,
            User user,
            String display,
            PayrollArrearPeriodResponse arrear,
            String note,
            String actorId,
            boolean postExpense,
            String paymentMethod,
            String branchId
    ) {
        if (payslipRepository.findByBusinessIdAndStaffProfileIdAndPeriodYearAndPeriodMonth(
                businessId, staffProfileId, arrear.year(), arrear.month()
        ).isPresent()) {
            return;
        }

        Payslip payslip = new Payslip();
        payslip.setBusinessId(businessId);
        payslip.setStaffProfileId(staffProfileId);
        payslip.setPeriodYear(arrear.year());
        payslip.setPeriodMonth(arrear.month());
        payslip.setBaseSalary(arrear.baseSalary());
        payslip.setAdvancesDeducted(ZERO_MONEY);
        payslip.setOtherDeductions(ZERO_MONEY);
        payslip.setPayeDeducted(arrear.payeSuggested());
        payslip.setNssfDeducted(arrear.nssfSuggested());
        payslip.setShifDeducted(arrear.shifSuggested());
        payslip.setHousingLevyDeducted(arrear.housingLevySuggested());
        payslip.setNetPaid(arrear.netBeforeAdvances());
        payslip.setPaidAt(Instant.now());
        payslip.setNote(blankToNull(note != null ? note : "Arrears catch-up"));
        payslip.setCreatedBy(actorId);
        payslipRepository.save(payslip);

        if (postExpense && arrear.netBeforeAdvances().signum() > 0) {
            String method = normalizePaymentMethod(paymentMethod);
            String expenseBranch = blankToNull(branchId) != null ? branchId.trim() : user.getBranchId();
            var expense = expenseService.recordExpense(
                    businessId,
                    new PostExpenseRequest(
                            LocalDate.now(),
                            "Salary arrears — " + display + " — " + arrear.month() + "/" + arrear.year(),
                            FinanceConstants.EXPENSE_CATEGORY_FIXED,
                            arrear.netBeforeAdvances(),
                            method,
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
            payslip.setPaymentMethod(method);
            payslipRepository.save(payslip);
        }
    }

    private static String buildPayslipNote(String note, List<PayrollArrearPeriodResponse> arrearPeriods) {
        String base = blankToNull(note);
        if (arrearPeriods.isEmpty()) {
            return base;
        }
        String arrearLabel = arrearPeriods.stream()
                .map(a -> a.month() + "/" + a.year())
                .collect(Collectors.joining(", "));
        String suffix = "Includes arrears: " + arrearLabel;
        String full = base == null ? suffix : base + " · " + suffix;
        // payslips.note is VARCHAR(500); the DTO check only covers the raw note.
        return full.length() > 500 ? full.substring(0, 500) : full;
    }

    /** Returns {@code [year, month]} for the calendar month before the given period. */
    private static int[] previousPeriod(int year, int month) {
        if (month == 1) {
            return new int[] { year - 1, 12 };
        }
        return new int[] { year, month - 1 };
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
        BigDecimal balance = advanceBalance(a);
        return new SalaryAdvanceResponse(
                a.getId(),
                a.getStaffProfileId(),
                userId,
                a.getAmount(),
                a.getAmountRepaid(),
                balance,
                a.getAdvancedOn(),
                a.getNote(),
                a.getStatus(),
                a.getRepaidInPayslipId(),
                a.getRepaymentMode(),
                a.getRepaymentValue(),
                AdvanceRepaymentPlanner.capForPayRun(a, balance, false),
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

    private Map<String, Object> readBankDetails(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    /** Keeps only the last four characters so payslips never expose full account numbers. */
    private static String maskBankAccount(String account) {
        if (account == null || account.isBlank()) {
            return null;
        }
        String trimmed = account.trim();
        int keep = Math.min(4, trimmed.length());
        return "\u2022\u2022\u2022\u2022 " + trimmed.substring(trimmed.length() - keep);
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
