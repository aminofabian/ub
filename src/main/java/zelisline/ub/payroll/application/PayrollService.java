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
import zelisline.ub.payroll.api.dto.PayRunRequest;
import zelisline.ub.payroll.api.dto.PayrollRunRowResponse;
import zelisline.ub.payroll.api.dto.PayslipResponse;
import zelisline.ub.payroll.api.dto.SalaryAdvanceResponse;
import zelisline.ub.payroll.api.dto.SalaryResponse;
import zelisline.ub.payroll.domain.AdvanceStatus;
import zelisline.ub.payroll.domain.EmploymentStatus;
import zelisline.ub.payroll.domain.Payslip;
import zelisline.ub.payroll.domain.Salary;
import zelisline.ub.payroll.domain.SalaryAdvance;
import zelisline.ub.payroll.domain.StaffProfile;
import zelisline.ub.payroll.repository.PayslipRepository;
import zelisline.ub.payroll.repository.SalaryAdvanceRepository;
import zelisline.ub.payroll.repository.SalaryRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.repository.BranchRepository;

/**
 * Monthly payroll: salaries, advances, and payslips.
 *
 * <p>Advance repayment on payday: deduct {@code min(outstanding_total, max(0, base - other_deductions))}
 * as a pool; mark outstanding advances repaid oldest-first while the pool covers each advance
 * in full. An advance larger than the remaining pool stays outstanding (no partial splits in MVP).
 * {@code payslips.expense_id} is reserved for a future GL post.
 */
@Service
@RequiredArgsConstructor
public class PayrollService {

    private final StaffProfileService staffProfileService;
    private final SalaryRepository salaryRepository;
    private final SalaryAdvanceRepository salaryAdvanceRepository;
    private final PayslipRepository payslipRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final BranchRepository branchRepository;

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
    public List<PayrollRunRowResponse> previewRun(String businessId, int year, int month) {
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

        List<PayrollRunRowResponse> rows = new ArrayList<>();
        for (User user : users) {
            if ("buyer".equalsIgnoreCase(roleKeys.getOrDefault(user.getRoleId(), ""))) {
                continue;
            }
            StaffProfile profile = staffProfileService.ensureProfile(businessId, user.getId());
            if (EmploymentStatus.TERMINATED.equals(profile.getEmploymentStatus())) {
                continue;
            }

            BigDecimal base = salaryRepository.findCurrent(businessId, profile.getId(), asOf)
                    .map(Salary::getAmount)
                    .orElse(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));

            BigDecimal outstanding = outstandingTotal(businessId, profile.getId());
            BigDecimal suggestedNet = base.subtract(outstanding).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

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
                    base,
                    outstanding,
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

        BigDecimal availableForAdvances = base.subtract(other).max(BigDecimal.ZERO);
        List<SalaryAdvance> outstanding = salaryAdvanceRepository
                .findByBusinessIdAndStaffProfileIdAndStatusOrderByAdvancedOnAscCreatedAtAsc(
                        businessId, profile.getId(), AdvanceStatus.OUTSTANDING
                );

        BigDecimal pool = availableForAdvances;
        BigDecimal deducted = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        List<SalaryAdvance> toRepay = new ArrayList<>();
        for (SalaryAdvance advance : outstanding) {
            BigDecimal amt = advance.getAmount();
            if (pool.compareTo(amt) >= 0) {
                toRepay.add(advance);
                pool = pool.subtract(amt);
                deducted = deducted.add(amt);
            } else {
                // Leave remaining advances outstanding (no partial splits in MVP).
                break;
            }
        }

        BigDecimal net = base.subtract(deducted).subtract(other).max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);

        Payslip payslip = new Payslip();
        payslip.setBusinessId(businessId);
        payslip.setStaffProfileId(profile.getId());
        payslip.setPeriodYear(year);
        payslip.setPeriodMonth(month);
        payslip.setBaseSalary(base);
        payslip.setAdvancesDeducted(deducted);
        payslip.setOtherDeductions(other);
        payslip.setNetPaid(net);
        payslip.setPaidAt(Instant.now());
        payslip.setNote(blankToNull(body.note()));
        payslip.setCreatedBy(actorId);
        payslipRepository.save(payslip);

        for (SalaryAdvance advance : toRepay) {
            advance.setStatus(AdvanceStatus.REPAID);
            advance.setRepaidInPayslipId(payslip.getId());
            salaryAdvanceRepository.save(advance);
        }

        return toPayslipResponse(payslip, userId, displayName(profile, userId, businessId));
    }

    private BigDecimal outstandingTotal(String businessId, String staffProfileId) {
        return salaryAdvanceRepository
                .findByBusinessIdAndStaffProfileIdAndStatusOrderByAdvancedOnAscCreatedAtAsc(
                        businessId, staffProfileId, AdvanceStatus.OUTSTANDING
                )
                .stream()
                .map(SalaryAdvance::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
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
                p.getNetPaid(),
                p.getPaidAt(),
                p.getNote(),
                p.getExpenseId()
        );
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
