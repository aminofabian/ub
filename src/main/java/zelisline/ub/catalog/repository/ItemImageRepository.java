package zelisline.ub.catalog.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.catalog.domain.ItemImage;

public interface ItemImageRepository extends JpaRepository<ItemImage, String> {

    List<ItemImage> findByItemIdIn(Collection<String> itemIds, Sort sort);

    List<ItemImage> findByItemIdOrderBySortOrderAscIdAsc(String itemId);

    Optional<ItemImage> findByIdAndItemId(String id, String itemId);

    @Query("SELECT COALESCE(MAX(i.sortOrder), -1) FROM ItemImage i WHERE i.itemId = :itemId")
    int maxSortOrderForItem(@Param("itemId") String itemId);
}
