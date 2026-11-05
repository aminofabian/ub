package zelisline.ub.globalcatalog.application;

import java.math.BigDecimal;

import zelisline.ub.globalcatalog.domain.GlobalProduct;

/**
 * The flat product fields the CSV importer may assign, with one shared read/apply
 * implementation so the preview diff and the commit write can never disagree.
 *
 * <p>A field is only applied when the planner put it in the row's value map — this is
 * what implements the absent-column-vs-blank-cell contract (scope §5.7): a column that
 * is absent from the file never reaches the map, so the stored value survives an update.
 */
public enum ImportProductField {
    NAME,
    BRAND,
    SIZE,
    DESCRIPTION,
    BARCODE,
    SKU_TEMPLATE,
    UNIT_TYPE,
    WEIGHED,
    SELLABLE,
    STOCKED,
    STATUS,
    CATEGORY_ID,
    BUYING_PRICE,
    SELLING_PRICE,
    MARGIN_PCT,
    REORDER_LEVEL,
    REORDER_QTY,
    MIN_STOCK_LEVEL,
    HAS_EXPIRY,
    EXPIRES_AFTER_DAYS,
    ITEM_TYPE_KEY_HINT,
    SORT_ORDER,
    VARIANT_NAME,
    PACKAGE_VARIANT,
    /** Parent link; written in the child's own row transaction, never in a later pass. */
    VARIANT_OF,
    PACKAGING_UNIT_NAME,
    PACKAGING_UNIT_QTY,
    STORE_KIT_ID;

    /** Current stored value, for diff rendering and unchanged detection. */
    public Object read(GlobalProduct p) {
        return switch (this) {
            case NAME -> p.getName();
            case BRAND -> p.getBrand();
            case SIZE -> p.getSize();
            case DESCRIPTION -> p.getDescription();
            case BARCODE -> p.getBarcode();
            case SKU_TEMPLATE -> p.getSkuTemplate();
            case UNIT_TYPE -> p.getUnitType();
            case WEIGHED -> p.isWeighed();
            case SELLABLE -> p.isSellable();
            case STOCKED -> p.isStocked();
            case STATUS -> p.getStatus();
            case CATEGORY_ID -> p.getGlobalCategoryId();
            case BUYING_PRICE -> p.getRecommendedBuyingPrice();
            case SELLING_PRICE -> p.getRecommendedSellingPrice();
            case MARGIN_PCT -> p.getSuggestedMarginPct();
            case REORDER_LEVEL -> p.getDefaultReorderLevel();
            case REORDER_QTY -> p.getDefaultReorderQty();
            case MIN_STOCK_LEVEL -> p.getDefaultMinStockLevel();
            case HAS_EXPIRY -> p.isHasExpiry();
            case EXPIRES_AFTER_DAYS -> p.getExpiresAfterDays();
            case ITEM_TYPE_KEY_HINT -> p.getItemTypeKeyHint();
            case SORT_ORDER -> p.getSortOrder();
            case VARIANT_NAME -> p.getVariantName();
            case PACKAGE_VARIANT -> p.isPackageVariant();
            case VARIANT_OF -> p.getVariantOfGlobalProductId();
            case PACKAGING_UNIT_NAME -> p.getPackagingUnitName();
            case PACKAGING_UNIT_QTY -> p.getPackagingUnitQty();
            case STORE_KIT_ID -> p.getStoreKitId();
        };
    }

    public void apply(GlobalProduct p, Object value) {
        switch (this) {
            case NAME -> p.setName((String) value);
            case BRAND -> p.setBrand((String) value);
            case SIZE -> p.setSize((String) value);
            case DESCRIPTION -> p.setDescription((String) value);
            case BARCODE -> p.setBarcode((String) value);
            case SKU_TEMPLATE -> p.setSkuTemplate((String) value);
            case UNIT_TYPE -> p.setUnitType((String) value);
            case WEIGHED -> p.setWeighed(bool(value));
            case SELLABLE -> p.setSellable(bool(value));
            case STOCKED -> p.setStocked(bool(value));
            case STATUS -> p.setStatus((String) value);
            case CATEGORY_ID -> p.setGlobalCategoryId((String) value);
            case BUYING_PRICE -> p.setRecommendedBuyingPrice((BigDecimal) value);
            case SELLING_PRICE -> p.setRecommendedSellingPrice((BigDecimal) value);
            case MARGIN_PCT -> p.setSuggestedMarginPct((BigDecimal) value);
            case REORDER_LEVEL -> p.setDefaultReorderLevel((BigDecimal) value);
            case REORDER_QTY -> p.setDefaultReorderQty((BigDecimal) value);
            case MIN_STOCK_LEVEL -> p.setDefaultMinStockLevel((BigDecimal) value);
            case HAS_EXPIRY -> p.setHasExpiry(bool(value));
            case EXPIRES_AFTER_DAYS -> p.setExpiresAfterDays((Integer) value);
            case ITEM_TYPE_KEY_HINT -> p.setItemTypeKeyHint((String) value);
            case SORT_ORDER -> p.setSortOrder(value == null ? 0 : (Integer) value);
            case VARIANT_NAME -> p.setVariantName((String) value);
            case PACKAGE_VARIANT -> p.setPackageVariant(bool(value));
            case VARIANT_OF -> p.setVariantOfGlobalProductId((String) value);
            case PACKAGING_UNIT_NAME -> p.setPackagingUnitName((String) value);
            case PACKAGING_UNIT_QTY -> p.setPackagingUnitQty((BigDecimal) value);
            case STORE_KIT_ID -> p.setStoreKitId((String) value);
        }
    }

    /** True when the stored value equals the planned value (null-safe; BigDecimal by compareTo). */
    public boolean matches(GlobalProduct p, Object planned) {
        Object current = read(p);
        if (current == null && planned == null) {
            return true;
        }
        if (current == null || planned == null) {
            return false;
        }
        if (current instanceof BigDecimal a && planned instanceof BigDecimal b) {
            return a.compareTo(b) == 0;
        }
        return current.equals(planned);
    }

    private static boolean bool(Object value) {
        return value != null && (Boolean) value;
    }
}
