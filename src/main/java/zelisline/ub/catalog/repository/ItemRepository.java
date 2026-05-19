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

    Optional<Item> findByBusinessIdAndLegacyImportSourceIdAndDeletedAtIsNull(
            String businessId,
            String legacyImportSourceId
    );

    List<Item> findByIdInAndBusinessIdAndDeletedAtIsNull(Collection<String> ids, String businessId);

    boolean existsByBusinessIdAndSkuAndDeletedAtIsNull(String businessId, String sku);

    @Query("select i.sku from Item i where i.businessId = :businessId and i.deletedAt is null")
    List<String> findSkusByBusinessIdActive(@Param("businessId") String businessId);

    Optional<Item> findByBusinessIdAndSkuAndDeletedAtIsNull(String businessId, String sku);

    Optional<Item> findByBusinessIdAndBarcodeAndDeletedAtIsNull(String businessId, String barcode);

    /**
     * Published items whose name contains the query, across all businesses.
     * <p>
     * Space-insensitive: {@code "blue band"} matches {@code "Blueband"} and vice-versa.
     * The query is also stripped of spaces on the caller side for the second match clause.
     */
    @Query("""
            select i from Item i
             where i.deletedAt is null
               and i.active = true
               and i.webPublished = true
               and (lower(i.name) like lower(concat('%', :q, '%'))
                    or (:qNoSpace is not null
                        and lower(replace(i.name, ' ', '')) like lower(concat('%', :qNoSpace, '%'))))
               and i.variantOfItemId is null
             order by i.name asc
            """)
    List<Item> findPublishedByNameContaining(
            @Param("q") String q,
            @Param("qNoSpace") String qNoSpace,
            Pageable pageable);

    /** First published item matching barcode across all businesses. */
    @Query("""
            select i from Item i
             where i.barcode = :barcode
               and i.deletedAt is null
               and i.active = true
               and i.webPublished = true
             order by i.name asc
             limit 1
            """)
    Optional<Item> findFirstPublishedByBarcode(@Param("barcode") String barcode);

    boolean existsByBusinessIdAndVariantOfItemIdAndDeletedAtIsNull(String businessId, String variantOfItemId);

    List<Item> findByBusinessIdAndVariantOfItemIdAndDeletedAtIsNullOrderBySkuAsc(
            String businessId,
            String variantOfItemId
    );

    @Query("""
            select i from Item i
             left join Item p on p.id = i.variantOfItemId and p.businessId = i.businessId and p.deletedAt is null
             where i.businessId = :businessId
               and i.deletedAt is null
               and (:includeInactive = true or i.active = true)
               and (:catUnset = true or i.categoryId in :categoryIds)
               and (:noBarcode = false or i.barcode is null or i.barcode = '')
               and (:barcodeExact is null or :barcodeExact = '' or i.barcode = :barcodeExact)
               and (:includeAllScopes = true
                    or (:parentsOnly = true and i.variantOfItemId is null)
                    or (:variantsOnly = true and i.variantOfItemId is not null)
                    or (:skusOnly = true and (
                         i.variantOfItemId is not null
                         or not exists (
                           select 1 from Item ch
                           where ch.variantOfItemId = i.id
                             and ch.businessId = i.businessId
                             and ch.deletedAt is null
                         )
                       )))
               and (:q is null or :q = ''
                    or lower(i.name) like lower(concat('%', :q, '%'))
                    or lower(coalesce(i.variantName, '')) like lower(concat('%', :q, '%'))
                    or lower(i.sku) like lower(concat('%', :q, '%'))
                    or lower(coalesce(i.barcode, '')) like lower(concat('%', :q, '%'))
                    or lower(coalesce(i.description, '')) like lower(concat('%', :q, '%')))
               and (:excludeLinkedSupplierId is null
                    or not exists (
                      select 1 from SupplierProduct sp
                       inner join Supplier s on s.id = sp.supplierId
                       where sp.itemId = i.id
                         and sp.supplierId = :excludeLinkedSupplierId
                         and s.businessId = i.businessId
                         and sp.deletedAt is null
                    ))
               and ((:squashParentGroupsForSearch = false)
                    or (i.variantOfItemId is not null
                        or not exists (
                          select 1 from Item ch
                          where ch.variantOfItemId = i.id
                            and ch.businessId = i.businessId
                            and ch.deletedAt is null
                        )))
               and (:itemTypeUnset = true or i.itemTypeId = :itemTypeId)
            """)
    Page<Item> search(
            @Param("businessId") String businessId,
            @Param("q") String q,
            @Param("barcodeExact") String barcodeExact,
            @Param("catUnset") boolean catUnset,
            @Param("categoryIds") Collection<String> categoryIds,
            @Param("noBarcode") boolean noBarcode,
            @Param("includeInactive") boolean includeInactive,
            @Param("includeAllScopes") boolean includeAllScopes,
            @Param("parentsOnly") boolean parentsOnly,
            @Param("variantsOnly") boolean variantsOnly,
            @Param("skusOnly") boolean skusOnly,
            @Param("excludeLinkedSupplierId") String excludeLinkedSupplierId,
            @Param("squashParentGroupsForSearch") boolean squashParentGroupsForSearch,
            @Param("itemTypeUnset") boolean itemTypeUnset,
            @Param("itemTypeId") String itemTypeId,
            Pageable pageable
    );

    @Query("""
            select distinct i.variantOfItemId from Item i
             where i.businessId = :businessId
               and i.deletedAt is null
               and i.variantOfItemId in :parentIds
            """)
    List<String> findParentIdsHavingVariants(
            @Param("businessId") String businessId,
            @Param("parentIds") Collection<String> parentIds
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

    boolean existsByBusinessIdAndItemTypeIdAndDeletedAtIsNull(String businessId, String itemTypeId);

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
               and ((:q is null or :q = '')
                    or i.variantOfItemId is not null
                    or not exists (
                      select 1 from Item ch
                      where ch.variantOfItemId = i.id
                        and ch.businessId = i.businessId
                        and ch.deletedAt is null
                    ))
               and (:cursor is null or :cursor = '' or i.id > :cursor)
               and (select coalesce(sum(b.quantityRemaining), 0)
                      from InventoryBatch b
                     where b.itemId = i.id
                       and b.businessId = i.businessId
                       and b.branchId = :catalogBranchId
                       and b.status = 'active') > 0
             order by i.id asc
            """)
    Slice<Item> searchStorefrontCatalog(
            @Param("businessId") String businessId,
            @Param("q") String q,
            @Param("catUnset") boolean catUnset,
            @Param("categoryIds") Collection<String> categoryIds,
            @Param("cursor") String cursor,
            @Param("catalogBranchId") String catalogBranchId,
            Pageable pageable
    );

    @Query("""
            select count(i) from Item i
             where i.businessId = :businessId
               and i.deletedAt is null
               and i.active = true
               and i.webPublished = true
               and (:catUnset = true or i.categoryId in :categoryIds)
               and (:q is null or :q = ''
                    or lower(i.name) like lower(concat('%', :q, '%'))
                    or lower(coalesce(i.variantName, '')) like lower(concat('%', :q, '%'))
                    or lower(coalesce(i.description, '')) like lower(concat('%', :q, '%')))
               and ((:q is null or :q = '')
                    or i.variantOfItemId is not null
                    or not exists (
                      select 1 from Item ch
                      where ch.variantOfItemId = i.id
                        and ch.businessId = i.businessId
                        and ch.deletedAt is null
                    ))
               and (:cursor is null or :cursor = '' or i.id > :cursor)
               and (select coalesce(sum(b.quantityRemaining), 0)
                      from InventoryBatch b
                     where b.itemId = i.id
                       and b.businessId = i.businessId
                       and b.branchId = :catalogBranchId
                       and b.status = 'active') > 0
            """)
    Long countStorefrontCatalog(
            @Param("businessId") String businessId,
            @Param("q") String q,
            @Param("catUnset") boolean catUnset,
            @Param("categoryIds") Collection<String> categoryIds,
            @Param("cursor") String cursor,
            @Param("catalogBranchId") String catalogBranchId
    );

    @Query("""
            select i.categoryId, count(i) from Item i
             where i.businessId = :businessId
               and i.deletedAt is null
               and i.active = true
               and i.webPublished = true
               and i.categoryId is not null
               and i.categoryId <> ''
               and (select coalesce(sum(b.quantityRemaining), 0)
                      from InventoryBatch b
                     where b.itemId = i.id
                       and b.businessId = i.businessId
                       and b.branchId = :catalogBranchId
                       and b.status = 'active') > 0
             group by i.categoryId
            """)
    List<Object[]> countStorefrontItemsByCategory(
            @Param("businessId") String businessId,
            @Param("catalogBranchId") String catalogBranchId
    );
}
