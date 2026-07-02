package zelisline.ub.purchasing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.suppliers.domain.SupplierProduct;
import zelisline.ub.suppliers.repository.SupplierProductRepository;

/**
 * Converts supplier purchase qty + unit to Path B catalog {@code itemId} + {@code usableQty},
 * using {@code supplier_products.pack_unit} / {@code pack_size} when configured.
 */
@Service
@RequiredArgsConstructor
public class PurchaseUnitConversionService {

    private static final int QTY_SCALE = 4;
    private static final BigDecimal TOLERANCE = new BigDecimal("0.0001");

    private static final Map<String, BigDecimal> KG_PER_UNIT = Map.ofEntries(
            Map.entry("kg", BigDecimal.ONE),
            Map.entry("kilogram", BigDecimal.ONE),
            Map.entry("kilograms", BigDecimal.ONE),
            Map.entry("g", new BigDecimal("0.001")),
            Map.entry("gram", new BigDecimal("0.001")),
            Map.entry("grams", new BigDecimal("0.001")),
            Map.entry("lb", new BigDecimal("0.453592")),
            Map.entry("pound", new BigDecimal("0.453592")),
            Map.entry("pounds", new BigDecimal("0.453592")),
            Map.entry("each", BigDecimal.ONE),
            Map.entry("pc", BigDecimal.ONE),
            Map.entry("piece", BigDecimal.ONE)
    );

    private final ItemRepository itemRepository;
    private final SupplierProductRepository supplierProductRepository;

    public record ReceiptResolution(String catalogItemId, BigDecimal usableQty) {
    }

    /**
     * When purchase qty/unit are sent on post, ensure posted catalog line matches server conversion.
     */
    public void assertMatchesPosted(
            String businessId,
            String supplierId,
            String postedItemId,
            BigDecimal postedUsableQty,
            BigDecimal purchaseQty,
            String purchaseUnit
    ) {
        if (purchaseQty == null || purchaseUnit == null || purchaseUnit.isBlank()) {
            return;
        }
        if (purchaseQty.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "purchaseQty must be positive");
        }
        ReceiptResolution expected = resolve(businessId, supplierId, postedItemId, purchaseQty, purchaseUnit);
        BigDecimal posted = postedUsableQty == null ? BigDecimal.ZERO : postedUsableQty.setScale(QTY_SCALE, RoundingMode.HALF_UP);
        if (!expected.catalogItemId().equals(postedItemId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Posted item does not match purchase unit conversion");
        }
        BigDecimal diff = posted.subtract(expected.usableQty()).abs();
        if (diff.compareTo(TOLERANCE) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Posted usableQty does not match purchase unit conversion (expected "
                            + expected.usableQty().toPlainString() + ")");
        }
    }

    public ReceiptResolution resolve(
            String businessId,
            String supplierId,
            String catalogItemId,
            BigDecimal purchaseQty,
            String purchaseUnitRaw
    ) {
        Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(catalogItemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item not found"));
        String purchaseUnit = normUnit(purchaseUnitRaw);
        BigDecimal qty = purchaseQty.setScale(QTY_SCALE, RoundingMode.HALF_UP);
        List<Item> variants = itemRepository.findByBusinessIdAndVariantOfItemIdAndDeletedAtIsNullOrderBySkuAsc(
                businessId, item.getId());
        Optional<SupplierProduct> link = supplierProductRepository.findBySupplierIdAndItemId(supplierId, item.getId())
                .filter(sp -> sp.getDeletedAt() == null && sp.isActive());

        if (link.isPresent()) {
            SupplierProduct sp = link.get();
            if (sp.getPackUnit() != null && sp.getPackSize() != null && sp.getPackSize().signum() > 0) {
                if (normUnit(sp.getPackUnit()).equals(purchaseUnit)) {
                    BigDecimal usable = qty.multiply(sp.getPackSize()).setScale(QTY_SCALE, RoundingMode.HALF_UP);
                    return new ReceiptResolution(item.getId(), usable);
                }
            }
        }

        for (Item variant : variants) {
            String variantUnit = variantUnitName(variant);
            if (variantUnit != null && normUnit(variantUnit).equals(purchaseUnit)) {
                return new ReceiptResolution(variant.getId(), qty);
            }
        }

        String itemPackaging = blankToNull(item.getPackagingUnitName());
        if (itemPackaging != null && normUnit(itemPackaging).equals(purchaseUnit) && item.isPackageVariant()) {
            return new ReceiptResolution(item.getId(), qty);
        }

        String stockUnit = normUnit(blankToNull(item.getUnitType()) != null ? item.getUnitType() : "kg");
        BigDecimal purchaseKg = KG_PER_UNIT.get(purchaseUnit);
        BigDecimal stockKg = KG_PER_UNIT.get(stockUnit);
        if (purchaseKg != null && stockKg != null && (item.isWeighed() || stockKg.compareTo(BigDecimal.ONE) != 0)) {
            BigDecimal stockQty = qty.multiply(purchaseKg).divide(stockKg, QTY_SCALE, RoundingMode.HALF_UP);
            return new ReceiptResolution(item.getId(), stockQty);
        }

        if (purchaseUnit.equals(stockUnit) || purchaseUnit.equals("each") || stockUnit.equals("each")) {
            return new ReceiptResolution(item.getId(), qty);
        }

        BigDecimal unitsPerPackage = positiveUnits(item.getPackagingUnitQty());
        if (unitsPerPackage != null
                && (purchaseUnit.equals("crate") || purchaseUnit.equals("case") || purchaseUnit.equals("box"))) {
            BigDecimal usable = qty.multiply(unitsPerPackage).setScale(QTY_SCALE, RoundingMode.HALF_UP);
            return new ReceiptResolution(item.getId(), usable);
        }

        return new ReceiptResolution(item.getId(), qty);
    }

    private static String variantUnitName(Item variant) {
        String packaging = blankToNull(variant.getPackagingUnitName());
        if (packaging != null) {
            return packaging;
        }
        return blankToNull(variant.getVariantName());
    }

    private static String normUnit(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    private static BigDecimal positiveUnits(BigDecimal units) {
        if (units == null || units.signum() <= 0) {
            return null;
        }
        return units;
    }
}
