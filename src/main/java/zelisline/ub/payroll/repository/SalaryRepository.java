package zelisline.ub.payroll.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.payroll.domain.Salary;

public interface SalaryRepository extends JpaRepository<Salary, String> {

    List<Salary> findByBusinessIdAndStaffProfileIdOrderByEffectiveFromDescCreatedAtDesc(
            String businessId,
            String staffProfileId
    );

    @Query("""
            SELECT s FROM Salary s
            WHERE s.businessId = :businessId
              AND s.staffProfileId = :staffProfileId
              AND s.effectiveFrom <= :asOf
            ORDER BY s.effectiveFrom DESC, s.createdAt DESC
            """)
    List<Salary> findEffectiveCandidates(
            @Param("businessId") String businessId,
            @Param("staffProfileId") String staffProfileId,
            @Param("asOf") LocalDate asOf
    );

    default Optional<Salary> findCurrent(String businessId, String staffProfileId, LocalDate asOf) {
        List<Salary> rows = findEffectiveCandidates(businessId, staffProfileId, asOf);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
