package zelisline.ub.payroll.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.payroll.domain.SalaryAdvanceRepayment;

public interface SalaryAdvanceRepaymentRepository extends JpaRepository<SalaryAdvanceRepayment, String> {

    List<SalaryAdvanceRepayment> findByAdvanceIdOrderByCreatedAtAsc(String advanceId);

    List<SalaryAdvanceRepayment> findByPayslipIdOrderByCreatedAtAsc(String payslipId);
}
