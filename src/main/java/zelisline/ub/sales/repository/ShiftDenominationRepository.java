package zelisline.ub.sales.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.sales.domain.ShiftDenomination;

public interface ShiftDenominationRepository extends JpaRepository<ShiftDenomination, String> {

    List<ShiftDenomination> findByShiftIdAndCountTypeOrderByDenominationDesc(String shiftId, String countType);

    List<ShiftDenomination> findByShiftIdOrderByCountTypeAscDenominationDesc(String shiftId);

    /** Flush so a replace (delete + insert) cannot hit uq_shift_denomination. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ShiftDenomination d where d.shiftId = :shiftId and d.countType = :countType")
    void deleteByShiftIdAndCountType(
            @Param("shiftId") String shiftId,
            @Param("countType") String countType);
}
