package zelisline.ub.trade.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.trade.domain.TradeOrderLine;

public interface TradeOrderLineRepository extends JpaRepository<TradeOrderLine, String> {

    List<TradeOrderLine> findByTradeOrderIdOrderByLineNoAsc(String tradeOrderId);

    /** The lines of several trade orders at once, for standing. */
    List<TradeOrderLine> findByTradeOrderIdIn(java.util.Collection<String> tradeOrderIds);
}
