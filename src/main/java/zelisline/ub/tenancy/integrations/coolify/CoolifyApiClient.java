package zelisline.ub.tenancy.integrations.coolify;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import zelisline.ub.tenancy.integrations.storefront.StorefrontDnsInstructions;
import zelisline.ub.tenancy.integrations.storefront.StorefrontDomainsProperties;

/**
 * Coolify REST client for the storefront app's FQDN list + deploy trigger.
 * Domain list mutations are serialised — concurrent PATCHes can drop entries.
 */
@Component
public class CoolifyApiClient {

    private static final Logger log = LoggerFactory.getLogger(CoolifyApiClient.class);

    private final StorefrontDomainsProperties properties;
    private final ObjectMapper objectMapper;
    private final Object lock = new Object();

    public CoolifyApiClient(StorefrontDomainsProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public boolean configured() {
        return properties.getCoolify().configured();
    }

    /**
     * Ensures {@code https://host} and {@code https://www.host} (for apex) are
     * on the app FQDN list, then triggers a redeploy so Traefik labels update.
     */
    public MutationResult ensureHttpsHosts(String hostname) {
        synchronized (lock) {
            if (!configured()) {
                return MutationResult.failed("coolify_not_configured");
            }
            String host = hostname.trim().toLowerCase(Locale.ROOT);
            try {
                String current = readFqdn();
                Set<String> entries = parseFqdn(current);
                entries.add("https://" + host);
                if (!host.startsWith("www.") && StorefrontDnsInstructions.isLikelyApex(host)) {
                    entries.add("https://www." + host);
                }
                String next = String.join(",", entries);
                if (!next.equals(current)) {
                    patchFqdn(next);
                }
                triggerDeploy();
                return MutationResult.ok(next);
            } catch (CoolifyApiException ex) {
                if (ex.httpStatus != null && (ex.httpStatus == 401 || ex.httpStatus == 403)) {
                    logConfigMismatch(ex.httpStatus);
                }
                log.warn("Coolify ensure hosts failed for {}: {}", host, ex.getMessage());
                return MutationResult.failed(ex.getMessage(), ex.httpStatus);
            } catch (Exception ex) {
                log.warn("Coolify ensure hosts failed for {}: {}", host, ex.getMessage());
                return MutationResult.failed("error: " + ex.getMessage());
            }
        }
    }

    public MutationResult removeHttpsHosts(String hostname) {
        synchronized (lock) {
            if (!configured()) {
                return MutationResult.failed("coolify_not_configured");
            }
            String host = hostname.trim().toLowerCase(Locale.ROOT);
            try {
                String current = readFqdn();
                Set<String> entries = parseFqdn(current);
                entries.removeIf(e -> {
                    String h = stripScheme(e);
                    return h.equals(host) || h.equals("www." + host);
                });
                // Never drop the platform hosts accidentally if somehow only those remain.
                String next = String.join(",", entries);
                if (!next.equals(current)) {
                    patchFqdn(next);
                    triggerDeploy();
                }
                return MutationResult.ok(next);
            } catch (CoolifyApiException ex) {
                log.warn("Coolify remove hosts failed for {}: {}", host, ex.getMessage());
                return MutationResult.failed(ex.getMessage(), ex.httpStatus);
            } catch (Exception ex) {
                log.warn("Coolify remove hosts failed for {}: {}", host, ex.getMessage());
                return MutationResult.failed("error: " + ex.getMessage());
            }
        }
    }

    private String readFqdn() throws Exception {
        String url = appUrl();
        HttpResponse<String> response = Unirest.get(url)
                .header("Authorization", "Bearer " + token())
                .header("Accept", "application/json")
                .asString();
        if (response.getStatus() < 200 || response.getStatus() >= 300) {
            throw new CoolifyApiException(
                    "Coolify GET application HTTP " + response.getStatus()
                            + suffix(response.getBody()),
                    response.getStatus());
        }
        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode fqdn = root == null ? null : root.get("fqdn");
        if (fqdn == null || fqdn.isNull()) {
            // Newer Coolify serialises the list as `domains`.
            JsonNode domains = root == null ? null : root.get("domains");
            if (domains != null && domains.isArray()) {
                StringBuilder joined = new StringBuilder();
                for (JsonNode entry : domains) {
                    if (!joined.isEmpty()) {
                        joined.append(',');
                    }
                    joined.append(entry.asText(""));
                }
                return joined.toString();
            }
            return domains == null || domains.isNull() ? "" : domains.asText("");
        }
        return fqdn.asText("");
    }

    /**
     * Newer Coolify renamed the writable field to {@code domains} (and rejects
     * {@code fqdn} as an unknown field with 422). Keep both shapes so the same
     * jar works against current and older instances.
     */
    private void patchFqdn(String fqdn) throws Exception {
        HttpResponse<String> response = patchDomainList("fqdn", fqdn);
        if (response.getStatus() == 422 && mentionsDomainsField(response.getBody())) {
            log.info("Coolify rejected `fqdn`; retrying PATCH with `domains`.");
            response = patchDomainList("domains", fqdn);
        }
        if (response.getStatus() < 200 || response.getStatus() >= 300) {
            throw new CoolifyApiException(
                    "Coolify PATCH fqdn HTTP " + response.getStatus() + suffix(response.getBody()),
                    response.getStatus());
        }
    }

    private HttpResponse<String> patchDomainList(String field, String fqdn) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put(field, fqdn);
        return Unirest.patch(appUrl())
                .header("Authorization", "Bearer " + token())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .body(objectMapper.writeValueAsString(body))
                .asString();
    }

    private static boolean mentionsDomainsField(String body) {
        return body != null && body.toLowerCase(Locale.ROOT).contains("domains");
    }

    /**
     * Coolify v4 only accepts {@code POST /deploy} (a GET returns a
     * {@code post_required} stub). Older builds exposed GET, so fall back once.
     */
    private void triggerDeploy() {
        String base = baseUrl();
        String uuid = properties.getCoolify().getAppUuid().trim();
        String url = base + "/deploy?uuid=" + uuid + "&force=false";
        try {
            HttpResponse<String> response = Unirest.post(url)
                    .header("Authorization", "Bearer " + token())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .body("{}")
                    .asString();
            if (response.getStatus() == 404 || response.getStatus() == 405) {
                response = Unirest.get(url)
                        .header("Authorization", "Bearer " + token())
                        .header("Accept", "application/json")
                        .asString();
            }
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                // Write-only tokens can't deploy; FQDN PATCH may still update Traefik on next restart.
                log.warn(
                        "Coolify deploy trigger HTTP {} (app={}) — FQDN was patched; redeploy from Coolify UI if labels lag. body={}",
                        response.getStatus(),
                        uuid,
                        truncate(response.getBody()));
            }
        } catch (Exception ex) {
            log.warn("Coolify deploy trigger failed: {}", ex.getMessage());
        }
    }

    private String appUrl() {
        return baseUrl() + "/applications/" + properties.getCoolify().getAppUuid().trim();
    }

    private String baseUrl() {
        String raw = properties.getCoolify().getApiUrl().trim();
        while (raw.endsWith("/")) {
            raw = raw.substring(0, raw.length() - 1);
        }
        // COOLIFY_API_URL is often pasted as the dashboard URL; the REST base is /api/v1.
        if (!raw.matches("(?i).*/api/v1$")) {
            raw = raw + "/api/v1";
        }
        return raw;
    }

    private String token() {
        return properties.getCoolify().getApiToken().trim();
    }

    private static Set<String> parseFqdn(String fqdn) {
        if (fqdn == null || fqdn.isBlank()) {
            return new LinkedHashSet<>();
        }
        return Arrays.stream(fqdn.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String stripScheme(String entry) {
        String e = entry.trim().toLowerCase(Locale.ROOT);
        if (e.startsWith("https://")) {
            return e.substring("https://".length());
        }
        if (e.startsWith("http://")) {
            return e.substring("http://".length());
        }
        return e;
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 240 ? body : body.substring(0, 240);
    }

    private static String suffix(String body) {
        String compact = truncate(body);
        return compact.isBlank() ? "" : ": " + compact;
    }

    /** One line that makes a cross-instance / wrong-team token obvious in the API log. */
    private void logConfigMismatch(int status) {
        String token = token();
        String tail = token.length() <= 4 ? "…" : token.substring(token.length() - 4);
        log.warn(
                "Coolify auth failed (HTTP {}). api={} app={} token=…{} ({} chars). "
                        + "A token is only valid on the instance that issued it — use Super Admin → "
                        + "Platform → Domains → Coolify → Test connection for the exact cause.",
                status,
                baseUrl(),
                properties.getCoolify().getAppUuid().trim(),
                tail,
                token.length());
    }

    public record MutationResult(boolean ok, String fqdn, String error, Integer httpStatus) {
        public static MutationResult ok(String fqdn) {
            return new MutationResult(true, fqdn, null, null);
        }

        public static MutationResult failed(String error) {
            return failed(error, null);
        }

        public static MutationResult failed(String error, Integer httpStatus) {
            return new MutationResult(false, null, error, httpStatus);
        }
    }

    private static final class CoolifyApiException extends Exception {
        private final Integer httpStatus;

        CoolifyApiException(String message, Integer httpStatus) {
            super(message);
            this.httpStatus = httpStatus;
        }
    }

}
