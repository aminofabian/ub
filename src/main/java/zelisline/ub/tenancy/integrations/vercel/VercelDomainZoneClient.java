package zelisline.ub.tenancy.integrations.vercel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

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
 * Vercel platform DNS zones ({@code POST /v5/domains} / {@code GET /v5/domains/{domain}}).
 * Creating a zone returns the intended nameservers merchants/ops must set at the registrar.
 */
@Component
@RequiredArgsConstructor
public class VercelDomainZoneClient {

    private static final Logger log = LoggerFactory.getLogger(VercelDomainZoneClient.class);

    private final PlatformDomainSettingsService domainSettingsService;
    private final ObjectMapper objectMapper;

    public boolean configured() {
        return cfg().vercelConfigured();
    }

    private ResolvedDomainIntegrationsConfig cfg() {
        return domainSettingsService.resolve();
    }

    public ZoneResult addZone(String apex) {
        if (!configured()) {
            return ZoneResult.skipped("vercel_not_configured");
        }
        String name = requireHost(apex);
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", name);
            HttpResponse<String> response = Unirest.post(base() + "/v5/domains")
                    .header("Authorization", "Bearer " + cfg().vercelToken().trim())
                    .header("Content-Type", "application/json")
                    .queryString(teamQuery())
                    .body(objectMapper.writeValueAsString(body))
                    .asString();
            if (response.getStatus() == 409) {
                return getZone(name).orElse(ZoneResult.failed("zone_exists_but_unreadable", 409));
            }
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                log.warn("Vercel add zone HTTP {} body={}", response.getStatus(), truncate(response.getBody()));
                return ZoneResult.failed(formatFailure(response.getStatus(), response.getBody()), response.getStatus());
            }
            return parseZone(response.getBody());
        } catch (Exception ex) {
            log.warn("Vercel add zone failed: {}", ex.getMessage());
            return ZoneResult.failed("error: " + ex.getMessage());
        }
    }

    public Optional<ZoneResult> getZone(String apex) {
        if (!configured()) {
            return Optional.empty();
        }
        String name = requireHost(apex);
        try {
            HttpResponse<String> response = Unirest.get(base() + "/v5/domains/" + name)
                    .header("Authorization", "Bearer " + cfg().vercelToken().trim())
                    .queryString(teamQuery())
                    .asString();
            if (response.getStatus() == 404) {
                return Optional.empty();
            }
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                return Optional.of(ZoneResult.failed(formatFailure(response.getStatus(), response.getBody()), response.getStatus()));
            }
            return Optional.of(parseZone(response.getBody()));
        } catch (Exception ex) {
            return Optional.of(ZoneResult.failed("error: " + ex.getMessage()));
        }
    }

    private ZoneResult parseZone(String raw) throws Exception {
        JsonNode root = objectMapper.readTree(raw == null ? "{}" : raw);
        String name = text(root, "name");
        List<String> intended = new ArrayList<>();
        JsonNode ns = root.get("intendedNameservers");
        if (ns != null && ns.isArray()) {
            for (JsonNode n : ns) {
                if (n != null && !n.asText("").isBlank()) {
                    intended.add(n.asText().trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        List<String> current = new ArrayList<>();
        JsonNode cns = root.get("nameservers");
        if (cns != null && cns.isArray()) {
            for (JsonNode n : cns) {
                if (n != null && !n.asText("").isBlank()) {
                    current.add(n.asText().trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        boolean verified = root.path("verified").asBoolean(false);
        return ZoneResult.ok(name, intended, current, verified);
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

    public record ZoneResult(
            boolean ok,
            boolean skipped,
            String name,
            List<String> intendedNameservers,
            List<String> nameservers,
            boolean verified,
            String error,
            Integer httpStatus
    ) {
        public static ZoneResult skipped(String reason) {
            return new ZoneResult(false, true, null, List.of(), List.of(), false, reason, null);
        }

        public static ZoneResult failed(String error) {
            return failed(error, null);
        }

        public static ZoneResult failed(String error, Integer httpStatus) {
            return new ZoneResult(false, false, null, List.of(), List.of(), false, error, httpStatus);
        }

        public static ZoneResult ok(
                String name,
                List<String> intended,
                List<String> current,
                boolean verified
        ) {
            return new ZoneResult(true, false, name, intended, current, verified, null, null);
        }

        public boolean nameserversMatch() {
            if (intendedNameservers == null || intendedNameservers.isEmpty()
                    || nameservers == null || nameservers.isEmpty()) {
                return false;
            }
            return nameservers.containsAll(intendedNameservers)
                    || intendedNameservers.containsAll(nameservers);
        }
    }
}
