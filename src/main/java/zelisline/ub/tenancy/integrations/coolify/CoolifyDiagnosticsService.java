package zelisline.ub.tenancy.integrations.coolify;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.tenancy.integrations.storefront.StorefrontDomainsProperties;

/**
 * Read-only "why is Coolify rejecting us?" probe for Super Admin → Platform →
 * Domains. Never mutates the Coolify app: it only calls {@code /version} and
 * {@code /applications/{uuid}} and turns the upstream body into an actionable
 * hint (wrong instance token, missing ability, disabled API, wrong app UUID).
 *
 * <p>Coolify answers 401 with two very different bodies:
 * {@code Unauthenticated.} (token unknown to that instance) and
 * {@code Invalid token.} (token row not scoped to a team / creator no longer a
 * member). Both used to surface in the UI as a bare "HTTP 401".
 */
@Service
public class CoolifyDiagnosticsService {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    private final StorefrontDomainsProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public CoolifyDiagnosticsService(StorefrontDomainsProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public CoolifyDiagnostic diagnose() {
        var coolify = properties.getCoolify();
        String apiUrl = trim(coolify.getApiUrl());
        String appUuid = trim(coolify.getAppUuid());
        String token = trim(coolify.getApiToken());
        boolean configured = !apiUrl.isEmpty() && !appUuid.isEmpty() && !token.isEmpty();

        List<String> checks = new ArrayList<>();
        checks.add("provider=" + trim(properties.getProvider()));
        checks.add("api_url=" + (apiUrl.isEmpty() ? "(missing)" : apiUrl));
        checks.add("app_uuid=" + (appUuid.isEmpty() ? "(missing)" : appUuid));
        checks.add("api_token=" + fingerprint(token));

        if (!configured) {
            String missing = missingConfig(apiUrl, appUuid, token);
            return new CoolifyDiagnostic(
                    false, apiUrl, appUuid, fingerprint(token),
                    null, null, null, null, null, null,
                    false,
                    "Coolify is not configured in the Java API environment. Missing: " + missing
                            + ". Set COOLIFY_API_URL (include /api/v1), COOLIFY_API_TOKEN and "
                            + "COOLIFY_APP_UUID on the API service, then restart it.",
                    checks);
        }

        String base = normalizeBase(apiUrl);
        Probe version = get(base + "/version", token);
        checks.add(describe("GET /version", version));
        Probe app = get(base + "/applications/" + appUuid, token);
        checks.add(describe("GET /applications/{uuid}", app));

        String appName = null;
        String appFqdn = null;
        if (app.ok()) {
            JsonNode json = readJson(app.body());
            if (json != null) {
                appName = text(json, "name");
                appFqdn = text(json, "fqdn");
            }
        }

        String hint = hintFor(apiUrl, base, appUuid, version, app);
        boolean ok = app.ok();
        return new CoolifyDiagnostic(
                true, apiUrl, appUuid, fingerprint(token),
                version.status(), messageOf(version),
                app.status(), messageOf(app),
                appName, appFqdn,
                ok, hint, checks);
    }

    private String hintFor(String rawApiUrl, String base, String appUuid, Probe version, Probe app) {
        if (app.transportError() != null) {
            return "The Java API could not reach " + base + " (" + app.transportError()
                    + "). Check COOLIFY_API_URL, the port, and that the API server may call this host. "
                    + "The domain flow cannot attach hostnames until this succeeds.";
        }

        String message = messageOf(app);
        int status = app.status();
        String lower = message.toLowerCase();

        if (status >= 200 && status < 300) {
            return "Coolify accepted the token and the application is visible on this instance. "
                    + "Attach/redeploy is wired correctly; DNS still has to point at "
                    + properties.resolvedATarget() + " before the certificate can issue.";
        }

        if (status == 401 && lower.contains("invalid token")) {
            return "Coolify says the token is not scoped to a team member (\"Invalid token.\"). "
                    + "This happens to tokens created before team-scoped API tokens, or when the user who "
                    + "created it was removed from the team. Open " + base
                    + " as an owner and create a fresh token under Keys & Tokens → API tokens with "
                    + "read + write + deploy, then replace COOLIFY_API_TOKEN and restart the API.";
        }
        if (status == 401) {
            return "Coolify does not recognise this token on " + base + " (\"Unauthenticated.\"). "
                    + "A token is only valid on the Coolify instance that created it, so if you run more "
                    + "than one instance, create the token on the same host as COOLIFY_API_URL — as a "
                    + "team owner, with read + write + deploy — and make sure COOLIFY_APP_UUID is the "
                    + "FRONTEND app on that host (the one Traefik routes merchant domains to).";
        }
        if (status == 403 && lower.contains("api is disabled")) {
            return "The Coolify API is disabled on this instance. As a root user, enable it under "
                    + "Settings → API (or POST " + base + "/enable) and retry.";
        }
        if (status == 403 && lower.contains("missing required permissions")) {
            return "The token exists but lacks the required ability (Coolify reports: " + message
                    + "). Recreate it with read + write + deploy so FQDN patching and redeploys work.";
        }
        if (status == 403 && lower.contains("team role")) {
            return "The token belongs to a team member, not an owner/admin. Coolify restricts write and "
                    + "deploy abilities to owners/admins. Create the token while logged in as the team owner.";
        }
        if (status == 404) {
            String detail = message.isBlank() ? "" : " Coolify said: " + message + ".";
            return "App UUID " + appUuid + " was not found on " + base + "." + detail
                    + " The UUID probably belongs to the other Coolify instance. Use the FRONTEND app UUID "
                    + "on the instance that serves the storefront (the one Traefik routes the custom domain to).";
        }
        if (status == 301 || status == 302 || status == 307 || status == 308) {
            return "COOLIFY_API_URL points at the dashboard, not the API (Coolify redirected the request). "
                    + "Use " + suggestApiUrl(rawApiUrl) + ".";
        }
        if (version.transportError() != null) {
            return "Could not reach Coolify at " + base + " (" + version.transportError()
                    + "). Fix COOLIFY_API_URL first.";
        }
        return "Coolify answered HTTP " + status + (message.isBlank() ? "" : " (" + message + ")")
                + " for " + base + ". Check the API URL, app UUID and token belong to the same instance.";
    }

    private Probe get(String url, String token) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return new Probe(response.statusCode(), safeBody(response.body()), null);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new Probe(0, null, "interrupted");
        } catch (Exception ex) {
            return new Probe(0, null, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private String messageOf(Probe probe) {
        if (probe.status() == 0) {
            return probe.transportError() == null ? "" : probe.transportError();
        }
        String body = probe.body();
        if (body == null || body.isBlank()) {
            return "";
        }
        JsonNode json = readJson(body);
        if (json != null) {
            String message = text(json, "message");
            if (message != null && !message.isBlank()) {
                return message;
            }
            String error = text(json, "error");
            if (error != null && !error.isBlank()) {
                return error;
            }
        }
        String compact = body.replaceAll("\\s+", " ").trim();
        if (compact.startsWith("<")) {
            return "HTML response (not the Coolify API)";
        }
        return compact.length() <= 200 ? compact : compact.substring(0, 200) + "…";
    }

    private String describe(String label, Probe probe) {
        if (probe.status() == 0) {
            return label + " → unreachable (" + probe.transportError() + ")";
        }
        String message = messageOf(probe);
        return label + " → " + probe.status() + (message.isBlank() ? "" : ": " + message);
    }

    private JsonNode readJson(String body) {
        if (body == null || body.isBlank() || body.stripLeading().startsWith("<")) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String fingerprint(String token) {
        if (token == null || token.isBlank()) {
            return "(missing)";
        }
        String trimmed = token.trim();
        String tail = trimmed.length() <= 4 ? trimmed : trimmed.substring(trimmed.length() - 4);
        return "…" + tail + " (" + trimmed.length() + " chars)";
    }

    private static String missingConfig(String apiUrl, String appUuid, String token) {
        List<String> missing = new ArrayList<>();
        if (apiUrl.isEmpty()) {
            missing.add("COOLIFY_API_URL");
        }
        if (token.isEmpty()) {
            missing.add("COOLIFY_API_TOKEN");
        }
        if (appUuid.isEmpty()) {
            missing.add("COOLIFY_APP_UUID");
        }
        return String.join(", ", missing);
    }

    private static String suggestApiUrl(String raw) {
        String base = stripTrailingSlash(trim(raw));
        if (!base.matches("(?i).*/api/v1$")) {
            base = base + "/api/v1";
        }
        return base;
    }

    /** Mirrors {@code CoolifyApiClient.baseUrl()} so the probe hits the same endpoint. */
    private static String normalizeBase(String raw) {
        return suggestApiUrl(raw);
    }

    private static String stripTrailingSlash(String value) {
        String out = value;
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String safeBody(String body) {
        return body == null ? null : body.length() <= 800 ? body : body.substring(0, 800);
    }

    private record Probe(int status, String body, String transportError) {
        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /** JSON-serialised to the Super Admin UI; contains no secret material. */
    public record CoolifyDiagnostic(
            boolean configured,
            String apiUrl,
            String appUuid,
            String tokenFingerprint,
            Integer versionStatus,
            String versionMessage,
            Integer applicationStatus,
            String applicationMessage,
            String applicationName,
            String applicationFqdn,
            boolean ok,
            String hint,
            List<String> checks
    ) {}
}
