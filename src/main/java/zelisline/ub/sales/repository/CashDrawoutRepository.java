package zelisline.ub.sales.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.sales.domain.CashDrawout;

public interface CashDrawoutRepository extends JpaRepository<CashDrawout, String> {

    List<CashDrawout> findByShiftIdOrderByCreatedAtDesc(String shiftId);

    List<CashDrawout> findByShiftIdAndStatusOrderByCreatedAtDesc(String shiftId, String status);

    List<CashDrawout> findByStatusOrderByCreatedAtAsc(String status);

    long countByShiftIdAndStatus(String shiftId, String status);

    long countByShiftIdAndRecurringItemIdAndStatus(String shiftId, String recurringItemId, String status);

    Optional<CashDrawout> findByIdAndShiftId(String id, String shiftId);
}
