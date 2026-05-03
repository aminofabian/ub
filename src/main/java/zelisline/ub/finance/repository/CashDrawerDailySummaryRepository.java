package zelisline.ub.finance.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.finance.domain.CashDrawerDailySummary;

public interface CashDrawerDailySummaryRepository extends JpaRepository<CashDrawerDailySummary, String> {

    Optional<CashDrawerDailySummary> findByShiftId(String shiftId);
}

