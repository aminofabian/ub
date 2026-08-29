package zelisline.ub.messaging.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import zelisline.ub.messaging.domain.BusinessSmsCreditAccount;

public interface BusinessSmsCreditAccountRepository
        extends JpaRepository<BusinessSmsCreditAccount, String> {

    Optional<BusinessSmsCreditAccount> findByBusinessId(String businessId);

    List<BusinessSmsCreditAccount> findByBusinessIdIn(java.util.Collection<String> businessIds);

    List<BusinessSmsCreditAccount> findByPurchasedBalanceLessThanEqual(int purchasedBalance);

    /**
     * Row-locked read for atomic credit movements. All debits/credits go through
     * this lock so concurrent sends can never drive the ledger negative
     * (SMS_CREDITS_SCOPE.md §9 optimistic lock + single choke point).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from BusinessSmsCreditAccount a where a.businessId = :businessId")
    Optional<BusinessSmsCreditAccount> findForUpdate(@Param("businessId") String businessId);
}
