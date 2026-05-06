package zelisline.ub.credits.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.credits.domain.Customer;

public interface CustomerRepository extends JpaRepository<Customer, String> {

    Optional<Customer> findByIdAndBusinessIdAndDeletedAtIsNull(String id, String businessId);

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
}
