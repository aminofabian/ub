package zelisline.ub.tenancy.integrations.storefront;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds the registrar DNS instruction payload shown in the connect UI. */
public final class StorefrontDnsInstructions {

    private StorefrontDnsInstructions() {}

    public static Map<String, Object> forHostname(
            String hostname,
            String aTarget,
            String cnameTarget,
            String providerId
    ) {
        String host = hostname == null ? "" : hostname.trim().toLowerCase(Locale.ROOT);
        Map<String, Object> instructions = new LinkedHashMap<>();
        instructions.put("provider", providerId);
        instructions.put("hostname", host);

        List<Map<String, String>> records = new ArrayList<>(2);
        if (isLikelyApex(host)) {
            records.add(Map.of("type", "A", "name", "@", "value", aTarget));
            records.add(Map.of("type", "CNAME", "name", "www", "value", cnameTarget));
        } else if (host.startsWith("www.")) {
            String apex = host.substring(4);
            records.add(Map.of("type", "A", "name", "@", "value", aTarget));
            records.add(Map.of("type", "CNAME", "name", "www", "value", cnameTarget));
            instructions.put("apex", apex);
        } else {
            // Subdomain of a customer zone — CNAME the leaf to our connect host.
            String leaf = host.contains(".") ? host.substring(0, host.indexOf('.')) : host;
            records.add(Map.of("type", "CNAME", "name", leaf, "value", cnameTarget));
        }

        instructions.put("recommendedRecords", records);
        instructions.put(
                "note",
                "Add these where you bought the domain. Leave nameservers and mail records as they are, then choose Check connection."
        );
        return instructions;
    }

    /**
     * Heuristic: one label + multi-part TLD (co.ke, com, …) or two labels for
     * simple TLDs. Good enough for Kenyan connect UX; DNS check is the source of truth.
     */
    public static boolean isLikelyApex(String host) {
        if (host == null || host.isBlank() || host.startsWith("www.")) {
            return false;
        }
        String[] parts = host.split("\\.");
        if (parts.length == 2) {
            return true;
        }
        if (parts.length == 3) {
            String mid = parts[1];
            return mid.equals("co")
                    || mid.equals("or")
                    || mid.equals("me")
                    || mid.equals("sc")
                    || mid.equals("ac")
                    || mid.equals("go")
                    || mid.equals("com")
                    || mid.equals("net")
                    || mid.equals("org");
        }
        return false;
    }
}
