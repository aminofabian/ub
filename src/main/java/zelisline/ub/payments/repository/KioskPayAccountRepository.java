package zelisline.ub.payments.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;
import zelisline.ub.payments.api.dto.KioskPayAccountSummary;
import zelisline.ub.payments.domain.KioskPayAccount;

public interface KioskPayAccountRepository extends JpaRepository<KioskPayAccount, String> {

    Optional<KioskPayAccount> findByBusinessId(String businessId);

    long countByStatus(String status);

    /**
     * Serializes balance-affecting operations per business (withdraw request flow).
     * The row must already exist (see {@code getOrCreate}).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from KioskPayAccount a where a.businessId = :businessId")
    Optional<KioskPayAccount> findByBusinessIdForUpdate(String businessId);

    @Query("""
            select new zelisline.ub.payments.api.dto.KioskPayAccountSummary(
                count(a),
                coalesce(sum(a.availableBalance), cast(0 as big_decimal)),
                coalesce(sum(a.pendingBalance), cast(0 as big_decimal)),
                coalesce(sum(a.lifetimeIn), cast(0 as big_decimal)),
                coalesce(sum(a.lifetimeOut), cast(0 as big_decimal)))
            from KioskPayAccount a
            """)
    KioskPayAccountSummary summarize();
}
