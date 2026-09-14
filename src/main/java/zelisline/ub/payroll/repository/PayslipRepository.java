package zelisline.ub.payroll.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
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

    /**
     * Serializes payslip-number assignment: the locking read blocks concurrent
     * pays for the same business+month until the current transaction commits,
     * so {@code count + 1} yields gap-free, collision-free sequence numbers.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "select count(p) from Payslip p " +
                    "where p.businessId = :businessId " +
                    "and p.periodYear = :year and p.periodMonth = :month"
    )
    long countForPeriodLocked(
            @Param("businessId") String businessId,
            @Param("year") int year,
            @Param("month") int month
    );
}
