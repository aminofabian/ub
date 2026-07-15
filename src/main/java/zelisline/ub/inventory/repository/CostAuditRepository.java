package zelisline.ub.inventory.repository;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import zelisline.ub.catalog.domain.Item;

/**
 * Read-only detection query for the cost-audit tool: finds sellable items whose effective
 * cost is missing/zero, at or above the sell price (would sell at a loss), or leaves a very
 * thin margin.
 *
 * <p>Effective cost = weighted-average of active on-hand batch unit costs (what drives COGS),
 * falling back to {@code items.buying_price}. Effective sell price = open branch selling price
 * → open business-wide selling price → {@code items.bundle_price} (matches
 * {@code PricingService#getCurrentOpenSellingPrice}).</p>
 */
public interface CostAuditRepository extends Repository<Item, String> {

    interface CostIssueRow {
        String getItemId();
        String getName();
        String getSku();
        String getUnitType();
        BigDecimal getCurrentStock();
        BigDecimal getBuyingPrice();
        BigDecimal getBundlePrice();
        BigDecimal getActiveQty();
        Long getActiveBatchCount();
        BigDecimal getBatchWac();
        BigDecimal getEffectiveCost();
        BigDecimal getSellPrice();
    }

    @Query(value = """
            select t.itemId            as itemId,
                   t.name              as name,
                   t.sku               as sku,
                   t.unitType          as unitType,
                   t.currentStock      as currentStock,
                   t.buyingPrice       as buyingPrice,
                   t.bundlePrice       as bundlePrice,
                   t.activeQty         as activeQty,
                   t.activeBatchCount  as activeBatchCount,
                   t.batchWac          as batchWac,
                   t.effectiveCost     as effectiveCost,
                   t.sellPrice         as sellPrice
              from (
                select
                    i.id            as itemId,
                    i.name          as name,
                    i.sku           as sku,
                    i.unit_type     as unitType,
                    i.current_stock as currentStock,
                    i.buying_price  as buyingPrice,
                    i.bundle_price  as bundlePrice,
                    coalesce(agg.active_qty, 0)   as activeQty,
                    coalesce(agg.batch_count, 0)  as activeBatchCount,
                    agg.wac                        as batchWac,
                    coalesce(agg.wac, i.buying_price) as effectiveCost,
                    coalesce(
                      (select sp.price from selling_prices sp
                        where sp.business_id = i.business_id and sp.item_id = i.id
                          and :branchId is not null and sp.branch_id = :branchId
                          and sp.effective_to is null
                        order by sp.effective_from desc limit 1),
                      (select sp.price from selling_prices sp
                        where sp.business_id = i.business_id and sp.item_id = i.id
                          and sp.branch_id is null and sp.effective_to is null
                        order by sp.effective_from desc limit 1),
                      i.bundle_price
                    ) as sellPrice
                  from items i
                  left join (
                    select ib.item_id as item_id,
                           sum(ib.quantity_remaining) as active_qty,
                           sum(ib.quantity_remaining * ib.unit_cost)
                             / nullif(sum(ib.quantity_remaining), 0) as wac,
                           count(*) as batch_count
                      from inventory_batches ib
                     where ib.business_id = :businessId
                       and ib.status = 'active'
                       and ib.quantity_remaining > 0
                       and (:branchId is null or ib.branch_id = :branchId)
                     group by ib.item_id
                  ) agg on agg.item_id = i.id
                 where i.business_id = :businessId
                   and i.deleted_at is null
                   and i.active = true
                   and i.is_sellable = true
              ) t
             where (
                    -- Holding stock with no cost recorded (understates COGS).
                    (t.activeQty > 0 and (t.effectiveCost is null or t.effectiveCost <= 0))
                    -- Cost at or above the sell price (would sell at a loss).
                 or (t.sellPrice is not null and t.sellPrice > 0
                     and t.effectiveCost is not null and t.effectiveCost > 0
                     and t.effectiveCost >= t.sellPrice)
                    -- Thin margin.
                 or (t.sellPrice is not null and t.sellPrice > 0
                     and t.effectiveCost is not null and t.effectiveCost > 0
                     and (t.sellPrice - t.effectiveCost) < t.sellPrice * :thinFraction)
               )
             order by
               case when t.activeQty > 0 and (t.effectiveCost is null or t.effectiveCost <= 0) then 0
                    when t.sellPrice is not null and t.sellPrice > 0
                         and t.effectiveCost is not null and t.effectiveCost > 0
                         and t.effectiveCost >= t.sellPrice then 1
                    else 2 end,
               t.activeQty desc,
               t.name asc
             limit :maxRows
            """, nativeQuery = true)
    List<CostIssueRow> findCostIssues(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("thinFraction") BigDecimal thinFraction,
            @Param("maxRows") int maxRows
    );
}
