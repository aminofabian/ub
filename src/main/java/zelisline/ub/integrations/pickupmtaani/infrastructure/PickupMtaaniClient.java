package zelisline.ub.integrations.pickupmtaani.infrastructure;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import zelisline.ub.integrations.pickupmtaani.config.PickupMtaaniProperties;

/**
 * HTTP client for the Pickup Mtaani business API (spec 0.1.0). All calls are
 * server-side and carry the tenant's own key in the {@code apiKey} header; the
 * key is never logged.
 *
 * <p>The published spec has few schemas — success bodies for reads, the state
 * enum, and the fee route are undocumented. Parsing here is deliberately
 * defensive: missing fields become {@code null} rather than throwing, and only a
 * malformed response or a non-2xx status raises.
 */
@Component
public class PickupMtaaniClient {

    private static final Logger log = LoggerFactory.getLogger(PickupMtaaniClient.class);

    private static final int MAX_LOGGED_BODY = 400;

    private final PickupMtaaniProperties properties;
    private final ObjectMapper objectMapper;

    public PickupMtaaniClient(PickupMtaaniProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    // ── Account / business ──────────────────────────────────────────────────

    public AccountInfo getAccount(String apiKey) {
        JsonNode data = dataNode(get(apiKey, "/account", null));
        return new AccountInfo(
                textOrNull(data, "mode"),
                intOrNull(data, "business_count"),
                intOrNull(data, "max_businesses")
        );
    }

    public BusinessInfo getBusiness(String apiKey) {
        JsonNode data = dataNode(get(apiKey, "/business", null));
        return new BusinessInfo(
                longOrNull(data, "id"),
                textOrNull(data, "name"),
                textOrNull(data, "phone_number"),
                textOrNull(data, "kra_pin")
        );
    }

    // ── Geography ───────────────────────────────────────────────────────────

    public List<GeoOption> listZones(String apiKey) {
        return options(get(apiKey, "/locations/zones", null));
    }

    public List<GeoOption> listAreas(String apiKey, Long zoneId) {
        return options(get(apiKey, "/locations/areas", query("zoneId", zoneId)));
    }

    public List<GeoOption> listLocations(String apiKey, Long areaId, String purpose, String searchKey) {
        Map<String, Object> q = new LinkedHashMap<>();
        put(q, "areaId", areaId);
        put(q, "purpose", purpose);
        put(q, "searchKey", searchKey);
        return options(get(apiKey, "/locations", q));
    }

    public List<GeoOption> listAgents(String apiKey, Long locationId, String purpose, String searchKey) {
        Map<String, Object> q = new LinkedHashMap<>();
        put(q, "locationId", locationId);
        put(q, "purpose", purpose);
        put(q, "searchKey", searchKey);
        return options(get(apiKey, "/agents", q));
    }

    public List<GeoOption> listDoorstepDestinations(String apiKey, Long areaId, String searchKey) {
        Map<String, Object> q = new LinkedHashMap<>();
        put(q, "areaId", areaId);
        put(q, "searchKey", searchKey);
        return options(get(apiKey, "/locations/doorstep-destinations", q));
    }

    // ── Delivery charge ─────────────────────────────────────────────────────

    public DeliveryCharge getAgentDeliveryCharge(String apiKey, long senderAgentId, long receiverAgentId) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("senderAgentID", senderAgentId);
        q.put("receiverAgentID", receiverAgentId);
        return deliveryCharge(get(apiKey, "/delivery-charge/agent-package", q));
    }

    public DeliveryCharge getDoorstepDeliveryCharge(String apiKey, long senderAgentId, long doorstepDestinationId) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("senderAgentID", senderAgentId);
        q.put("doorstepDestinationID", doorstepDestinationId);
        return deliveryCharge(get(apiKey, "/delivery-charge/doorstep-package", q));
    }

    /**
     * The fee endpoint's 200 body is not in the spec (§14). Extract an amount
     * defensively; a body without a recognisable amount is a failed quote, never
     * a free delivery.
     */
    private DeliveryCharge deliveryCharge(JsonNode root) {
        BigDecimal amount = extractFee(root);
        if (amount == null) {
            log.warn("Pickup Mtaani delivery-charge response had no recognisable amount: {}",
                    truncate(root == null ? null : root.toString()));
            throw new PickupMtaaniApiException(
                    200, "UNPARSABLE_FEE", null,
                    "Pickup Mtaani returned an unexpected delivery-fee response.");
        }
        return new DeliveryCharge(amount, root.toString());
    }

    private static final List<String> FEE_KEYS = List.of(
            "delivery_fee", "deliveryFee", "delivery_charge", "deliveryCharge",
            "fee", "amount", "charge", "total", "price", "cost");
    private static final Pattern FEE_KEY_HINT =
            Pattern.compile("(?i)(fee|charge|amount|cost|price)");

    BigDecimal extractFee(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return null;
        }
        JsonNode data = dataNode(root);
        for (JsonNode node : new JsonNode[] { data, root }) {
            if (node == null || !node.isObject()) {
                continue;
            }
            for (String key : FEE_KEYS) {
                BigDecimal value = coerceDecimal(node.path(key));
                if (value != null) {
                    return value.setScale(2, RoundingMode.HALF_UP);
                }
            }
            for (String key : FEE_KEYS) {
                JsonNode child = node.path(key);
                if (child.isObject()) {
                    for (String inner : List.of("amount", "value", "fee", "total", "kes")) {
                        BigDecimal value = coerceDecimal(child.path(inner));
                        if (value != null) {
                            return value.setScale(2, RoundingMode.HALF_UP);
                        }
                    }
                }
            }
        }
        BigDecimal hinted = findNumericByKeyHint(data != null ? data : root);
        return hinted == null ? null : hinted.setScale(2, RoundingMode.HALF_UP);
    }

    /** Breadth-first search for the first numeric value under a fee-ish key. */
    private static BigDecimal findNumericByKeyHint(JsonNode root) {
        List<JsonNode> queue = new ArrayList<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            JsonNode node = queue.remove(0);
            if (node == null) {
                continue;
            }
            if (node.isObject()) {
                var fields = node.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    BigDecimal value = coerceDecimal(entry.getValue());
                    if (value != null && FEE_KEY_HINT.matcher(entry.getKey()).find()) {
                        return value;
                    }
                    if (entry.getValue().isObject() || entry.getValue().isArray()) {
                        queue.add(entry.getValue());
                    }
                }
            } else if (node.isArray()) {
                node.forEach(queue::add);
            }
        }
        return null;
    }

    private static BigDecimal coerceDecimal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || node.isBoolean()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        String text = node.isTextual() ? node.asText("").trim() : null;
        if (text == null || text.isEmpty()) {
            return null;
        }
        // Tolerate "KES 350", "350.00", "1,200".
        String cleaned = text.replaceAll("[^0-9.\\-]", "");
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ── Packages ────────────────────────────────────────────────────────────

    /** Creates an agent-to-agent package. Note the spec's create spelling `receieverAgentID_id`. */
    public CreatedPackage createAgentPackage(
            String apiKey,
            long senderAgentId,
            long receiverAgentId,
            String customerName,
            String customerPhoneNumber,
            String packageName,
            Integer packageValue) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("senderAgentID_id", senderAgentId);
        body.put("receieverAgentID_id", receiverAgentId);
        body.put("customerName", customerName);
        body.put("customerPhoneNumber", customerPhoneNumber);
        body.put("packageName", packageName);
        if (packageValue != null) {
            body.put("packageValue", packageValue);
        }
        body.put("paymentOption", "vendor");
        return createdPackage(post(apiKey, "/packages/agent-agent", body));
    }

    public CreatedPackage createDoorstepPackage(
            String apiKey,
            long senderAgentId,
            long doorstepDestinationId,
            String customerName,
            String customerPhoneNumber,
            String packageName,
            String locationDescription,
            Integer packageValue) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("senderAgentID_id", senderAgentId);
        body.put("doorstepDestinationId", doorstepDestinationId);
        body.put("customerName", customerName);
        body.put("customerPhoneNumber", customerPhoneNumber);
        body.put("packageName", packageName);
        if (locationDescription != null && !locationDescription.isBlank()) {
            body.put("locationDescription", locationDescription);
        }
        if (packageValue != null) {
            body.put("packageValue", packageValue);
        }
        body.put("paymentOption", "vendor");
        return createdPackage(post(apiKey, "/packages/doorstep", body));
    }

    public PackageView getAgentPackage(String apiKey, long id) {
        return packageView(get(apiKey, "/packages/agent-agent", query("id", id)));
    }

    public PackageView getDoorstepPackage(String apiKey, long id) {
        return packageView(get(apiKey, "/packages/doorstep", query("id", id)));
    }

    /**
     * Cancels an agent package. {@code id} is mandatory: the doorstep delete route
     * marks <em>every</em> package deleted when {@code id} is omitted (scope §13),
     * so a null id is refused before any request is built.
     */
    public void cancelAgentPackage(String apiKey, Long id) {
        requireIdForCancel(id);
        delete(apiKey, "/packages/agent-package", query("id", id));
    }

    public void cancelDoorstepPackage(String apiKey, Long id) {
        requireIdForCancel(id);
        delete(apiKey, "/packages/doorstep-package", query("id", id));
    }

    private static void requireIdForCancel(Long id) {
        if (id == null) {
            throw new IllegalArgumentException(
                    "Refusing to cancel Pickup Mtaani packages without an id");
        }
    }

    private CreatedPackage createdPackage(JsonNode root) {
        JsonNode data = dataNode(root);
        Long id = longOrNull(data, "id");
        if (id == null) {
            throw new PickupMtaaniApiException(
                    200, "NO_PACKAGE_ID", null,
                    "Pickup Mtaani accepted the request but returned no package id.");
        }
        return new CreatedPackage(
                id,
                textOrNull(data, "trackId"),
                textOrNull(data, "receipt_no"),
                textOrNull(data, "payment_status"),
                root == null ? null : root.toString());
    }

    private PackageView packageView(JsonNode root) {
        JsonNode data = dataNode(root);
        return new PackageView(
                textOrNull(data, "state"),
                textOrNull(data, "trackId"),
                textOrNull(data, "receipt_no"),
                textOrNull(data, "payment_status"),
                coerceDecimal(data == null ? null : data.path("delivery_fee")),
                lastTrackDescription(data),
                root == null ? null : root.toString());
    }

    /** The published spec only names tracks in examples; read the last description defensively. */
    private static String lastTrackDescription(JsonNode data) {
        if (data == null || !data.isObject()) {
            return null;
        }
        for (String key : List.of("agent_package_tracks", "door_step_package_tracks")) {
            JsonNode descriptions = data.path(key).path("descriptions");
            if (descriptions.isArray() && descriptions.size() > 0) {
                JsonNode last = descriptions.get(descriptions.size() - 1);
                String text = textOrNull(last, "descriptions");
                if (text == null) {
                    text = textOrNull(last, "description");
                }
                if (text == null) {
                    text = textOrNull(last, "state");
                }
                if (text != null) {
                    return text;
                }
            }
        }
        return null;
    }

    // ── HTTP plumbing ───────────────────────────────────────────────────────

    private JsonNode get(String apiKey, String path, Map<String, Object> query) {
        HttpResponse<String> response;
        try {
            var request = Unirest.get(properties.baseUrl() + path)
                    .header("apiKey", apiKey)
                    .header("Accept", "application/json")
                    .connectTimeout(properties.connectTimeoutMs())
                    .socketTimeout(properties.readTimeoutMs());
            if (query != null && !query.isEmpty()) {
                request = request.queryString(query);
            }
            response = request.asString();
        } catch (Exception ex) {
            throw new PickupMtaaniApiException(null, null, null, "network_error: " + ex.getMessage());
        }
        int status = response.getStatus();
        if (status < 200 || status >= 300) {
            log.warn("Pickup Mtaani {} failed: HTTP {} body={}", path, status, truncate(response.getBody()));
            throw errorFrom(status, response.getBody());
        }
        try {
            String body = response.getBody();
            return body == null || body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
        } catch (Exception ex) {
            throw new PickupMtaaniApiException(status, null, null, "Could not read the Pickup Mtaani response.");
        }
    }

    private JsonNode post(String apiKey, String path, Object body) {
        HttpResponse<String> response;
        try {
            response = Unirest.post(properties.baseUrl() + path)
                    .header("apiKey", apiKey)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .connectTimeout(properties.connectTimeoutMs())
                    .socketTimeout(properties.createTimeoutMs())
                    .body(writeJson(body))
                    .asString();
        } catch (Exception ex) {
            throw new PickupMtaaniApiException(null, null, null, "network_error: " + ex.getMessage());
        }
        int status = response.getStatus();
        if (status < 200 || status >= 300) {
            log.warn("Pickup Mtaani {} failed: HTTP {} body={}", path, status, truncate(response.getBody()));
            throw errorFrom(status, response.getBody());
        }
        try {
            String raw = response.getBody();
            return raw == null || raw.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(raw);
        } catch (Exception ex) {
            throw new PickupMtaaniApiException(status, null, null, "Could not read the Pickup Mtaani response.");
        }
    }

    private String writeJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception ex) {
            throw new PickupMtaaniApiException(null, null, null, "Could not build the Pickup Mtaani request.");
        }
    }

    private void delete(String apiKey, String path, Map<String, Object> query) {
        HttpResponse<String> response;
        try {
            var request = Unirest.delete(properties.baseUrl() + path)
                    .header("apiKey", apiKey)
                    .header("Accept", "application/json")
                    .connectTimeout(properties.connectTimeoutMs())
                    .socketTimeout(properties.readTimeoutMs());
            if (query != null && !query.isEmpty()) {
                request = request.queryString(query);
            }
            response = request.asString();
        } catch (Exception ex) {
            throw new PickupMtaaniApiException(null, null, null, "network_error: " + ex.getMessage());
        }
        int status = response.getStatus();
        if (status < 200 || status >= 300) {
            log.warn("Pickup Mtaani {} failed: HTTP {} body={}", path, status, truncate(response.getBody()));
            throw errorFrom(status, response.getBody());
        }
    }

    /**
     * Normalises the several error envelopes into one safe message plus the
     * machine fields worth keeping in logs.
     */
    PickupMtaaniApiException errorFrom(int status, String body) {
        String code = null;
        String requestId = null;
        String message = null;
        if (body != null && !body.isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(body);
                code = textOrNull(root, "code");
                requestId = firstTextOrNull(root, "request_id", "correlation_id", "requestId");
                JsonNode display = root.path("display");
                if (display.isObject()) {
                    message = textOrNull(display, "message");
                }
                if (isBlank(message)) {
                    message = textOrNull(root, "message");
                }
                if (isBlank(message)) {
                    JsonNode validation = root.path("validationErrors");
                    if (validation.isArray() && validation.size() > 0) {
                        JsonNode first = validation.get(0);
                        message = first.isTextual()
                                ? first.asText()
                                : firstTextOrNull(first, "message", "error", "detail");
                    }
                }
                if (isBlank(message)) {
                    message = textOrNull(root, "error");
                }
            } catch (Exception ignored) {
                // Non-JSON body: fall through to the generic message.
            }
        }
        String safe = isBlank(message)
                ? "Pickup Mtaani rejected the request (HTTP " + status + ")."
                : message.trim();
        return new PickupMtaaniApiException(status, code, requestId, safe);
    }

    // ── Parsing helpers ─────────────────────────────────────────────────────

    private List<GeoOption> options(JsonNode root) {
        JsonNode data = dataNode(root);
        if (data == null || !data.isArray()) {
            return List.of();
        }
        List<GeoOption> out = new ArrayList<>();
        for (JsonNode item : data) {
            Long id = longOrNull(item, "id");
            if (id == null) {
                continue;
            }
            out.add(new GeoOption(
                    id,
                    textOrNull(item, "name"),
                    firstLongOrNull(item, "locationId", "location_id"),
                    firstLongOrNull(item, "zoneId", "zone_id"),
                    firstLongOrNull(item, "areaId", "area_id", "areaID")
            ));
        }
        return out;
    }

    /** Returns {@code data} when present, else the root — coping with both shapes. */
    private static JsonNode dataNode(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return null;
        }
        JsonNode data = root.path("data");
        return data.isMissingNode() || data.isNull() ? root : data;
    }

    static Long longOrNull(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        return coerceLong(node.path(field));
    }

    static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        return coerceText(node.path(field));
    }

    private static Long firstLongOrNull(JsonNode node, String... fields) {
        for (String field : fields) {
            Long value = longOrNull(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String firstTextOrNull(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = textOrNull(node, field);
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static Integer intOrNull(JsonNode node, String field) {
        Long value = longOrNull(node, field);
        return value == null ? null : value.intValue();
    }

    private static Long coerceLong(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.longValue();
        }
        String text = node.asText("").trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String coerceText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asText();
        }
        String text = node.isTextual() ? node.asText() : null;
        return isBlank(text) ? null : text.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > MAX_LOGGED_BODY ? body.substring(0, MAX_LOGGED_BODY) + "…" : body;
    }

    private static Map<String, Object> query(String key, Object value) {
        Map<String, Object> q = new LinkedHashMap<>();
        put(q, key, value);
        return q;
    }

    private static void put(Map<String, Object> q, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String s && s.isBlank()) {
            return;
        }
        q.put(key, value);
    }

    // ── Records ─────────────────────────────────────────────────────────────

    public record AccountInfo(String mode, Integer businessCount, Integer maxBusinesses) {
    }

    public record BusinessInfo(Long id, String name, String phoneNumber, String kraPin) {
    }

    /** A resolved delivery fee in whole KES plus the raw body, for support. */
    public record DeliveryCharge(BigDecimal amountKes, String rawJson) {
    }

    /** Fields from a successful create. */
    public record CreatedPackage(
            Long id,
            String trackId,
            String receiptNo,
            String paymentStatus,
            String rawJson
    ) {
    }

    /** Fields read back from a package GET. Shape is only documented in examples. */
    public record PackageView(
            String state,
            String trackId,
            String receiptNo,
            String paymentStatus,
            BigDecimal deliveryFee,
            String lastTrackDescription,
            String rawJson
    ) {
    }

    /**
     * A zone, area, location, or agent. {@code zoneId}/{@code areaId}/{@code locationId}
     * are populated when the upstream row carries them, and may be null.
     */
    public record GeoOption(Long id, String name, Long locationId, Long zoneId, Long areaId) {
    }
}
