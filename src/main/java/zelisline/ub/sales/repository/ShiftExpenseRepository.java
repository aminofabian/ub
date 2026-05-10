package zelisline.ub.sales.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.sales.domain.ShiftExpense;

public interface ShiftExpenseRepository extends JpaRepository<ShiftExpense, String> {

    List<ShiftExpense> findByShiftIdOrderByCreatedAtDesc(String shiftId);

    List<ShiftExpense> findByShiftIdAndTypeOrderByCreatedAtDesc(String shiftId, String type);
}
