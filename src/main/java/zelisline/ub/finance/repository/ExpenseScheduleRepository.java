package zelisline.ub.finance.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.finance.domain.ExpenseSchedule;

public interface ExpenseScheduleRepository extends JpaRepository<ExpenseSchedule, String> {

    List<ExpenseSchedule> findByBusinessIdAndActiveTrue(String businessId);

    Optional<ExpenseSchedule> findByIdAndBusinessId(String id, String businessId);
}

