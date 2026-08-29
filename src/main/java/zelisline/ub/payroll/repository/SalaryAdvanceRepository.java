package zelisline.ub.payroll.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.payroll.domain.SalaryAdvance;

public interface SalaryAdvanceRepository extends JpaRepository<SalaryAdvance, String> {

    List<SalaryAdvance> findByBusinessIdAndStaffProfileIdOrderByAdvancedOnDescCreatedAtDesc(
            String businessId,
            String staffProfileId
    );

    List<SalaryAdvance> findByBusinessIdAndStaffProfileIdAndStatusOrderByAdvancedOnAscCreatedAtAsc(
            String businessId,
            String staffProfileId,
            String status
    );

    List<SalaryAdvance> findByBusinessIdOrderByAdvancedOnDescCreatedAtDesc(String businessId);

    List<SalaryAdvance> findByBusinessIdAndStatusOrderByAdvancedOnDescCreatedAtDesc(
            String businessId,
            String status
    );
}
