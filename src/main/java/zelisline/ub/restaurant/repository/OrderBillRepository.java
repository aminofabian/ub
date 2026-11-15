package zelisline.ub.restaurant.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.restaurant.domain.OrderBill;

public interface OrderBillRepository extends JpaRepository<OrderBill, String> {

    Optional<OrderBill> findByIdAndBusinessId(String id, String businessId);

    List<OrderBill> findByBusinessIdAndOrderIdOrderByBillNoAsc(String businessId, String orderId);

    List<OrderBill> findByBusinessIdAndOrderIdInOrderByBillNoAsc(String businessId, Collection<String> orderIds);

    Optional<OrderBill> findFirstByBusinessIdAndSaleId(String businessId, String saleId);
}
