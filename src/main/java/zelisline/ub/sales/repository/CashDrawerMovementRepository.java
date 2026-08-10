package zelisline.ub.sales.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.sales.domain.CashDrawerMovement;

public interface CashDrawerMovementRepository extends JpaRepository<CashDrawerMovement, String> {

    List<CashDrawerMovement> findByShiftIdOrderByCreatedAtAsc(String shiftId);

    boolean existsByShiftIdAndEventType(String shiftId, String eventType);

    /** Idempotency key for replay: one movement per (shift, reference, event, denomination). */
    boolean existsByShiftIdAndReferenceTypeAndReferenceIdAndEventTypeAndDenomination(
            String shiftId,
            String referenceType,
            String referenceId,
            String eventType,
            int denomination);

    long countByShiftId(String shiftId);

    @Modifying
    @Query("delete from CashDrawerMovement m where m.shiftId = :shiftId")
    void deleteByShiftId(@Param("shiftId") String shiftId);
}
