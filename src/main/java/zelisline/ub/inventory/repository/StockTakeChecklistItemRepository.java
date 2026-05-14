package zelisline.ub.inventory.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.inventory.domain.StocktakeChecklistItem;
import zelisline.ub.inventory.domain.StocktakeChecklistItemId;

public interface StockTakeChecklistItemRepository
        extends JpaRepository<StocktakeChecklistItem, StocktakeChecklistItemId> {

    @Query("""
            select c.itemId from StocktakeChecklistItem c
             where c.businessId = :businessId
               and (c.sessionType = :sessionType or c.sessionType = 'both')
             order by c.sortOrder asc, c.itemId asc
            """)
    List<String> findItemIdsByBusinessIdAndSessionType(
            @Param("businessId") String businessId,
            @Param("sessionType") String sessionType
    );

    List<StocktakeChecklistItem> findByBusinessIdOrderBySortOrderAscItemIdAsc(String businessId);

    void deleteByBusinessIdAndItemId(String businessId, String itemId);

    boolean existsByBusinessIdAndItemId(String businessId, String itemId);
}
