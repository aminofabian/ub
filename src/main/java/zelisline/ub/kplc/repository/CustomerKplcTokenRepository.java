package zelisline.ub.kplc.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.kplc.domain.CustomerKplcToken;

public interface CustomerKplcTokenRepository extends JpaRepository<CustomerKplcToken, String> {

    Optional<CustomerKplcToken> findByBusinessIdAndCustomerIdAndTokenNo(
            String businessId, String customerId, String tokenNo);

    List<CustomerKplcToken> findByBusinessIdAndCustomerIdAndMeterNumberOrderByPurchasedAtDesc(
            String businessId, String customerId, String meterNumber);

    Optional<CustomerKplcToken> findFirstByBusinessIdAndCustomerIdAndMeterNumberAndPurchasedAtAndAmount(
            String businessId,
            String customerId,
            String meterNumber,
            Instant purchasedAt,
            BigDecimal amount);

    Optional<CustomerKplcToken> findFirstByBusinessIdAndCustomerIdAndMeterNumberAndPurchasedAt(
            String businessId, String customerId, String meterNumber, Instant purchasedAt);
}
