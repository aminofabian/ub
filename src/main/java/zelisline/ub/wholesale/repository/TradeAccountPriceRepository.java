package zelisline.ub.wholesale.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.wholesale.domain.TradeAccountPrice;

public interface TradeAccountPriceRepository extends JpaRepository<TradeAccountPrice, String> {

    List<TradeAccountPrice> findByBusinessIdAndTradeAccountId(String businessId, String tradeAccountId);

    void deleteByBusinessIdAndTradeAccountId(String businessId, String tradeAccountId);
}
