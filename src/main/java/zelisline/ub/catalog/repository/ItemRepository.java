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

import zelisline.ub.catalog.api.dto.CatalogRowTypeSum;
import zelisline.ub.catalog.domain.Item;

public interface ItemRepository extends JpaRepository<Item, String> {

    Optional<Item> findByIdAndBusinessIdAndDeletedAtIsNull(String id, String businessId);

    List<Item> findByBusinessIdAndDeletedAtIsNull(String businessId);

    List<Item> findByGlobalProductSourceIdAndDeletedAtIsNull(String globalProductSourceId);

    List<Item> findByBusinessIdAndGlobalProductSourceIdInAndDeletedAtIsNull(
            String businessId,
            Collection<String> globalProductSourceIds
    );

    Optional<Item> findByBusinessIdAndLegacyImportSourceIdAndDeletedAtIsNull(
            String businessId,
            String legacyImportSourceId
    );

    List<Item> findByIdInAndBusinessIdAndDeletedAtIsNull(Collection<String> ids, String businessId);

    @Query("""
            select i from Item i
             where i.businessId = :businessId
               and i.deletedAt is null
               and (:q is null or :q = ''
                    or lower(i.name) like lower(concat('%', :q, '%'))
                    or lower(coalesce(i.brand, '')) like lower(concat('%', :q, '%'))
                    or lower(coalesce(i.barcode, '')) like lower(concat('%', :q, '%'))
                    or lower(i.sku) like lower(concat('%', :q, '%')))
            """)
    Page<Item> searchActiveByBusiness(
            @Param("businessId") String businessId,
            @Param("q") String q,
            Pageable pageable);

    boolean existsByBusinessIdAndSkuAndDeletedAtIsNull(String businessId, String sku);

    /** Includes soft-deleted rows — SKU unique key is business-wide regardless of deleted_at. */
    boolean existsByBusinessIdAndSku(String businessId, String sku);

    @Query("select i.sku from Item i where i.businessId = :businessId and i.deletedAt is null")
    List<String> findSkusByBusinessIdActive(@Param("businessId") String businessId);

    /** All SKUs for a business (including soft-deleted) for sequence allocation. */
    @Query("select i.sku from Item i where i.businessId = :businessId")
    List<String> findSkusByBusinessIdAll(@Param("businessId") String businessId);

    Optional<Item> findByBusinessIdAndSkuAndDeletedAtIsNull(String businessId, String sku);

    Optional<Item> findByBusinessIdAndBarcodeAndDeletedAtIsNull(String businessId, String barcode);

    Optional<Item> findByBusinessIdAndPluCodeAndDeletedAtIsNull(String businessId, String pluCode);

    /** First published item matching scale PLU across all businesses. */
    @Query("""
            select i from Item i
             where i.pluCode = :pluCode
               and i.deletedAt is null
               and i.active = true
               and i.webPublished = true
             order by i.name asc
             limit 1
            """)
    Optional<Item> findFirstPublishedByPluCode(@Param("pluCode") String pluCode);

    /**
     * Published items whose name or variant name contains the query, across all businesses.
     * <p>
     * Compact-insensitive via {@code qCompact}: {@code "cocacola"} matches {@code "Coca-Cola"}
     * / {@code "Coca Cola"}, and {@code "blue band"} matches {@code "Blueband"}.
     * <p>
     * Returns standalone items and variants, but <strong>not</strong> parent items that have
     * variant children — parents are labels, not scannable products.
     */
    @Query("""
            select i from Item i
             where i.deletedAt is null
               and i.active = true
               and i.webPublished = true
               and (lower(i.name) like lower(concat('%', :q, '%'))
                    or lower(coalesce(i.variantName, '')) like lower(concat('%', :q, '%'))
                    or (:qCompact is not null
                        and (lower(replace(replace(i.name, ' ', ''), '-', ''))
                                 like lower(concat('%', :qCompact, '%'))
                             or lower(replace(replace(coalesce(i.variantName, ''), ' ', ''), '-', ''))
                                 like lower(concat('%', :qCompact, '%')))))
               and (i.variantOfItemId is not null
                    or not exists (
                      select 1 from Item ch
                      where ch.variantOfItemId = i.id
                        and ch.businessId = i.businessId
                        and ch.deletedAt is null
                    ))
             order by i.name asc
            """)
    List<Item> findPublishedByNameContaining(
            @Param("q") String q,
            @Param("qCompact") String qCompact,
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
               and (:inactiveOnly = false or i.active = false)
               and (:inactiveOnly = true or :includeInactive = true or i.active = true)
               and (:catUnset = true or i.categoryId in :categoryIds)
               and (:noBarcode = false or i.barcode is null or trim(i.barcode) = '')
               and (:barcodeExact is null or :barcodeExact = '' or i.barcode = :barcodeExact)
               and (:includeAllScopes = true
                    or (:parentsOnly = true and i.variantOfItemId is null
                        and exists (
                          select 1 from Item ch
                          where ch.variantOfItemId = i.id
                            and ch.businessId = i.businessId
                            and ch.deletedAt is null
                        ))
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
               and (:filterByCatalogRowTypes = false
                    or (:includeParentRows = true and i.variantOfItemId is null
                        and exists (
                          select 1 from Item chp
                          where chp.variantOfItemId = i.id
                            and chp.businessId = i.businessId
                            and chp.deletedAt is null
                        ))
                    or (:includeVariantRows = true and i.variantOfItemId is not null)
                    or (:includeStandaloneRows = true and i.variantOfItemId is null
                        and not exists (
                          select 1 from Item chs
                          where chs.variantOfItemId = i.id
                            and chs.businessId = i.businessId
                            and chs.deletedAt is null
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
                        or i.sellable = true
                        or i.stocked = true
                        or not exists (
                          select 1 from Item ch
                          where ch.variantOfItemId = i.id
                            and ch.businessId = i.businessId
                            and ch.deletedAt is null
                        )))
               and (:itemTypeUnset = true or i.itemTypeId = :itemTypeId)
               and (:restrictByAllowedItemTypes = false or i.itemTypeId in :allowedItemTypeIds)
               and (:isWeighedUnset = true or i.weighed = :isWeighed)
               and (:filterNoPrice = false or (
                    i.sellable = true
                    and (i.bundlePrice is null or i.bundlePrice <= 0)
                    and not exists (
                      select 1 from SellingPrice sp
                       where sp.itemId = i.id
                         and sp.businessId = i.businessId
                         and sp.effectiveTo is null
                         and sp.price > 0
                    )))
               and (:restrictItemIdsUnset = true or i.id in :restrictItemIds)
            """)
    Page<Item> search(
            @Param("businessId") String businessId,
            @Param("q") String q,
            @Param("barcodeExact") String barcodeExact,
            @Param("catUnset") boolean catUnset,
            @Param("categoryIds") Collection<String> categoryIds,
            @Param("noBarcode") boolean noBarcode,
            @Param("includeInactive") boolean includeInactive,
            @Param("inactiveOnly") boolean inactiveOnly,
            @Param("includeAllScopes") boolean includeAllScopes,
            @Param("parentsOnly") boolean parentsOnly,
            @Param("variantsOnly") boolean variantsOnly,
            @Param("skusOnly") boolean skusOnly,
            @Param("filterByCatalogRowTypes") boolean filterByCatalogRowTypes,
            @Param("includeParentRows") boolean includeParentRows,
            @Param("includeVariantRows") boolean includeVariantRows,
            @Param("includeStandaloneRows") boolean includeStandaloneRows,
            @Param("excludeLinkedSupplierId") String excludeLinkedSupplierId,
            @Param("squashParentGroupsForSearch") boolean squashParentGroupsForSearch,
            @Param("itemTypeUnset") boolean itemTypeUnset,
            @Param("itemTypeId") String itemTypeId,
            @Param("restrictByAllowedItemTypes") boolean restrictByAllowedItemTypes,
            @Param("allowedItemTypeIds") Collection<String> allowedItemTypeIds,
            @Param("filterNoPrice") boolean filterNoPrice,
            @Param("restrictItemIdsUnset") boolean restrictItemIdsUnset,
            @Param("restrictItemIds") Collection<String> restrictItemIds,
            @Param("isWeighedUnset") boolean isWeighedUnset,
            @Param("isWeighed") boolean isWeighed,
            Pageable pageable
    );

    @Query("""
            select new zelisline.ub.catalog.api.dto.CatalogRowTypeSum(
              coalesce(sum(case when i.variantOfItemId is null and exists (
                    select 1 from Item ch
                    where ch.variantOfItemId = i.id
                      and ch.businessId = i.businessId
                      and ch.deletedAt is null
                  ) then 1 else 0 end), 0L),
              coalesce(sum(case when i.variantOfItemId is not null then 1 else 0 end), 0L),
              coalesce(sum(case when i.variantOfItemId is null and not exists (
                    select 1 from Item ch
                    where ch.variantOfItemId = i.id
                      and ch.businessId = i.businessId
                      and ch.deletedAt is null
                  ) then 1 else 0 end), 0L)
            )
            from Item i
             where i.businessId = :businessId
               and i.deletedAt is null
               and (:inactiveOnly = false or i.active = false)
               and (:inactiveOnly = true or :includeInactive = true or i.active = true)
               and (:catUnset = true or i.categoryId in :categoryIds)
               and (:noBarcode = false or i.barcode is null or trim(i.barcode) = '')
               and (:barcodeExact is null or :barcodeExact = '' or i.barcode = :barcodeExact)
               and (:includeAllScopes = true
                    or (:parentsOnly = true and i.variantOfItemId is null
                        and exists (
                          select 1 from Item ch
                          where ch.variantOfItemId = i.id
                            and ch.businessId = i.businessId
                            and ch.deletedAt is null
                        ))
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
               and (:itemTypeUnset = true or i.itemTypeId = :itemTypeId)
               and (:restrictByAllowedItemTypes = false or i.itemTypeId in :allowedItemTypeIds)
            """)
    CatalogRowTypeSum sumCatalogRowTypes(
            @Param("businessId") String businessId,
            @Param("q") String q,
            @Param("barcodeExact") String barcodeExact,
            @Param("catUnset") boolean catUnset,
            @Param("categoryIds") Collection<String> categoryIds,
            @Param("noBarcode") boolean noBarcode,
            @Param("includeInactive") boolean includeInactive,
            @Param("inactiveOnly") boolean inactiveOnly,
            @Param("includeAllScopes") boolean includeAllScopes,
            @Param("parentsOnly") boolean parentsOnly,
            @Param("variantsOnly") boolean variantsOnly,
            @Param("skusOnly") boolean skusOnly,
            @Param("excludeLinkedSupplierId") String excludeLinkedSupplierId,
            @Param("itemTypeUnset") boolean itemTypeUnset,
            @Param("itemTypeId") String itemTypeId,
            @Param("restrictByAllowedItemTypes") boolean restrictByAllowedItemTypes,
            @Param("allowedItemTypeIds") Collection<String> allowedItemTypeIds
    );

    @Query("""
            select count(i) from Item i
             where i.businessId = :businessId
               and i.deletedAt is null
               and (:inactiveOnly = false or i.active = false)
               and (:inactiveOnly = true or :includeInactive = true or i.active = true)
               and (:catUnset = true or i.categoryId in :categoryIds)
               and (:noBarcode = false or i.barcode is null or trim(i.barcode) = '')
               and (:barcodeExact is null or :barcodeExact = '' or i.barcode = :barcodeExact)
               and (:includeAllScopes = true
                    or (:parentsOnly = true and i.variantOfItemId is null
                        and exists (
                          select 1 from Item ch
                          where ch.variantOfItemId = i.id
                            and ch.businessId = i.businessId
                            and ch.deletedAt is null
                        ))
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
               and (:itemTypeUnset = true or i.itemTypeId = :itemTypeId)
               and (:restrictByAllowedItemTypes = false or i.itemTypeId in :allowedItemTypeIds)
               and (i.barcode is null or trim(i.barcode) = '')
            """)
    long countCatalogMissingBarcodes(
            @Param("businessId") String businessId,
            @Param("q") String q,
            @Param("barcodeExact") String barcodeExact,
            @Param("catUnset") boolean catUnset,
            @Param("categoryIds") Collection<String> categoryIds,
            @Param("noBarcode") boolean noBarcode,
            @Param("includeInactive") boolean includeInactive,
            @Param("inactiveOnly") boolean inactiveOnly,
            @Param("includeAllScopes") boolean includeAllScopes,
            @Param("parentsOnly") boolean parentsOnly,
            @Param("variantsOnly") boolean variantsOnly,
            @Param("skusOnly") boolean skusOnly,
            @Param("excludeLinkedSupplierId") String excludeLinkedSupplierId,
            @Param("itemTypeUnset") boolean itemTypeUnset,
            @Param("itemTypeId") String itemTypeId,
            @Param("restrictByAllowedItemTypes") boolean restrictByAllowedItemTypes,
            @Param("allowedItemTypeIds") Collection<String> allowedItemTypeIds
    );

    @Query("""
            select count(i) from Item i
             where i.businessId = :businessId
               and i.deletedAt is null
               and (:catUnset = true or i.categoryId in :categoryIds)
               and (:barcodeExact is null or :barcodeExact = '' or i.barcode = :barcodeExact)
               and (:includeAllScopes = true
                    or (:parentsOnly = true and i.variantOfItemId is null
                        and exists (
                          select 1 from Item ch
                          where ch.variantOfItemId = i.id
                            and ch.businessId = i.businessId
                            and ch.deletedAt is null
                        ))
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
               and (:itemTypeUnset = true or i.itemTypeId = :itemTypeId)
               and (:restrictByAllowedItemTypes = false or i.itemTypeId in :allowedItemTypeIds)
               and i.active = false
            """)
    long countCatalogInactive(
            @Param("businessId") String businessId,
            @Param("q") String q,
            @Param("barcodeExact") String barcodeExact,
            @Param("catUnset") boolean catUnset,
            @Param("categoryIds") Collection<String> categoryIds,
            @Param("includeAllScopes") boolean includeAllScopes,
            @Param("parentsOnly") boolean parentsOnly,
            @Param("variantsOnly") boolean variantsOnly,
            @Param("skusOnly") boolean skusOnly,
            @Param("excludeLinkedSupplierId") String excludeLinkedSupplierId,
            @Param("itemTypeUnset") boolean itemTypeUnset,
            @Param("itemTypeId") String itemTypeId,
            @Param("restrictByAllowedItemTypes") boolean restrictByAllowedItemTypes,
            @Param("allowedItemTypeIds") Collection<String> allowedItemTypeIds
    );

    @Query("""
            select count(i) from Item i
             where i.businessId = :businessId
               and i.deletedAt is null
               and (:inactiveOnly = false or i.active = false)
               and (:inactiveOnly = true or :includeInactive = true or i.active = true)
               and (:catUnset = true or i.categoryId in :categoryIds)
               and (:noBarcode = false or i.barcode is null or trim(i.barcode) = '')
               and (:barcodeExact is null or :barcodeExact = '' or i.barcode = :barcodeExact)
               and (:includeAllScopes = true
                    or (:parentsOnly = true and i.variantOfItemId is null
                        and exists (
                          select 1 from Item ch
                          where ch.variantOfItemId = i.id
                            and ch.businessId = i.businessId
                            and ch.deletedAt is null
                        ))
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
               and (:itemTypeUnset = true or i.itemTypeId = :itemTypeId)
               and (:restrictByAllowedItemTypes = false or i.itemTypeId in :allowedItemTypeIds)
               and i.sellable = true
               and (i.bundlePrice is null or i.bundlePrice <= 0)
               and not exists (
                 select 1 from SellingPrice sp
                  where sp.itemId = i.id
                    and sp.businessId = i.businessId
                    and sp.effectiveTo is null
                    and sp.price > 0
               )
            """)
    long countCatalogMissingPrices(
            @Param("businessId") String businessId,
            @Param("q") String q,
            @Param("barcodeExact") String barcodeExact,
            @Param("catUnset") boolean catUnset,
            @Param("categoryIds") Collection<String> categoryIds,
            @Param("noBarcode") boolean noBarcode,
            @Param("includeInactive") boolean includeInactive,
            @Param("inactiveOnly") boolean inactiveOnly,
            @Param("includeAllScopes") boolean includeAllScopes,
            @Param("parentsOnly") boolean parentsOnly,
            @Param("variantsOnly") boolean variantsOnly,
            @Param("skusOnly") boolean skusOnly,
            @Param("excludeLinkedSupplierId") String excludeLinkedSupplierId,
            @Param("itemTypeUnset") boolean itemTypeUnset,
            @Param("itemTypeId") String itemTypeId,
            @Param("restrictByAllowedItemTypes") boolean restrictByAllowedItemTypes,
            @Param("allowedItemTypeIds") Collection<String> allowedItemTypeIds
    );

    @Query("""
            select i.id from Item i
             where i.businessId = :businessId
               and i.deletedAt is null
               and (:inactiveOnly = false or i.active = false)
               and (:inactiveOnly = true or i.active = true)
               and i.stocked = true
               and i.sellable = true
               and (:catUnset = true or i.categoryId in :categoryIds)
               and (:noBarcode = false or i.barcode is null or trim(i.barcode) = '')
               and (:filterNoPrice = false or (
                    i.sellable = true
                    and (i.bundlePrice is null or i.bundlePrice <= 0)
                    and not exists (
                      select 1 from SellingPrice sp
                       where sp.itemId = i.id
                         and sp.businessId = i.businessId
                         and sp.effectiveTo is null
                         and sp.price > 0
                    )))
               and (:barcodeExact is null or :barcodeExact = '' or i.barcode = :barcodeExact)
               and (:includeAllScopes = true
                    or (:parentsOnly = true and i.variantOfItemId is null
                        and exists (
                          select 1 from Item ch
                          where ch.variantOfItemId = i.id
                            and ch.businessId = i.businessId
                            and ch.deletedAt is null
                        ))
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
               and (:filterByCatalogRowTypes = false
                    or (:includeParentRows = true and i.variantOfItemId is null
                        and exists (
                          select 1 from Item chp
                          where chp.variantOfItemId = i.id
                            and chp.businessId = i.businessId
                            and chp.deletedAt is null
                        ))
                    or (:includeVariantRows = true and i.variantOfItemId is not null)
                    or (:includeStandaloneRows = true and i.variantOfItemId is null
                        and not exists (
                          select 1 from Item chs
                          where chs.variantOfItemId = i.id
                            and chs.businessId = i.businessId
                            and chs.deletedAt is null
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
                        or i.sellable = true
                        or i.stocked = true
                        or not exists (
                          select 1 from Item ch
                          where ch.variantOfItemId = i.id
                            and ch.businessId = i.businessId
                            and ch.deletedAt is null
                        )))
               and (:itemTypeUnset = true or i.itemTypeId = :itemTypeId)
               and (:restrictByAllowedItemTypes = false or i.itemTypeId in :allowedItemTypeIds)
            """)
    List<String> findCatalogStockAttentionItemIds(
            @Param("businessId") String businessId,
            @Param("q") String q,
            @Param("barcodeExact") String barcodeExact,
            @Param("catUnset") boolean catUnset,
            @Param("categoryIds") Collection<String> categoryIds,
            @Param("noBarcode") boolean noBarcode,
            @Param("filterNoPrice") boolean filterNoPrice,
            @Param("inactiveOnly") boolean inactiveOnly,
            @Param("includeAllScopes") boolean includeAllScopes,
            @Param("parentsOnly") boolean parentsOnly,
            @Param("variantsOnly") boolean variantsOnly,
            @Param("skusOnly") boolean skusOnly,
            @Param("filterByCatalogRowTypes") boolean filterByCatalogRowTypes,
            @Param("includeParentRows") boolean includeParentRows,
            @Param("includeVariantRows") boolean includeVariantRows,
            @Param("includeStandaloneRows") boolean includeStandaloneRows,
            @Param("excludeLinkedSupplierId") String excludeLinkedSupplierId,
            @Param("squashParentGroupsForSearch") boolean squashParentGroupsForSearch,
            @Param("itemTypeUnset") boolean itemTypeUnset,
            @Param("itemTypeId") String itemTypeId,
            @Param("restrictByAllowedItemTypes") boolean restrictByAllowedItemTypes,
            @Param("allowedItemTypeIds") Collection<String> allowedItemTypeIds
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

    @Query("""
            select distinct i.itemTypeId from Item i
             where i.businessId = :businessId
               and i.deletedAt is null
               and i.active = true
               and i.webPublished = true
               and i.itemTypeId is not null
               and i.itemTypeId <> ''
            """)
    List<String> findDistinctWebPublishedItemTypeIds(@Param("businessId") String businessId);

    boolean existsByBusinessIdAndItemTypeIdAndDeletedAtIsNull(String businessId, String itemTypeId);

    @Query("""
            select i from Item i
             where i.businessId = :businessId
               and i.deletedAt is null
               and i.active = true
               and i.webPublished = true
               and (:catUnset = true or i.categoryId in :categoryIds)
               and (:deptUnset = true or i.itemTypeId = :departmentId)
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
             order by i.id asc
            """)
    Slice<Item> searchStorefrontCatalog(
            @Param("businessId") String businessId,
            @Param("q") String q,
            @Param("catUnset") boolean catUnset,
            @Param("categoryIds") Collection<String> categoryIds,
            @Param("deptUnset") boolean deptUnset,
            @Param("departmentId") String departmentId,
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
               and (:deptUnset = true or i.itemTypeId = :departmentId)
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
            """)
    Long countStorefrontCatalog(
            @Param("businessId") String businessId,
            @Param("q") String q,
            @Param("catUnset") boolean catUnset,
            @Param("categoryIds") Collection<String> categoryIds,
            @Param("deptUnset") boolean deptUnset,
            @Param("departmentId") String departmentId,
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
             group by i.categoryId
            """)
    List<Object[]> countStorefrontItemsByCategory(
            @Param("businessId") String businessId,
            @Param("catalogBranchId") String catalogBranchId
    );
}
