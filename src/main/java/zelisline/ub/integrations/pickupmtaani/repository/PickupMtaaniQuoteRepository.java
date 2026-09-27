package zelisline.ub.integrations.pickupmtaani.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.integrations.pickupmtaani.domain.PickupMtaaniQuote;

public interface PickupMtaaniQuoteRepository extends JpaRepository<PickupMtaaniQuote, String> {

    Optional<PickupMtaaniQuote> findByIdAndBusinessId(String id, String businessId);
}
