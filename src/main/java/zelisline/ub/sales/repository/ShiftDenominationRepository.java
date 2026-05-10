package zelisline.ub.sales.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.sales.domain.ShiftDenomination;

public interface ShiftDenominationRepository extends JpaRepository<ShiftDenomination, String> {

    List<ShiftDenomination> findByShiftIdAndCountTypeOrderByDenominationDesc(String shiftId, String countType);

    List<ShiftDenomination> findByShiftIdOrderByCountTypeAscDenominationDesc(String shiftId);

    void deleteByShiftIdAndCountType(String shiftId, String countType);
}
