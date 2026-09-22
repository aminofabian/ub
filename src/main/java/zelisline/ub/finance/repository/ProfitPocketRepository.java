package zelisline.ub.finance.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.finance.domain.ProfitPocket;

public interface ProfitPocketRepository extends JpaRepository<ProfitPocket, String> {

    Optional<ProfitPocket> findByIdAndBusinessId(String id, String businessId);

    Optional<ProfitPocket> findByBusinessIdAndKopokopoSendMoneyId(String businessId, String kopokopoSendMoneyId);

    Optional<ProfitPocket> findFirstByKopokopoSendMoneyId(String kopokopoSendMoneyId);

    List<ProfitPocket> findByBusinessIdOrderByCreatedAtDesc(String businessId, Pageable pageable);
}
