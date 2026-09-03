package zelisline.ub.credits.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerPhone;

public interface CustomerRepository extends JpaRepository<Customer, String> {

    Optional<Customer> findByIdAndBusinessIdAndDeletedAtIsNull(String id, String businessId);

    List<Customer> findByIdInAndBusinessIdAndDeletedAtIsNull(
            java.util.Collection<String> ids, String businessId);

    /** All live customers of a business — used by the desktop sync export. */
    List<Customer> findByBusinessIdAndDeletedAtIsNull(String businessId);

    /**
     * Customers the till must upload: never synced, or edited locally since the
     * last sync. Balance changes live on {@code credit_accounts} (not the
     * customer row), so a credit sale / tab payment is covered by the subquery.
     */
    @Query("""
            select c from Customer c
             where c.businessId = :businessId
               and c.deletedAt is null
               and (c.cloudSyncedAt is null
                    or c.updatedAt > c.cloudSyncedAt
                    or exists (select 1 from CreditAccount a
                                where a.customerId = c.id
                                  and a.updatedAt > c.cloudSyncedAt)
                    or exists (select 1 from CustomerPhone p
                                where p.customerId = c.id
                                  and p.createdAt > c.cloudSyncedAt))
             order by c.updatedAt asc""")
    List<Customer> findDirtyForDesktopSync(@Param("businessId") String businessId);

    Optional<Customer> findByBusinessIdAndMpesaIdentityKeyAndDeletedAtIsNull(
            String businessId, String mpesaIdentityKey);

    List<Customer> findByBusinessIdAndFirstNameNormAndLastNameNormAndDeletedAtIsNull(
            String businessId, String firstNameNorm, String lastNameNorm);

    /**
     * Current highest customer number for the business (empty when none yet).
     *
     * <p>Locks the max row (and the trailing gap on the
     * {@code (business_id, customer_no)} unique index) so concurrent creates
     * for the same business serialize instead of colliding; the unique index
     * is the backstop. Callers add 1 for the next number. A plain (non-aggregate)
     * {@code FOR UPDATE} select is used because MySQL/MariaDB (and H2's MySQL
     * mode) reject {@code FOR UPDATE} on grouped/aggregate queries, and Hibernate 7
     * forbids {@code @Lock} on native queries.
     */
    @Query(
            value = "SELECT customer_no FROM customers WHERE business_id = :businessId"
                    + " ORDER BY customer_no DESC LIMIT 1 FOR UPDATE",
            nativeQuery = true
    )
    Optional<Long> nextCustomerNo(@Param("businessId") String businessId);

    Page<Customer> findByBusinessIdAndDeletedAtIsNullOrderByCustomerNoAscNameAsc(
            String businessId, Pageable pageable);

    @Query(
            """
                    select c from Customer c
                     where c.businessId = :businessId
                       and c.deletedAt is null
                       and c.anonymisedAt is null
                       and c.email is not null
                       and lower(trim(c.email)) = lower(trim(:emailNorm))
                     order by c.updatedAt desc""")
    List<Customer> findActiveByBusinessIdAndNormalizedEmail(
            @Param("businessId") String businessId,
            @Param("emailNorm") String emailNormNormalized,
            Pageable pageable
    );

    Page<Customer> findByBusinessIdAndDeletedAtIsNullOrderByNameAsc(String businessId, Pageable pageable);

    @Query(
            """
                    select c from Customer c
                     where c.businessId = :businessId
                       and c.deletedAt is null
                       and (:createdFrom is null or c.createdAt >= :createdFrom)
                       and (:createdToExclusive is null or c.createdAt < :createdToExclusive)
                     order by c.customerNo asc, c.name asc""")
    Page<Customer> findByBusinessIdAndDeletedAtIsNullAndCreatedAtRange(
            @Param("businessId") String businessId,
            @Param("createdFrom") Instant createdFrom,
            @Param("createdToExclusive") Instant createdToExclusive,
            Pageable pageable
    );

    @Query(
            value = """
                    SELECT DISTINCT c FROM Customer c, CustomerPhone p
                    WHERE p.customerId = c.id
                      AND c.businessId = :businessId
                      AND c.deletedAt IS NULL
                      AND p.businessId = :businessId
                      AND p.phone = :phoneNormalized
                    """,
            countQuery = """
                    SELECT COUNT(DISTINCT c) FROM Customer c, CustomerPhone p
                    WHERE p.customerId = c.id
                      AND c.businessId = :businessId
                      AND c.deletedAt IS NULL
                      AND p.businessId = :businessId
                      AND p.phone = :phoneNormalized
                    """
    )
    Page<Customer> findByBusinessIdAndPhoneNormalized(
            @Param("businessId") String businessId,
            @Param("phoneNormalized") String phoneNormalized,
            Pageable pageable
    );

    @Query(
            value = """
                    SELECT DISTINCT c FROM Customer c, CustomerPhone p
                    WHERE p.customerId = c.id
                      AND c.businessId = :businessId
                      AND c.deletedAt IS NULL
                      AND p.businessId = :businessId
                      AND p.phone = :phoneNormalized
                      AND (:createdFrom IS NULL OR c.createdAt >= :createdFrom)
                      AND (:createdToExclusive IS NULL OR c.createdAt < :createdToExclusive)
                    """,
            countQuery = """
                    SELECT COUNT(DISTINCT c) FROM Customer c, CustomerPhone p
                    WHERE p.customerId = c.id
                      AND c.businessId = :businessId
                      AND c.deletedAt IS NULL
                      AND p.businessId = :businessId
                      AND p.phone = :phoneNormalized
                      AND (:createdFrom IS NULL OR c.createdAt >= :createdFrom)
                      AND (:createdToExclusive IS NULL OR c.createdAt < :createdToExclusive)
                    """
    )
    Page<Customer> findByBusinessIdAndPhoneNormalizedAndCreatedAtRange(
            @Param("businessId") String businessId,
            @Param("phoneNormalized") String phoneNormalized,
            @Param("createdFrom") Instant createdFrom,
            @Param("createdToExclusive") Instant createdToExclusive,
            Pageable pageable
    );

    /**
     * Name contains (case-insensitive) and/or phone digit contains.
     * Used for Tab checkout when admin enables name search.
     */
    @Query(
            value = """
                    SELECT DISTINCT c FROM Customer c
                    WHERE c.businessId = :businessId
                      AND c.deletedAt IS NULL
                      AND (
                            (:namePart IS NOT NULL AND (
                                lower(c.name) LIKE concat('%', :namePart, '%')
                             OR lower(coalesce(c.firstName, '')) LIKE concat('%', :namePart, '%')
                             OR lower(coalesce(c.lastName, '')) LIKE concat('%', :namePart, '%')
                            ))
                         OR (:phoneDigits IS NOT NULL AND EXISTS (
                                SELECT 1 FROM CustomerPhone p
                                 WHERE p.customerId = c.id
                                   AND p.businessId = :businessId
                                   AND (
                                        (p.phone IS NOT NULL AND p.phone LIKE concat('%', :phoneDigits, '%'))
                                     OR (p.assignedMsisdn IS NOT NULL AND p.assignedMsisdn LIKE concat('%', :phoneDigits, '%'))
                                     OR (p.maskedMsisdn IS NOT NULL AND p.maskedMsisdn LIKE concat('%', :phoneDigits, '%'))
                                   )
                            ))
                         OR (:customerNo IS NOT NULL AND c.customerNo = :customerNo)
                      )
                      AND (:createdFrom IS NULL OR c.createdAt >= :createdFrom)
                      AND (:createdToExclusive IS NULL OR c.createdAt < :createdToExclusive)
                    ORDER BY c.customerNo ASC, c.name ASC
                    """,
            countQuery = """
                    SELECT COUNT(DISTINCT c) FROM Customer c
                    WHERE c.businessId = :businessId
                      AND c.deletedAt IS NULL
                      AND (
                            (:namePart IS NOT NULL AND (
                                lower(c.name) LIKE concat('%', :namePart, '%')
                             OR lower(coalesce(c.firstName, '')) LIKE concat('%', :namePart, '%')
                             OR lower(coalesce(c.lastName, '')) LIKE concat('%', :namePart, '%')
                            ))
                         OR (:phoneDigits IS NOT NULL AND EXISTS (
                                SELECT 1 FROM CustomerPhone p
                                 WHERE p.customerId = c.id
                                   AND p.businessId = :businessId
                                   AND (
                                        (p.phone IS NOT NULL AND p.phone LIKE concat('%', :phoneDigits, '%'))
                                     OR (p.assignedMsisdn IS NOT NULL AND p.assignedMsisdn LIKE concat('%', :phoneDigits, '%'))
                                     OR (p.maskedMsisdn IS NOT NULL AND p.maskedMsisdn LIKE concat('%', :phoneDigits, '%'))
                                   )
                            ))
                         OR (:customerNo IS NOT NULL AND c.customerNo = :customerNo)
                      )
                      AND (:createdFrom IS NULL OR c.createdAt >= :createdFrom)
                      AND (:createdToExclusive IS NULL OR c.createdAt < :createdToExclusive)
                    """
    )
    Page<Customer> findByBusinessIdAndNameOrPhoneContains(
            @Param("businessId") String businessId,
            @Param("namePart") String namePartLower,
            @Param("phoneDigits") String phoneDigits,
            @Param("customerNo") Long customerNo,
            @Param("createdFrom") Instant createdFrom,
            @Param("createdToExclusive") Instant createdToExclusive,
            Pageable pageable
    );
}
