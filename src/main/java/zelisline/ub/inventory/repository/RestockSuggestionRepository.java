package zelisline.ub.inventory.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.inventory.domain.RestockSuggestion;

public interface RestockSuggestionRepository extends JpaRepository<RestockSuggestion, String> {

    List<RestockSuggestion> findByRunIdOrderBySuggestedQtyDescIdAsc(String runId);

    boolean existsByRunIdAndStatus(String runId, String status);

    /** Snoozed suggestions on still-active runs only (expired runs don't suppress the next pass). */
    @Query("""
            select s from RestockSuggestion s
             join RestockRun r on r.id = s.runId
             where s.businessId = :businessId
               and s.branchId = :branchId
               and s.status = :status
               and s.snoozeUntil >= :snoozeUntil
               and r.status <> :expiredRunStatus
            """)
    List<RestockSuggestion> findByBusinessIdAndBranchIdAndStatusAndSnoozeUntilGreaterThanEqual(
            String businessId,
            String branchId,
            String status,
            java.time.LocalDate snoozeUntil,
            String expiredRunStatus
    );

    /**
     * Accepted-qty / suggested-qty history per item (across runs for the branch).
     * Used by Phase-4 par learning — biases future par toward what the reviewer
     * actually accepted. {@code ratio} is the mean of per-line accepted/suggested.
     */
    interface AcceptedRatioRow {
        String getItemId();

        Double getRatio();

        long getCount();
    }

    @Query("""
            select s.itemId as itemId,
                   avg(s.acceptedQty / s.suggestedQty) as ratio,
                   count(s.id) as count
              from RestockSuggestion s
             where s.businessId = :businessId
               and s.branchId = :branchId
               and s.status = :status
               and s.acceptedQty is not null
               and s.acceptedQty > 0
               and s.suggestedQty > 0
             group by s.itemId
            """)
    List<AcceptedRatioRow> acceptedRatioByItem(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("status") String status
    );
}
