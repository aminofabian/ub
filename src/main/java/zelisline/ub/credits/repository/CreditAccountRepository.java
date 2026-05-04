package zelisline.ub.credits.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import zelisline.ub.credits.domain.CreditAccount;

public interface CreditAccountRepository extends JpaRepository<CreditAccount, String> {

    Optional<CreditAccount> findByCustomerIdAndBusinessId(String customerId, String businessId);

    List<CreditAccount> findByCustomerIdIn(Collection<String> customerIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CreditAccount c where c.id = :id and c.businessId = :businessId")
    Optional<CreditAccount> findByIdAndBusinessIdForUpdate(
            @Param("id") String id,
            @Param("businessId") String businessId
    );

    Optional<CreditAccount> findByIdAndBusinessId(String id, String businessId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CreditAccount c where c.customerId = :customerId and c.businessId = :businessId")
    Optional<CreditAccount> findByCustomerIdAndBusinessIdForUpdate(
            @Param("customerId") String customerId,
            @Param("businessId") String businessId
    );

    /**
     * Accounts considered "overdue" by the reminder sweep: a positive open balance, no activity
     * since {@code staleBefore}, opted-in to reminders. Falls back to {@code createdAt} for
     * accounts that have never seen movement so the sweep covers freshly-funded tabs too.
     */
    @Query("""
            select c from CreditAccount c
            where c.balanceOwed > :minOwed
              and c.remindersOptOut = false
              and coalesce(c.lastActivityAt, c.createdAt) < :staleBefore
            order by coalesce(c.lastActivityAt, c.createdAt) asc
            """)
    List<CreditAccount> findOverdueForReminder(
            @Param("minOwed") BigDecimal minOwed,
            @Param("staleBefore") Instant staleBefore
    );
}
