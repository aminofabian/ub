package zelisline.ub.credits.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.credits.domain.CustomerPhone;

public interface CustomerPhoneRepository extends JpaRepository<CustomerPhone, String> {

    List<CustomerPhone> findByCustomerIdOrderByCreatedAtAsc(String customerId);

    List<CustomerPhone> findByCustomerIdIn(Collection<String> customerIds);

    boolean existsByBusinessIdAndPhone(String businessId, String phone);

    void deleteByCustomerId(String customerId);
}
