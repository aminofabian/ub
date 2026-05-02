package zelisline.ub.finance.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.finance.domain.LedgerAccount;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, String> {

    Optional<LedgerAccount> findByBusinessIdAndCode(String businessId, String code);

    boolean existsByBusinessIdAndCode(String businessId, String code);
}
