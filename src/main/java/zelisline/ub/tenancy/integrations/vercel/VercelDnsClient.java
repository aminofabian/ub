package zelisline.ub.tenancy.integrations.vercel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.application.PlatformDomainSettingsService;
import zelisline.ub.platform.application.ResolvedDomainIntegrationsConfig;

/**
 * Vercel DNS record CRUD for a zone that already uses Vercel nameservers
 * (or is about to).
 *
 * @see <a href="https://vercel.com/docs/rest-api/dns/create-a-dns-record">Create DNS record</a>
 */
@Component
@RequiredArgsConstructor
public class VercelDnsClient {

    private static final Logger log = LoggerFactory.getLogger(VercelDnsClient.class);

    /** Vercel anycast IPv4 for apex A records when ALIAS is unavailable. */
    public static final String APEX_A_TARGET = "76.76.21.21";

    /** Standard www CNAME target. */
    public static final String WWW_CNAME_TARGET = "cname.vercel-dns.com";

    private final PlatformDomainSettingsService domainSettingsService;
    private final ObjectMapper objectMapper;

    public boolean configured() {
        return cfg().vercelConfigured();
    }

    private ResolvedDomainIntegrationsConfig cfg() {
        return domainSettingsService.resolve();
    }

    public DnsResult ensureStorefrontRecords(String apex) {
        if (!configured()) {
            return DnsResult.skipped("vercel_not_configured");
        }
        String domain = requireHost(apex);
        List<String> created = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        DnsResult apexResult = createRecord(domain, "", "A", APEX_A_TARGET);
        if (apexResult.ok() || isDuplicate(apexResult)) {
            created.add("A @ → " + APEX_A_TARGET);
        } else if (!apexResult.skipped()) {
            // Try ALIAS as fallback for some plans.
            DnsResult alias = createRecord(domain, "", "ALIAS", WWW_CNAME_TARGET);
            if (alias.ok() || isDuplicate(alias)) {
                created.add("ALIAS @ → " + WWW_CNAME_TARGET);
            } else {
                errors.add("apex: " + apexResult.error());
            }
        }

        DnsResult www = createRecord(domain, "www", "CNAME", WWW_CNAME_TARGET);
        if (www.ok() || isDuplicate(www)) {
            created.add("CNAME www → " + WWW_CNAME_TARGET);
        } else if (!www.skipped()) {
            errors.add("www: " + www.error());
        }

        if (!errors.isEmpty() && created.isEmpty()) {
            return DnsResult.failed(String.join("; ", errors));
        }
        return DnsResult.ok(created, errors);
    }

    public DnsResult createRecord(String apex, String name, String type, String value) {
        if (!configured()) {
            return DnsResult.skipped("vercel_not_configured");
        }
        String domain = requireHost(apex);
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", name == null ? "" : name);
            body.put("type", type);
            body.put("value", value);
            body.put("ttl", 60);
            HttpResponse<String> response = Unirest.post(base() + "/v2/domains/" + domain + "/records")
                    .header("Authorization", "Bearer " + cfg().vercelToken().trim())
                    .header("Content-Type", "application/json")
                    .queryString(teamQuery())
                    .body(objectMapper.writeValueAsString(body))
                    .asString();
            if (response.getStatus() >= 200 && response.getStatus() < 300) {
                return DnsResult.ok(List.of(type + " " + blankToAt(name) + " → " + value), List.of());
            }
            if (response.getStatus() == 409) {
                return DnsResult.duplicate(type + " " + blankToAt(name));
            }
            log.warn("Vercel create DNS HTTP {} body={}", response.getStatus(), truncate(response.getBody()));
            return DnsResult.failed(formatFailure(response.getStatus(), response.getBody()), response.getStatus());
        } catch (Exception ex) {
            log.warn("Vercel create DNS failed: {}", ex.getMessage());
            return DnsResult.failed("error: " + ex.getMessage());
        }
    }

    public ListRecordsResult listRecords(String apex) {
        if (!configured()) {
            return ListRecordsResult.skipped("vercel_not_configured");
        }
        String domain = requireHost(apex);
        try {
            HttpResponse<String> response = Unirest.get(base() + "/v2/domains/" + domain + "/records")
                    .header("Authorization", "Bearer " + cfg().vercelToken().trim())
                    .queryString(teamQuery())
                    .asString();
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                return ListRecordsResult.failed(formatFailure(response.getStatus(), response.getBody()), response.getStatus());
            }
            JsonNode root = objectMapper.readTree(response.getBody() == null ? "{}" : response.getBody());
            JsonNode records = root.has("records") ? root.get("records") : root;
            List<Map<String, String>> out = new ArrayList<>();
            if (records != null && records.isArray()) {
                for (JsonNode r : records) {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("id", text(r, "id"));
                    row.put("name", text(r, "name"));
                    row.put("type", text(r, "type"));
                    row.put("value", text(r, "value"));
                    out.add(row);
                }
            }
            return ListRecordsResult.ok(out);
        } catch (Exception ex) {
            return ListRecordsResult.failed("error: " + ex.getMessage());
        }
    }

    private static boolean isDuplicate(DnsResult result) {
        return result != null && result.duplicate();
    }

    private String base() {
        return cfg().vercelApiBaseUrl() == null || cfg().vercelApiBaseUrl().isBlank()
                ? "https://api.vercel.com"
                : cfg().vercelApiBaseUrl().trim().replaceAll("/+$", "");
    }

    private Map<String, Object> teamQuery() {
        Map<String, Object> q = new LinkedHashMap<>();
        if (cfg().vercelTeamId() != null && !cfg().vercelTeamId().isBlank()) {
            q.put("teamId", cfg().vercelTeamId().trim());
        }
        return q;
    }

    private static String requireHost(String hostname) {
        if (hostname == null || hostname.isBlank()) {
            throw new IllegalArgumentException("hostname required");
        }
        return hostname.trim().toLowerCase(Locale.ROOT);
    }

    private static String blankToAt(String name) {
        return name == null || name.isBlank() ? "@" : name;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).asText();
    }

    private static String formatFailure(int status, String body) {
        return "http_" + status + (body == null || body.isBlank() ? "" : ": " + truncate(body));
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 400 ? s : s.substring(0, 400) + "…";
    }

    public record DnsResult(
            boolean ok,
            boolean skipped,
            boolean duplicate,
            List<String> created,
            List<String> warnings,
            String error,
            Integer httpStatus
    ) {
        public static DnsResult skipped(String reason) {
            return new DnsResult(false, true, false, List.of(), List.of(), reason, null);
        }

        public static DnsResult duplicate(String label) {
            return new DnsResult(true, false, true, List.of(label + " (exists)"), List.of(), null, 409);
        }

        public static DnsResult failed(String error) {
            return failed(error, null);
        }

        public static DnsResult failed(String error, Integer httpStatus) {
            return new DnsResult(false, false, false, List.of(), List.of(), error, httpStatus);
        }

        public static DnsResult ok(List<String> created, List<String> warnings) {
            return new DnsResult(true, false, false, created, warnings, null, null);
        }
    }

    public record ListRecordsResult(
            boolean ok,
            boolean skipped,
            List<Map<String, String>> records,
            String error,
            Integer httpStatus
    ) {
        public static ListRecordsResult skipped(String reason) {
            return new ListRecordsResult(false, true, List.of(), reason, null);
        }

        public static ListRecordsResult failed(String error) {
            return failed(error, null);
        }

        public static ListRecordsResult failed(String error, Integer httpStatus) {
            return new ListRecordsResult(false, false, List.of(), error, httpStatus);
        }

        public static ListRecordsResult ok(List<Map<String, String>> records) {
            return new ListRecordsResult(true, false, records, null, null);
        }
    }
}
