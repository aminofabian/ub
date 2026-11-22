package zelisline.ub.trade.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.trade.domain.TradeOrder;

public interface TradeOrderRepository extends JpaRepository<TradeOrder, String> {

    Optional<TradeOrder> findBySalesOrderId(String salesOrderId);

    Optional<TradeOrder> findByIdAndBuyerBusinessId(String id, String buyerBusinessId);

    List<TradeOrder> findTop200ByBuyerBusinessIdOrderByCreatedAtDesc(String buyerBusinessId);

    List<TradeOrder> findTop200ByBuyerBusinessIdAndOrderStateOrderByCreatedAtDesc(
            String buyerBusinessId, String orderState);

    long countBySupplierBusinessIdAndBuyerBusinessIdAndOrderState(
            String supplierBusinessId, String buyerBusinessId, String orderState);

    /** The supplier's trade orders that came from the given sales orders. */
    java.util.List<zelisline.ub.trade.domain.TradeOrder> findBySupplierBusinessIdAndSalesOrderIdIn(
            String supplierBusinessId, java.util.Collection<String> salesOrderIds);
}
