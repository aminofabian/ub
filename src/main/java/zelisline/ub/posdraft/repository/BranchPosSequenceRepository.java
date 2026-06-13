package zelisline.ub.posdraft.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.posdraft.domain.BranchPosSequence;

public interface BranchPosSequenceRepository extends JpaRepository<BranchPosSequence, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from BranchPosSequence s where s.branchId = :branchId")
    Optional<BranchPosSequence> findByBranchIdForUpdate(@Param("branchId") String branchId);
}
