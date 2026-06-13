package zelisline.ub.grocery.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.grocery.domain.BranchGrocerySequence;

public interface BranchGrocerySequenceRepository extends JpaRepository<BranchGrocerySequence, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from BranchGrocerySequence s where s.branchId = :branchId")
    Optional<BranchGrocerySequence> findByBranchIdForUpdate(@Param("branchId") String branchId);
}
