package zelisline.ub.tenancy.integrations.vercel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 * Vercel project domains: attach hostname + verify ownership / SSL.
 *
 * @see <a href="https://vercel.com/docs/rest-api/projects/add-a-domain-to-a-project">Add domain</a>
 * @see <a href="https://vercel.com/docs/rest-api/projects/verify-project-domain">Verify domain</a>
 */
@Component
@RequiredArgsConstructor
public class VercelProjectDomainClient {

    private static final Logger log = LoggerFactory.getLogger(VercelProjectDomainClient.class);

    private final PlatformDomainSettingsService domainSettingsService;
    private final ObjectMapper objectMapper;

    public boolean configured() {
        return cfg().vercelConfigured();
    }

    private ResolvedDomainIntegrationsConfig cfg() {
        return domainSettingsService.resolve();
    }

    /**
     * Attaches {@code hostname} to the configured Vercel project.
     * Idempotent when Vercel reports the domain already exists on the project.
     */
    public ProjectDomainResult addDomain(String hostname) {
        if (!configured()) {
            return ProjectDomainResult.skipped("vercel_not_configured");
        }
        String name = requireHost(hostname);
        String url = projectUrl("/domains");
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", name);
            HttpResponse<String> response = Unirest.post(url)
                    .header("Authorization", "Bearer " + cfg().vercelToken().trim())
                    .header("Content-Type", "application/json")
                    .queryString(teamQuery())
                    .body(objectMapper.writeValueAsString(body))
                    .asString();
            if (response.getStatus() >= 200 && response.getStatus() < 300) {
                return parseDomainPayload(response.getBody(), false);
            }
            // Already on project — treat as success and re-fetch.
            if (response.getStatus() == 409) {
                return getDomain(name).orElse(ProjectDomainResult.failed(
                        "domain_exists_but_unreadable", response.getStatus()));
            }
            log.warn("Vercel add domain HTTP {} body={}", response.getStatus(), truncate(response.getBody()));
            return ProjectDomainResult.failed(formatFailure(response.getStatus(), response.getBody()), response.getStatus());
        } catch (Exception ex) {
            log.warn("Vercel add domain failed: {}", ex.getMessage());
            return ProjectDomainResult.failed("error: " + ex.getMessage());
        }
    }

    public Optional<ProjectDomainResult> getDomain(String hostname) {
        if (!configured()) {
            return Optional.empty();
        }
        String name = requireHost(hostname);
        String url = projectDomainUrl(name, "");
        try {
            HttpResponse<String> response = Unirest.get(url)
                    .header("Authorization", "Bearer " + cfg().vercelToken().trim())
                    .queryString(teamQuery())
                    .asString();
            if (response.getStatus() == 404) {
                return Optional.empty();
            }
            if (response.getStatus() >= 200 && response.getStatus() < 300) {
                return Optional.of(parseDomainPayload(response.getBody(), false));
            }
            log.warn("Vercel get domain HTTP {} body={}", response.getStatus(), truncate(response.getBody()));
            return Optional.of(ProjectDomainResult.failed(
                    formatFailure(response.getStatus(), response.getBody()), response.getStatus()));
        } catch (Exception ex) {
            log.warn("Vercel get domain failed: {}", ex.getMessage());
            return Optional.of(ProjectDomainResult.failed("error: " + ex.getMessage()));
        }
    }

    public ProjectDomainResult verifyDomain(String hostname) {
        if (!configured()) {
            return ProjectDomainResult.skipped("vercel_not_configured");
        }
        String name = requireHost(hostname);
        String url = projectDomainUrl(name, "/verify");
        try {
            HttpResponse<String> response = Unirest.post(url)
                    .header("Authorization", "Bearer " + cfg().vercelToken().trim())
                    .header("Content-Type", "application/json")
                    .queryString(teamQuery())
                    .asString();
            if (response.getStatus() >= 200 && response.getStatus() < 300) {
                return parseDomainPayload(response.getBody(), true);
            }
            log.warn("Vercel verify domain HTTP {} body={}", response.getStatus(), truncate(response.getBody()));
            return ProjectDomainResult.failed(formatFailure(response.getStatus(), response.getBody()), response.getStatus());
        } catch (Exception ex) {
            log.warn("Vercel verify domain failed: {}", ex.getMessage());
            return ProjectDomainResult.failed("error: " + ex.getMessage());
        }
    }

    public ProjectDomainResult removeDomain(String hostname) {
        if (!configured()) {
            return ProjectDomainResult.skipped("vercel_not_configured");
        }
        String name = requireHost(hostname);
        String url = projectDomainUrl(name, "");
        try {
            HttpResponse<String> response = Unirest.delete(url)
                    .header("Authorization", "Bearer " + cfg().vercelToken().trim())
                    .queryString(teamQuery())
                    .asString();
            if (response.getStatus() == 404
                    || (response.getStatus() >= 200 && response.getStatus() < 300)) {
                return ProjectDomainResult.ok(name, true, true, List.of(), null);
            }
            log.warn("Vercel remove domain HTTP {} body={}", response.getStatus(), truncate(response.getBody()));
            return ProjectDomainResult.failed(formatFailure(response.getStatus(), response.getBody()), response.getStatus());
        } catch (Exception ex) {
            log.warn("Vercel remove domain failed: {}", ex.getMessage());
            return ProjectDomainResult.failed("error: " + ex.getMessage());
        }
    }

    private String projectUrl(String suffix) {
        String base = cfg().vercelApiBaseUrl() == null || cfg().vercelApiBaseUrl().isBlank()
                ? "https://api.vercel.com"
                : cfg().vercelApiBaseUrl().trim().replaceAll("/+$", "");
        return base + "/v10/projects/" + cfg().vercelProjectId().trim() + suffix;
    }

    private String projectDomainUrl(String hostname, String actionSuffix) {
        String base = cfg().vercelApiBaseUrl() == null || cfg().vercelApiBaseUrl().isBlank()
                ? "https://api.vercel.com"
                : cfg().vercelApiBaseUrl().trim().replaceAll("/+$", "");
        return base + "/v9/projects/" + cfg().vercelProjectId().trim()
                + "/domains/" + hostname + actionSuffix;
    }

    private Map<String, Object> teamQuery() {
        Map<String, Object> q = new LinkedHashMap<>();
        if (cfg().vercelTeamId() != null && !cfg().vercelTeamId().isBlank()) {
            q.put("teamId", cfg().vercelTeamId().trim());
        }
        return q;
    }

    private ProjectDomainResult parseDomainPayload(String raw, boolean fromVerify) throws Exception {
        JsonNode root = objectMapper.readTree(raw == null ? "{}" : raw);
        // Some endpoints wrap under "domain"; others return the domain object at root.
        JsonNode domain = root.has("domain") ? root.get("domain") : root;
        String name = text(domain, "name");
        if (name == null || name.isBlank()) {
            name = text(root, "name");
        }
        boolean verified = bool(domain, "verified") || bool(root, "verified");
        List<DnsChallenge> challenges = parseChallenges(domain.has("verification")
                ? domain.get("verification")
                : root.get("verification"));
        Map<String, Object> instructions = new LinkedHashMap<>();
        instructions.put("provider", "vercel");
        instructions.put("hostname", name);
        instructions.put("verified", verified);
        if (!challenges.isEmpty()) {
            instructions.put("challenges", challenges.stream().map(DnsChallenge::toMap).toList());
        }
        // Recommended BYO records when Vercel is not yet the nameserver.
        List<Map<String, String>> recommended = new ArrayList<>();
        recommended.add(Map.of(
                "type", "CNAME",
                "name", "www".equals(apexLabel(name)) ? "www" : name.contains(".") ? name.substring(0, name.indexOf('.')) : "@",
                "value", "cname.vercel-dns.com"
        ));
        if (isApex(name)) {
            recommended.add(Map.of(
                    "type", "A",
                    "name", "@",
                    "value", "76.76.21.21"
            ));
        }
        instructions.put("recommendedRecords", recommended);
        return new ProjectDomainResult(
                true,
                false,
                name,
                verified,
                fromVerify || verified,
                challenges,
                instructions,
                null,
                null
        );
    }

    private static List<DnsChallenge> parseChallenges(JsonNode node) {
        List<DnsChallenge> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode c : node) {
            out.add(new DnsChallenge(
                    text(c, "type"),
                    text(c, "domain"),
                    text(c, "value"),
                    text(c, "reason")
            ));
        }
        return out;
    }

    private static boolean isApex(String host) {
        if (host == null) {
            return false;
        }
        String[] parts = host.split("\\.");
        // co.ke style: acme.co.ke → 3 labels; shop.acme.co.ke → 4
        if (parts.length >= 2 && "ke".equals(parts[parts.length - 1]) && parts[parts.length - 2].length() <= 3) {
            return parts.length == 3;
        }
        return parts.length == 2;
    }

    private static String apexLabel(String host) {
        if (host == null) {
            return "";
        }
        int i = host.indexOf('.');
        return i < 0 ? host : host.substring(0, i);
    }

    private static String requireHost(String hostname) {
        if (hostname == null || hostname.isBlank()) {
            throw new IllegalArgumentException("hostname required");
        }
        return hostname.trim().toLowerCase();
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).asText();
    }

    private static boolean bool(JsonNode node, String field) {
        return node != null && node.has(field) && node.get(field).asBoolean(false);
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

    public record DnsChallenge(String type, String domain, String value, String reason) {
        Map<String, String> toMap() {
            Map<String, String> m = new LinkedHashMap<>();
            if (type != null) {
                m.put("type", type);
            }
            if (domain != null) {
                m.put("domain", domain);
            }
            if (value != null) {
                m.put("value", value);
            }
            if (reason != null) {
                m.put("reason", reason);
            }
            return m;
        }
    }

    public record ProjectDomainResult(
            boolean ok,
            boolean skipped,
            String name,
            boolean verified,
            boolean verifyAttempted,
            List<DnsChallenge> challenges,
            Map<String, Object> dnsInstructions,
            String error,
            Integer httpStatus
    ) {
        public static ProjectDomainResult skipped(String reason) {
            return new ProjectDomainResult(false, true, null, false, false, List.of(), Map.of(), reason, null);
        }

        public static ProjectDomainResult failed(String error) {
            return failed(error, null);
        }

        public static ProjectDomainResult failed(String error, Integer httpStatus) {
            return new ProjectDomainResult(false, false, null, false, false, List.of(), Map.of(), error, httpStatus);
        }

        public static ProjectDomainResult ok(
                String name,
                boolean verified,
                boolean verifyAttempted,
                List<DnsChallenge> challenges,
                Map<String, Object> dnsInstructions
        ) {
            return new ProjectDomainResult(
                    true, false, name, verified, verifyAttempted, challenges,
                    dnsInstructions == null ? Map.of() : dnsInstructions, null, null);
        }
    }
}
