package zelisline.ub.kplc.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.kplc.domain.CustomerKplcMeter;

public interface CustomerKplcMeterRepository extends JpaRepository<CustomerKplcMeter, String> {

    List<CustomerKplcMeter> findByBusinessIdAndCustomerIdOrderByLastUsedAtDesc(
            String businessId, String customerId);

    Optional<CustomerKplcMeter> findByBusinessIdAndCustomerIdAndMeterNumber(
            String businessId, String customerId, String meterNumber);

    long countByBusinessIdAndCustomerId(String businessId, String customerId);

    void deleteByBusinessIdAndCustomerIdAndMeterNumber(
            String businessId, String customerId, String meterNumber);

    List<CustomerKplcMeter> findByDepletionAlertsEnabledTrue();
}
