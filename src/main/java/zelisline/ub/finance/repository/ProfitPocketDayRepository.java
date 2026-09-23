package zelisline.ub.finance.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.finance.domain.ProfitPocketDay;

public interface ProfitPocketDayRepository extends JpaRepository<ProfitPocketDay, String> {

    Optional<ProfitPocketDay> findByBusinessIdAndBranchKeyAndPocketDate(
            String businessId,
            String branchKey,
            LocalDate pocketDate
    );

    List<ProfitPocketDay> findByBusinessIdAndPocketDateBetweenOrderByPocketDateAsc(
            String businessId,
            LocalDate from,
            LocalDate to
    );
}
