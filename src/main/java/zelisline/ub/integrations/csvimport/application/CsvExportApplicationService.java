package zelisline.ub.integrations.csvimport.application;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.domain.ItemType;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.integrations.csvimport.support.CsvImportFormats;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.pricing.domain.SellingPrice;
import zelisline.ub.pricing.repository.SellingPriceRepository;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.repository.BranchRepository;

/**
 * CSV exports matching {@link CsvImportFormats} so merchants can download,
 * edit in Excel, and re-upload via Data Import.
 */
@Service
@RequiredArgsConstructor
public class CsvExportApplicationService {

    private static final int MONEY_SCALE = 2;
    private static final int QTY_SCALE = 4;

    private final ItemRepository itemRepository;
    private final ItemTypeRepository itemTypeRepository;
    private final SupplierRepository supplierRepository;
    private final BranchRepository branchRepository;
    private final SellingPriceRepository sellingPriceRepository;
    private final InventoryBatchRepository inventoryBatchRepository;

    @Transactional(readOnly = true)
    public byte[] exportItems(String businessId) {
        List<Item> items = itemRepository.findByBusinessIdAndDeletedAtIsNull(businessId).stream()
                .sorted(Comparator
                        .comparing((Item i) -> nullToEmpty(i.getName()).toLowerCase(Locale.ROOT))
                        .thenComparing(i -> nullToEmpty(i.getSku()).toLowerCase(Locale.ROOT)))
                .toList();

        Map<String, String> typeKeyById = itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(businessId).stream()
                .collect(Collectors.toMap(ItemType::getId, ItemType::getTypeKey, (a, b) -> a));

        Map<String, BigDecimal> sellByItemId = latestBusinessWideSellPriceByItem(businessId);

        return writeCsv(CsvImportFormats.ITEM_HEADERS, printer -> {
            for (Item item : items) {
                printer.printRecord(
                        nullToEmpty(item.getSku()),
                        nullToEmpty(item.getName()),
                        nullToEmpty(typeKeyById.get(item.getItemTypeId())),
                        nullToEmpty(item.getBarcode()),
                        nullToEmpty(item.getUnitType()),
                        bool(item.isStocked()),
                        bool(item.isSellable()),
                        decimal(sellByItemId.get(item.getId()), MONEY_SCALE),
                        decimal(item.getReorderLevel(), QTY_SCALE));
            }
        });
    }

    @Transactional(readOnly = true)
    public byte[] exportSuppliers(String businessId) {
        List<Supplier> suppliers = supplierRepository.findAllByBusinessIdNotDeleted(businessId);
        return writeCsv(CsvImportFormats.SUPPLIER_HEADERS, printer -> {
            for (Supplier s : suppliers) {
                printer.printRecord(
                        nullToEmpty(s.getName()),
                        nullToEmpty(s.getCode()),
                        nullToEmpty(s.getSupplierType()),
                        nullToEmpty(s.getVatPin()),
                        nullToEmpty(s.getStatus()),
                        nullToEmpty(s.getNotes()));
            }
        });
    }

    @Transactional(readOnly = true)
    public byte[] exportOpeningStock(String businessId) {
        List<Branch> branches = branchRepository.findByBusinessIdAndDeletedAtIsNullOrderByNameAsc(businessId);
        Map<String, Item> itemsById = itemRepository.findByBusinessIdAndDeletedAtIsNull(businessId).stream()
                .collect(Collectors.toMap(Item::getId, i -> i, (a, b) -> a));

        return writeCsv(CsvImportFormats.OPENING_STOCK_HEADERS, printer -> {
            for (Branch branch : branches) {
                List<Object[]> rows = inventoryBatchRepository.sumQtyAndExtensionByItemAtBranch(
                        businessId,
                        branch.getId(),
                        InventoryConstants.BATCH_STATUS_ACTIVE);
                rows.sort(Comparator.comparing(r -> {
                    Item item = itemsById.get((String) r[0]);
                    return item == null ? "" : nullToEmpty(item.getSku()).toLowerCase(Locale.ROOT);
                }));
                for (Object[] row : rows) {
                    String itemId = (String) row[0];
                    Item item = itemsById.get(itemId);
                    if (item == null || item.getSku() == null || item.getSku().isBlank()) {
                        continue;
                    }
                    BigDecimal qty = toBd(row[1]);
                    if (qty == null || qty.signum() <= 0) {
                        continue;
                    }
                    BigDecimal extension = toBd(row[2]);
                    BigDecimal unitCost;
                    if (extension != null && qty.signum() > 0) {
                        unitCost = extension.divide(qty, MONEY_SCALE, RoundingMode.HALF_UP);
                    } else if (item.getBuyingPrice() != null) {
                        unitCost = item.getBuyingPrice().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                    } else {
                        unitCost = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                    }
                    printer.printRecord(
                            nullToEmpty(branch.getName()),
                            item.getSku(),
                            decimal(qty, QTY_SCALE),
                            decimal(unitCost, MONEY_SCALE),
                            "");
                }
            }
        });
    }

    private Map<String, BigDecimal> latestBusinessWideSellPriceByItem(String businessId) {
        Map<String, SellingPrice> best = new HashMap<>();
        for (SellingPrice sp : sellingPriceRepository.findOpenEndedBusinessWide(businessId)) {
            SellingPrice prev = best.get(sp.getItemId());
            if (prev == null
                    || sp.getEffectiveFrom().isAfter(prev.getEffectiveFrom())
                    || (sp.getEffectiveFrom().equals(prev.getEffectiveFrom())
                            && Objects.toString(sp.getId(), "").compareTo(Objects.toString(prev.getId(), "")) > 0)) {
                best.put(sp.getItemId(), sp);
            }
        }
        Map<String, BigDecimal> out = new HashMap<>();
        best.forEach((itemId, sp) -> out.put(itemId, sp.getPrice()));
        return out;
    }

    @FunctionalInterface
    private interface CsvRows {
        void write(CSVPrinter printer) throws IOException;
    }

    private static byte[] writeCsv(String[] headers, CsvRows rows) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            out.write(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            try (Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
                    CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder()
                            .setHeader(headers)
                            .build())) {
                rows.write(printer);
                printer.flush();
            }
            return out.toByteArray();
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not build CSV export");
        }
    }

    private static String bool(boolean value) {
        return value ? "true" : "false";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String decimal(BigDecimal value, int scale) {
        if (value == null) {
            return "";
        }
        return value.setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }

    private static BigDecimal toBd(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        return new BigDecimal(value.toString());
    }
}
