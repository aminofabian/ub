package zelisline.ub.integrations.csvimport.support;

/**
 * Shared CSV column headers for import templates and matching exports.
 * Keep these in sync — export must round-trip into import.
 *
 * <p>For items, only {@code sku} and {@code name} are required on import. Every
 * other column may be omitted from the file or left blank.
 */
public final class CsvImportFormats {

    public static final String[] ITEM_HEADERS = {
            "sku",
            "name",
            "item_type_key",
            "barcode",
            "unit_type",
            "is_stocked",
            "is_sellable",
            "category_name",
            "aisle_code",
            "brand",
            "size",
            "buying_price",
            "selling_price",
            "on_hand",
            "min_stock_level",
            "reorder_level",
            "supplier_name",
            "supplier_code",
            "image_url"
    };

    public static final String[] SUPPLIER_HEADERS = {
            "name",
            "code",
            "supplier_type",
            "vat_pin",
            "status",
            "notes"
    };

    public static final String[] OPENING_STOCK_HEADERS = {
            "branch_name",
            "sku",
            "quantity",
            "unit_cost",
            "notes"
    };

    public static final String ITEM_TEMPLATE_HEADER = String.join(",", ITEM_HEADERS);
    public static final String SUPPLIER_TEMPLATE_HEADER = String.join(",", SUPPLIER_HEADERS);
    public static final String OPENING_STOCK_TEMPLATE_HEADER = String.join(",", OPENING_STOCK_HEADERS);

    private CsvImportFormats() {
    }
}
