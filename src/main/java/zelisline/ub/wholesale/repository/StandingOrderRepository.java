package zelisline.ub.wholesale.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.wholesale.domain.StandingOrder;

public interface StandingOrderRepository extends JpaRepository<StandingOrder, String> {

    List<StandingOrder> findTop200ByActiveTrueAndNextRunOnLessThanEqualOrderByNextRunOnAsc(LocalDate day);

    List<StandingOrder> findBySupplierBusinessIdAndActiveTrueOrderByNextRunOnAsc(String supplierBusinessId);

    List<StandingOrder> findByBuyerBusinessIdAndActiveTrueOrderByNextRunOnAsc(String buyerBusinessId);

    Optional<StandingOrder> findByIdAndBuyerBusinessId(String id, String buyerBusinessId);

    Optional<StandingOrder> findByIdAndSupplierBusinessId(String id, String supplierBusinessId);

    long countBySupplierBusinessIdAndBuyerBusinessIdAndActiveTrue(String supplierBusinessId, String buyerBusinessId);
}
