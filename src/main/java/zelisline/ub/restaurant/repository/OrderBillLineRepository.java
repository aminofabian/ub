package zelisline.ub.restaurant.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.restaurant.domain.OrderBillLine;

public interface OrderBillLineRepository extends JpaRepository<OrderBillLine, String> {

    List<OrderBillLine> findByBusinessIdAndOrderId(String businessId, String orderId);

    List<OrderBillLine> findByBusinessIdAndOrderIdIn(String businessId, Collection<String> orderIds);

    List<OrderBillLine> findByBusinessIdAndBillId(String businessId, String billId);

    void deleteByBusinessIdAndBillId(String businessId, String billId);
}
