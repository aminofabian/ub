package zelisline.ub.finance.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.finance.domain.ExpenseScheduleOccurrence;

public interface ExpenseScheduleOccurrenceRepository extends JpaRepository<ExpenseScheduleOccurrence, String> {

    Optional<ExpenseScheduleOccurrence> findByScheduleIdAndOccurrenceDate(String scheduleId, LocalDate occurrenceDate);

    Optional<ExpenseScheduleOccurrence> findByIdAndBusinessId(String id, String businessId);

    List<ExpenseScheduleOccurrence> findByBusinessIdAndOccurrenceDateBetween(
            String businessId,
            LocalDate start,
            LocalDate end
    );

    long countByScheduleId(String scheduleId);
}
