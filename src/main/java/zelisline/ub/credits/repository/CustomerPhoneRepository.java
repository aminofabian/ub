package zelisline.ub.credits.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.credits.domain.CustomerPhone;

public interface CustomerPhoneRepository extends JpaRepository<CustomerPhone, String> {

    List<CustomerPhone> findByCustomerIdOrderByCreatedAtAsc(String customerId);

    List<CustomerPhone> findByCustomerIdIn(Collection<String> customerIds);

    boolean existsByBusinessIdAndPhone(String businessId, String phone);

    Optional<CustomerPhone> findFirstByBusinessIdAndPhone(String businessId, String phone);

    List<CustomerPhone> findByBusinessIdAndMaskFingerprint(String businessId, String maskFingerprint);

    void deleteByCustomerId(String customerId);
}
