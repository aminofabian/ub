package zelisline.ub.marketplace.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.repository.ItemRepository;

/**
 * A shop's live catalogue by barcode and by name, loaded once per attach and kept current as the
 * attach creates items. Every lookup is answered from memory, so an item created a moment ago in its
 * own transaction is found just like one that was always there (re-reading it from the database inside
 * the attach transaction can miss it on MySQL's default isolation level).
 */
final class MarketplaceItemIndex {

    static final String BY_BARCODE = "barcode";
    static final String BY_NAME = "name";

    /** An item the shop already has. */
    record Entry(String id, String name, String barcode) {
    }

    /** The item a shelf product was matched to, and what it was matched on ({@link #BY_BARCODE} or {@link #BY_NAME}). */
    record Match(Entry item, String by) {
    }

    private final Map<String, Entry> byBarcode = new HashMap<>();
    private final Map<String, List<Entry>> byName = new HashMap<>();

    static MarketplaceItemIndex load(ItemRepository itemRepository, String businessId) {
        MarketplaceItemIndex index = new MarketplaceItemIndex();
        for (ItemRepository.ItemIdentityRow row : itemRepository.findIdentitiesByBusinessId(businessId)) {
            index.add(row.getId(), row.getName(), row.getBarcode());
        }
        return index;
    }

    void add(String id, String name, String barcode) {
        Entry entry = new Entry(id, name, barcode);
        String barcodeKey = barcodeKey(barcode);
        if (barcodeKey != null) {
            byBarcode.putIfAbsent(barcodeKey, entry);
        }
        String nameKey = nameKey(name);
        if (nameKey != null) {
            List<Entry> sameName = byName.get(nameKey);
            if (sameName == null) {
                sameName = new ArrayList<>();
                byName.put(nameKey, sameName);
            }
            sameName.add(entry);
        }
    }

    /**
     * The barcode decides first. With no barcode match, an item called exactly the same counts as the same
     * product, unless both sides carry barcodes: two different barcodes are two different products
     * whatever they are called. Returns null when the shop has nothing like it.
     */
    Match find(String barcode, String name) {
        String barcodeKey = barcodeKey(barcode);
        if (barcodeKey != null) {
            Entry hit = byBarcode.get(barcodeKey);
            if (hit != null) {
                return new Match(hit, BY_BARCODE);
            }
        }
        String nameKey = nameKey(name);
        if (nameKey != null) {
            List<Entry> sameName = byName.get(nameKey);
            if (sameName != null) {
                for (Entry candidate : sameName) {
                    if (barcodeKey == null || barcodeKey(candidate.barcode()) == null) {
                        return new Match(candidate, BY_NAME);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Barcodes are compared the way the catalogue stores them (separators dropped, digits only), so a
     * shelf barcode written "6161-100" still meets the shop's "6161100". Anything the catalogue would not
     * store as a barcode is compared as written.
     */
    private static String barcodeKey(String barcode) {
        if (barcode == null || barcode.isBlank()) {
            return null;
        }
        String normalized = ItemCatalogService.normalizeBarcode(barcode);
        return normalized != null ? normalized : barcode.trim().toLowerCase(Locale.ROOT);
    }

    private static String nameKey(String name) {
        if (name == null) {
            return null;
        }
        String squashed = name.trim().replaceAll("\\s+", " ");
        return squashed.isEmpty() ? null : squashed.toLowerCase(Locale.ROOT);
    }
}
