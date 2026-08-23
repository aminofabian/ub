package zelisline.ub.inventory.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.inventory.domain.RestockSuggestion;

public interface RestockSuggestionRepository extends JpaRepository<RestockSuggestion, String> {

    List<RestockSuggestion> findByRunIdOrderBySuggestedQtyDescIdAsc(String runId);

    List<RestockSuggestion> findByBusinessIdAndBranchIdAndStatusAndSnoozeUntilGreaterThanEqual(
            String businessId,
            String branchId,
            String status,
            java.time.LocalDate snoozeUntil
    );
}
