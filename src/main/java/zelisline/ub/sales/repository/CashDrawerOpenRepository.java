package zelisline.ub.sales.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.sales.domain.CashDrawerOpen;

public interface CashDrawerOpenRepository extends JpaRepository<CashDrawerOpen, String> {

    Optional<CashDrawerOpen> findByBusinessIdAndClientEventId(String businessId, String clientEventId);

    List<CashDrawerOpen> findByBusinessIdAndShiftIdOrderByOpenedAtAsc(String businessId, String shiftId);

    List<CashDrawerOpen> findByBusinessIdAndShiftIdOrderByOpenedAtDesc(
            String businessId,
            String shiftId,
            Pageable pageable);
}
