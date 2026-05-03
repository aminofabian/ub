package zelisline.ub.credits.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.credits.domain.MpesaStkIntent;

public interface MpesaStkIntentRepository extends JpaRepository<MpesaStkIntent, String> {

    Optional<MpesaStkIntent> findByBusinessIdAndIdempotencyKey(String businessId, String idempotencyKey);
}
