package zelisline.ub.payments.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.payments.domain.KioskPayAccount;

public interface KioskPayAccountRepository extends JpaRepository<KioskPayAccount, String> {

    Optional<KioskPayAccount> findByBusinessId(String businessId);
}
