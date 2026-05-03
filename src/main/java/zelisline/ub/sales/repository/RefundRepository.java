package zelisline.ub.sales.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.sales.domain.Refund;

public interface RefundRepository extends JpaRepository<Refund, String> {

    Optional<Refund> findByBusinessIdAndIdempotencyKey(String businessId, String idempotencyKey);
}
