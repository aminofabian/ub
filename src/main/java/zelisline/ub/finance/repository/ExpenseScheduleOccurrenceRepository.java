package zelisline.ub.finance.repository;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.finance.domain.ExpenseScheduleOccurrence;

public interface ExpenseScheduleOccurrenceRepository extends JpaRepository<ExpenseScheduleOccurrence, String> {

    Optional<ExpenseScheduleOccurrence> findByScheduleIdAndOccurrenceDate(String scheduleId, LocalDate occurrenceDate);

    long countByScheduleId(String scheduleId);
}

