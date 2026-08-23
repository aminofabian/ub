package zelisline.ub.credits.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.credits.domain.CustomerPhone;

public interface CustomerPhoneRepository extends JpaRepository<CustomerPhone, String> {

    List<CustomerPhone> findByCustomerIdOrderByCreatedAtAsc(String customerId);

    List<CustomerPhone> findByCustomerIdIn(Collection<String> customerIds);

    boolean existsByBusinessIdAndPhone(String businessId, String phone);

    Optional<CustomerPhone> findFirstByBusinessIdAndPhone(String businessId, String phone);

    List<CustomerPhone> findByBusinessIdAndMaskFingerprint(String businessId, String maskFingerprint);

    /**
     * Shops a phone has a customer record in (Phase 4 apex "one door"). Matches
     * any of the candidate digit forms stored across writers (`07…`, `2547…`, raw).
     */
    @Query("select distinct c.businessId from CustomerPhone c where c.phone in :phones")
    List<String> findDistinctBusinessIdByPhones(@Param("phones") Collection<String> phones);

    void deleteByCustomerId(String customerId);
}
