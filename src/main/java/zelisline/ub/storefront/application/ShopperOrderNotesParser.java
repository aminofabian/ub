package zelisline.ub.storefront.application;

import java.util.HashMap;
import java.util.Map;

/** Parses pipe-delimited {@code web_orders.notes} from storefront checkout. */
public final class ShopperOrderNotesParser {

    private ShopperOrderNotesParser() {
    }

    public static ParsedOrderNotes parse(String notes) {
        Map<String, String> fields = new HashMap<>();
        boolean defaultAddress = false;
        if (notes == null || notes.isBlank()) {
            return new ParsedOrderNotes(fields, defaultAddress);
        }
        for (String segment : notes.split("\\|")) {
            String seg = segment.trim();
            if (seg.isEmpty()) {
                continue;
            }
            if (seg.startsWith("Street:")) {
                fields.put("streetAddress", seg.substring(7).trim());
            } else if (seg.startsWith("County:")) {
                fields.put("county", seg.substring(7).trim());
            } else if (seg.startsWith("Subcounty:")) {
                fields.put("subCounty", seg.substring(10).trim());
            } else if (seg.startsWith("Ward:")) {
                fields.put("ward", seg.substring(5).trim());
            } else if (seg.startsWith("WhatsApp:")) {
                fields.put("whatsApp", seg.substring(9).trim());
            } else if (seg.startsWith("Notes:")) {
                fields.put("deliveryNotes", seg.substring(6).trim());
            } else if ("Set as default address".equals(seg)) {
                defaultAddress = true;
            }
        }
        return new ParsedOrderNotes(fields, defaultAddress);
    }

    public record ParsedOrderNotes(Map<String, String> fields, boolean defaultAddress) {}
}
