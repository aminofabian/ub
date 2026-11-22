package zelisline.ub.pricing.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.pricing.domain.ItemPriceBreak;

public interface ItemPriceBreakRepository extends JpaRepository<ItemPriceBreak, String> {

    List<ItemPriceBreak> findByBusinessIdAndItemIdOrderByMinQtyAsc(String businessId, String itemId);

    List<ItemPriceBreak> findByBusinessIdAndItemIdInOrderByMinQtyAsc(String businessId, Collection<String> itemIds);

    void deleteByBusinessIdAndItemId(String businessId, String itemId);
}
