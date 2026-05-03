package zelisline.ub.catalog.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.catalog.domain.Item;

public interface ItemRepository extends JpaRepository<Item, String> {

    Optional<Item> findByIdAndBusinessIdAndDeletedAtIsNull(String id, String businessId);

    List<Item> findByIdInAndBusinessIdAndDeletedAtIsNull(Collection<String> ids, String businessId);

    boolean existsByBusinessIdAndSkuAndDeletedAtIsNull(String businessId, String sku);

    Optional<Item> findByBusinessIdAndBarcodeAndDeletedAtIsNull(String businessId, String barcode);

    boolean existsByBusinessIdAndVariantOfItemIdAndDeletedAtIsNull(String businessId, String variantOfItemId);

    List<Item> findByBusinessIdAndVariantOfItemIdAndDeletedAtIsNullOrderBySkuAsc(
            String businessId,
            String variantOfItemId
    );

    @Query("""
            select i from Item i
             where i.businessId = :businessId
               and i.deletedAt is null
               and (:includeInactive = true or i.active = true)
               and (:catUnset = true or i.categoryId in :categoryIds)
               and (:noBarcode = false or i.barcode is null or i.barcode = '')
               and (:barcodeExact is null or :barcodeExact = '' or i.barcode = :barcodeExact)
               and (:q is null or :q = ''
                    or lower(i.name) like lower(concat('%', :q, '%'))
                    or lower(coalesce(i.variantName, '')) like lower(concat('%', :q, '%'))
                    or lower(i.sku) like lower(concat('%', :q, '%'))
                    or lower(coalesce(i.barcode, '')) like lower(concat('%', :q, '%'))
                    or lower(coalesce(i.description, '')) like lower(concat('%', :q, '%')))
            """)
    Page<Item> search(
            @Param("businessId") String businessId,
            @Param("q") String q,
            @Param("barcodeExact") String barcodeExact,
            @Param("catUnset") boolean catUnset,
            @Param("categoryIds") Collection<String> categoryIds,
            @Param("noBarcode") boolean noBarcode,
            @Param("includeInactive") boolean includeInactive,
            Pageable pageable
    );

    @Query("""
            select i.id from Item i
             where i.businessId = :businessId
               and i.stocked = true
               and i.deletedAt is null
             order by i.id asc
            """)
    List<String> findStockedItemIdsByBusinessId(@Param("businessId") String businessId);

    @Query("""
            select distinct i.categoryId from Item i
             where i.businessId = :businessId
               and i.deletedAt is null
               and i.active = true
               and i.webPublished = true
               and i.categoryId is not null
               and i.categoryId <> ''
            """)
    List<String> findDistinctWebPublishedCategoryIds(@Param("businessId") String businessId);

    @Query("""
            select i from Item i
             where i.businessId = :businessId
               and i.deletedAt is null
               and i.active = true
               and i.webPublished = true
               and (:catUnset = true or i.categoryId in :categoryIds)
               and (:q is null or :q = ''
                    or lower(i.name) like lower(concat('%', :q, '%'))
                    or lower(coalesce(i.variantName, '')) like lower(concat('%', :q, '%'))
                    or lower(coalesce(i.description, '')) like lower(concat('%', :q, '%')))
               and (:cursor is null or :cursor = '' or i.id > :cursor)
             order by i.id asc
            """)
    Slice<Item> searchStorefrontCatalog(
            @Param("businessId") String businessId,
            @Param("q") String q,
            @Param("catUnset") boolean catUnset,
            @Param("categoryIds") Collection<String> categoryIds,
            @Param("cursor") String cursor,
            Pageable pageable
    );
}
