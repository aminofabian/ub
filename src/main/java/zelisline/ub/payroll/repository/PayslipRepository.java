package zelisline.ub.payroll.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.payroll.domain.Payslip;

public interface PayslipRepository extends JpaRepository<Payslip, String> {

    List<Payslip> findByBusinessIdAndStaffProfileIdOrderByPeriodYearDescPeriodMonthDesc(
            String businessId,
            String staffProfileId
    );

    Optional<Payslip> findByBusinessIdAndStaffProfileIdAndPeriodYearAndPeriodMonth(
            String businessId,
            String staffProfileId,
            int periodYear,
            int periodMonth
    );

    List<Payslip> findByBusinessIdAndPeriodYearAndPeriodMonth(
            String businessId,
            int periodYear,
            int periodMonth
    );

    List<Payslip> findByBusinessIdAndPeriodYearOrderByPeriodMonthAsc(
            String businessId,
            int periodYear
    );
}
