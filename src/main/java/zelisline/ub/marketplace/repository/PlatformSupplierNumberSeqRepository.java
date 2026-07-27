package zelisline.ub.marketplace.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import zelisline.ub.marketplace.domain.PlatformSupplierNumberSeq;

public interface PlatformSupplierNumberSeqRepository extends JpaRepository<PlatformSupplierNumberSeq, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM PlatformSupplierNumberSeq s WHERE s.id = :id")
    Optional<PlatformSupplierNumberSeq> findByIdForUpdate(@Param("id") String id);
}
