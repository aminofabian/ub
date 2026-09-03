package zelisline.ub.catalog.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.api.dto.ItemEconomicsDayPoint;
import zelisline.ub.catalog.api.dto.ItemEconomicsResponse;
import zelisline.ub.catalog.api.dto.ItemPurchaseHistoryRow;
import zelisline.ub.catalog.api.dto.ItemSaleHistoryRow;
import zelisline.ub.catalog.api.dto.ItemSupplierSpendRow;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.purchasing.repository.SupplierInvoiceLineRepository;
import zelisline.ub.sales.repository.SaleItemRepository;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.repository.BranchRepository;

@Service
@RequiredArgsConstructor
public class ItemEconomicsService {

    static final ZoneId SHOP_ZONE = ZoneId.of("Africa/Nairobi");
    private static final int HISTORY_LIMIT = 40;
    private static final int TREND_DAYS = 30;

    private final ItemRepository itemRepository;
    private final SaleItemRepository saleItemRepository;
    private final SupplierInvoiceLineRepository supplierInvoiceLineRepository;
    private final SupplierRepository supplierRepository;
    private final BranchRepository branchRepository;

    @Transactional(readOnly = true)
    public ItemEconomicsResponse economics(String businessId, String itemId) {
        Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found"));

        List<String> skuIds = new ArrayList<>();
        skuIds.add(item.getId());
        BigDecimal onHand = nz(item.getCurrentStock());
        boolean includesVariants = false;
        if (item.getVariantOfItemId() == null || item.getVariantOfItemId().isBlank()) {
            List<Item> children = itemRepository.findByBusinessIdAndVariantOfItemIdAndDeletedAtIsNullOrderBySkuAsc(
                    businessId, item.getId());
            if (!children.isEmpty()) {
                includesVariants = true;
                for (Item child : children) {
                    skuIds.add(child.getId());
                    onHand = onHand.add(nz(child.getCurrentStock()));
                }
            }
        }

        Instant now = Instant.now();
        Instant since7 = now.minusSeconds(7L * 24 * 60 * 60);
        Instant since30 = now.minusSeconds((long) TREND_DAYS * 24 * 60 * 60);
        LocalDate today = LocalDate.now(SHOP_ZONE);

        Totals allTime = readSalesTotals(businessId, skuIds, null);
        Totals last7 = readSalesTotals(businessId, skuIds, since7);
        Totals last30 = readSalesTotals(businessId, skuIds, since30);
        SpendTotals spend = readSpendTotals(businessId, skuIds);

        Map<String, String> branchNames = branchNames(businessId);
        Map<String, String> supplierNames = supplierNames(spend.bySupplier().keySet(), businessId);

        List<ItemEconomicsDayPoint> days = buildDaySeries(
                saleItemRepository.completedSalePointsSince(businessId, skuIds, since30),
                today.minusDays(TREND_DAYS - 1L),
                today);

        List<ItemSupplierSpendRow> supplierRows = new ArrayList<>();
        spend.bySupplier().forEach((supplierId, row) -> supplierRows.add(new ItemSupplierSpendRow(
                supplierId,
                supplierNames.getOrDefault(supplierId, "Supplier"),
                row.qty(),
                row.spend())));

        List<ItemSaleHistoryRow> sales = new ArrayList<>();
        for (Object[] row : saleItemRepository.recentCompletedSaleLines(
                businessId, skuIds, PageRequest.of(0, HISTORY_LIMIT))) {
            String branchId = asString(row[3]);
            sales.add(new ItemSaleHistoryRow(
                    asString(row[0]),
                    asLong(row[1]),
                    asInstant(row[2]),
                    branchId,
                    branchNames.getOrDefault(branchId, ""),
                    asString(row[4]),
                    bd(row[5]),
                    bd(row[6]),
                    bd(row[7]),
                    bd(row[8]),
                    bd(row[9])));
        }

        List<ItemPurchaseHistoryRow> purchases = new ArrayList<>();
        for (Object[] row : supplierInvoiceLineRepository.recentPostedLines(
                businessId, skuIds, PageRequest.of(0, HISTORY_LIMIT))) {
            String supplierId = asString(row[3]);
            purchases.add(new ItemPurchaseHistoryRow(
                    asString(row[0]),
                    asString(row[1]),
                    asLocalDate(row[2]),
                    supplierId,
                    supplierNames.getOrDefault(supplierId, "Supplier"),
                    asString(row[4]),
                    bd(row[5]),
                    bd(row[6]),
                    bd(row[7]),
                    asString(row[8])));
        }

        return new ItemEconomicsResponse(
                item.getId(),
                ProductDisplayName.forItem(item),
                includesVariants,
                skuIds.size(),
                allTime.qty(),
                last7.qty(),
                last30.qty(),
                allTime.revenue(),
                allTime.cost(),
                allTime.profit(),
                allTime.saleCount(),
                allTime.lastSoldAt(),
                spend.totalSpend(),
                spend.totalQty(),
                onHand,
                days,
                supplierRows,
                sales,
                purchases);
    }

    private Totals readSalesTotals(String businessId, Collection<String> skuIds, Instant from) {
        List<Object[]> rows = saleItemRepository.aggregateCompletedSales(businessId, skuIds, from);
        Object[] row = firstRow(rows);
        if (row == null) {
            return Totals.ZERO;
        }
        return new Totals(
                bd(row[0]),
                bd(row[1]),
                bd(row[2]),
                bd(row[3]),
                asLong(row[4]) == null ? 0L : asLong(row[4]),
                asInstant(row[5]));
    }

    private SpendTotals readSpendTotals(String businessId, Collection<String> skuIds) {
        Object[] totalsRow = firstRow(supplierInvoiceLineRepository.aggregatePostedSpend(businessId, skuIds));
        BigDecimal qty = totalsRow == null ? BigDecimal.ZERO : bd(totalsRow[0]);
        BigDecimal spend = totalsRow == null ? BigDecimal.ZERO : bd(totalsRow[1]);
        LinkedHashMap<String, QtySpend> bySupplier = new LinkedHashMap<>();
        for (Object[] row : supplierInvoiceLineRepository.spendBySupplier(businessId, skuIds)) {
            String supplierId = asString(row[0]);
            if (supplierId == null || supplierId.isBlank()) {
                continue;
            }
            bySupplier.put(supplierId, new QtySpend(bd(row[1]), bd(row[2])));
        }
        return new SpendTotals(qty, spend, bySupplier);
    }

    static List<ItemEconomicsDayPoint> buildDaySeries(
            List<Object[]> points,
            LocalDate fromInclusive,
            LocalDate toInclusive) {
        Map<LocalDate, QtySpend> buckets = new HashMap<>();
        if (points != null) {
            for (Object[] row : points) {
                Instant at = asInstant(row[0]);
                if (at == null) {
                    continue;
                }
                LocalDate day = at.atZone(SHOP_ZONE).toLocalDate();
                QtySpend prev = buckets.getOrDefault(day, QtySpend.ZERO);
                buckets.put(day, new QtySpend(prev.qty().add(bd(row[1])), prev.spend().add(bd(row[2]))));
            }
        }
        List<ItemEconomicsDayPoint> out = new ArrayList<>();
        for (LocalDate d = fromInclusive; !d.isAfter(toInclusive); d = d.plusDays(1)) {
            QtySpend v = buckets.getOrDefault(d, QtySpend.ZERO);
            out.add(new ItemEconomicsDayPoint(d, v.qty(), v.spend()));
        }
        return out;
    }

    private Map<String, String> branchNames(String businessId) {
        return branchRepository.findByBusinessIdAndDeletedAtIsNullOrderByNameAsc(businessId).stream()
                .collect(Collectors.toMap(Branch::getId, Branch::getName, (a, b) -> a));
    }

    private Map<String, String> supplierNames(Set<String> ids, String businessId) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new HashMap<>();
        for (String id : ids) {
            supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull(id, businessId)
                    .map(Supplier::getName)
                    .ifPresent(name -> out.put(id, name));
        }
        return out;
    }

    static Object[] firstRow(List<Object[]> rows) {
        if (rows == null || rows.isEmpty() || rows.get(0) == null) {
            return null;
        }
        Object[] row = rows.get(0);
        if (row.length == 1 && row[0] instanceof Object[] nested) {
            return nested;
        }
        return row;
    }

    static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    static BigDecimal bd(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal n) {
            return n;
        }
        if (v instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    static Long asLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static Instant asInstant(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Instant i) {
            return i;
        }
        return null;
    }

    static LocalDate asLocalDate(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDate d) {
            return d;
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        return null;
    }

    private record Totals(
            BigDecimal qty,
            BigDecimal revenue,
            BigDecimal cost,
            BigDecimal profit,
            long saleCount,
            Instant lastSoldAt
    ) {
        static final Totals ZERO = new Totals(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0L, null);
    }

    private record QtySpend(BigDecimal qty, BigDecimal spend) {
        static final QtySpend ZERO = new QtySpend(BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private record SpendTotals(BigDecimal totalQty, BigDecimal totalSpend, LinkedHashMap<String, QtySpend> bySupplier) {
    }
}
