package zelisline.ub.trade.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.trade.domain.TradeOrderEvent;

public interface TradeOrderEventRepository extends JpaRepository<TradeOrderEvent, String> {

    List<TradeOrderEvent> findByTradeOrderIdOrderByEventNoAsc(String tradeOrderId);
}
