package zelisline.ub.tenancy.integrations.hostafrica;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.application.PlatformDomainSettingsService;
import zelisline.ub.platform.application.PlatformDomainSettingsService.ResolvedResellerConfig;

/**
 * HostAfrica DomainsReseller API (HMAC) — RegisterDomain, SaveNameServers, GetCredits.
 * Public Bearer API remains in {@link HostAfricaClient}.
 *
 * <p>Auth matches HA docs:
 * {@code base64_encode(hash_hmac("sha256", apiKey, email + ":" + gmdate("y-m-d H")))}
 * (PHP: data=apiKey, key=email:hour; hex digest then base64).
 */
@Component
@RequiredArgsConstructor
public class HostAfricaResellerClient {

    private static final Logger log = LoggerFactory.getLogger(HostAfricaResellerClient.class);

    private static final DateTimeFormatter HA_HOUR =
            DateTimeFormatter.ofPattern("yy-MM-dd HH").withZone(ZoneOffset.UTC);

    private static final String[] CONTACT_ROLES = {"Registrant", "Admin", "Technical", "Billing"};

    private final PlatformDomainSettingsService domainSettingsService;
    private final ObjectMapper objectMapper;

    public boolean configured() {
        return domainSettingsService.resellerConfigured();
    }

    public RegisterResult registerDomain(
            String fqdn,
            int regPeriodYears,
            List<String> nameservers,
            Map<String, String> whois
    ) {
        ResolvedResellerConfig cfg = domainSettingsService.resolveReseller();
        if (!cfg.configured()) {
            return RegisterResult.skipped("reseller_not_configured");
        }
        if (fqdn == null || fqdn.isBlank()) {
            return RegisterResult.failed("domain required");
        }
        List<String> ns = normalizeNs(nameservers);
        if (ns.size() < 2) {
            return RegisterResult.failed("at least ns1 and ns2 required");
        }
        Map<String, String> contact = normalizeContact(whois);
        if (contact.isEmpty()) {
            return RegisterResult.failed("WHOIS contact incomplete");
        }

        RegisterResult first = postRegister(cfg, fqdn.trim().toLowerCase(Locale.ROOT), regPeriodYears, ns, contact);
        if (first.ok() || first.skipped()) {
            return first;
        }
        // Some HA builds expect zipcode instead of postcode.
        if (contact.containsKey("postcode") && !contact.containsKey("zipcode")) {
            Map<String, String> aliased = new LinkedHashMap<>(contact);
            aliased.put("zipcode", aliased.get("postcode"));
            RegisterResult retry = postRegister(cfg, fqdn.trim().toLowerCase(Locale.ROOT), regPeriodYears, ns, aliased);
            if (retry.ok()) {
                return retry;
            }
            return RegisterResult.failed(
                    first.error() + " (zipcode retry: " + retry.error() + ")",
                    first.httpStatus()
            );
        }
        return first;
    }

    public NameserverResult saveNameServers(String fqdn, List<String> nameservers) {
        ResolvedResellerConfig cfg = domainSettingsService.resolveReseller();
        if (!cfg.configured()) {
            return NameserverResult.skipped("reseller_not_configured");
        }
        if (fqdn == null || fqdn.isBlank()) {
            return NameserverResult.failed("domain required");
        }
        List<String> ns = normalizeNs(nameservers);
        if (ns.size() < 2) {
            return NameserverResult.failed("at least ns1 and ns2 required");
        }
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            Map<String, String> nsMap = new LinkedHashMap<>();
            nsMap.put("ns1", ns.get(0));
            nsMap.put("ns2", ns.get(1));
            if (ns.size() > 2) {
                nsMap.put("ns3", ns.get(2));
            }
            if (ns.size() > 3) {
                nsMap.put("ns4", ns.get(3));
            }
            if (ns.size() > 4) {
                nsMap.put("ns5", ns.get(4));
            }
            params.put("nameservers", nsMap);
            HttpResponse<String> response = postForm(
                    cfg,
                    "/domains/" + encodePath(fqdn.trim().toLowerCase(Locale.ROOT)) + "/nameservers",
                    params
            );
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                log.warn("DomainsReseller SaveNameServers HTTP {} body={}",
                        response.getStatus(), truncate(response.getBody()));
                return NameserverResult.failed(formatFailure(response.getStatus(), response.getBody()), response.getStatus());
            }
            if (looksLikeApiError(response.getBody())) {
                return NameserverResult.failed(formatFailure(response.getStatus(), response.getBody()), response.getStatus());
            }
            return NameserverResult.ok(ns);
        } catch (Exception ex) {
            log.warn("DomainsReseller SaveNameServers failed: {}", ex.getMessage());
            return NameserverResult.failed("error: " + ex.getMessage());
        }
    }

    public CreditsResult getCredits() {
        ResolvedResellerConfig cfg = domainSettingsService.resolveReseller();
        if (!cfg.configured()) {
            return CreditsResult.skipped("reseller_not_configured");
        }
        try {
            HttpResponse<String> response = get(cfg, "/billing/credits");
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                return CreditsResult.failed(formatFailure(response.getStatus(), response.getBody()), response.getStatus());
            }
            JsonNode root = objectMapper.readTree(nullToEmpty(response.getBody()));
            JsonNode data = root.has("data") ? root.get("data") : root;
            String credit = text(data, "credit");
            if (credit == null) {
                credit = text(data, "credits");
            }
            if (credit == null) {
                credit = text(root, "credit");
            }
            return CreditsResult.ok(credit, response.getBody());
        } catch (Exception ex) {
            return CreditsResult.failed("error: " + ex.getMessage());
        }
    }

    private RegisterResult postRegister(
            ResolvedResellerConfig cfg,
            String domain,
            int years,
            List<String> ns,
            Map<String, String> contact
    ) {
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("domain", domain);
            params.put("regperiod", String.valueOf(Math.max(1, years)));

            Map<String, String> nsMap = new LinkedHashMap<>();
            nsMap.put("ns1", ns.get(0));
            nsMap.put("ns2", ns.get(1));
            if (ns.size() > 2) {
                nsMap.put("ns3", ns.get(2));
            }
            if (ns.size() > 3) {
                nsMap.put("ns4", ns.get(3));
            }
            if (ns.size() > 4) {
                nsMap.put("ns5", ns.get(4));
            }
            params.put("nameservers", nsMap);

            Map<String, Object> contacts = new LinkedHashMap<>();
            for (String role : CONTACT_ROLES) {
                contacts.put(role, contact);
            }
            params.put("contacts", contacts);

            // Vercel owns DNS — keep HA addons off.
            Map<String, Object> addons = new LinkedHashMap<>();
            addons.put("dnsmanagement", "0");
            addons.put("emailforwarding", "0");
            addons.put("idprotection", "0");
            params.put("addons", addons);

            HttpResponse<String> response = postForm(cfg, "/order/domains/register", params);
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                log.warn("DomainsReseller RegisterDomain HTTP {} body={}",
                        response.getStatus(), truncate(response.getBody()));
                return RegisterResult.failed(formatFailure(response.getStatus(), response.getBody()), response.getStatus());
            }
            if (looksLikeApiError(response.getBody())) {
                return RegisterResult.failed(formatFailure(response.getStatus(), response.getBody()), response.getStatus());
            }
            return RegisterResult.ok(truncate(response.getBody()));
        } catch (Exception ex) {
            log.warn("DomainsReseller RegisterDomain failed: {}", ex.getMessage());
            return RegisterResult.failed("error: " + ex.getMessage());
        }
    }

    private HttpResponse<String> postForm(ResolvedResellerConfig cfg, String action, Map<String, Object> params)
            throws Exception {
        String token = buildToken(cfg.apiKey(), cfg.email());
        String body = httpBuildQuery(params);
        return Unirest.post(url(cfg.apiBaseUrl(), action))
                .header("username", cfg.email().trim())
                .header("token", token)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body(body)
                .asString();
    }

    private HttpResponse<String> get(ResolvedResellerConfig cfg, String action) throws Exception {
        String token = buildToken(cfg.apiKey(), cfg.email());
        return Unirest.get(url(cfg.apiBaseUrl(), action))
                .header("username", cfg.email().trim())
                .header("token", token)
                .asString();
    }

    /**
     * PHP {@code hash_hmac("sha256", apiKey, email:gmdate("y-m-d H"))} then {@code base64_encode}
     * of the hex digest (default non-raw HMAC).
     */
    static String buildToken(String apiKey, String email) throws Exception {
        String hour = HA_HOUR.format(ZonedDateTime.now(ZoneOffset.UTC));
        String hmacKey = email.trim() + ":" + hour;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(hmacKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(apiKey.getBytes(StandardCharsets.UTF_8));
        String hex = HexFormat.of().formatHex(raw);
        return Base64.getEncoder().encodeToString(hex.getBytes(StandardCharsets.UTF_8));
    }

    /** PHP-like {@code http_build_query} for nested maps/lists. */
    @SuppressWarnings("unchecked")
    static String httpBuildQuery(Map<String, Object> params) {
        StringBuilder sb = new StringBuilder();
        appendQuery(sb, params, null);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendQuery(StringBuilder sb, Object value, String prefix) {
        if (value == null) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getKey() == null) {
                    continue;
                }
                String key = String.valueOf(e.getKey());
                String next = prefix == null ? key : prefix + "[" + key + "]";
                appendQuery(sb, e.getValue(), next);
            }
            return;
        }
        if (value instanceof List<?> list) {
            int i = 0;
            for (Object item : list) {
                String next = prefix + "[" + i + "]";
                appendQuery(sb, item, next);
                i++;
            }
            return;
        }
        if (prefix == null) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append('&');
        }
        sb.append(encodeForm(prefix)).append('=').append(encodeForm(String.valueOf(value)));
    }

    private static Map<String, String> normalizeContact(Map<String, String> whois) {
        if (whois == null || whois.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        copy(out, whois, "firstname");
        copy(out, whois, "lastname");
        String fullname = firstNonBlank(whois.get("fullname"), joinName(whois.get("firstname"), whois.get("lastname")));
        if (fullname != null) {
            out.put("fullname", fullname);
        }
        copy(out, whois, "companyname");
        copy(out, whois, "email");
        copy(out, whois, "address1");
        copy(out, whois, "address2");
        copy(out, whois, "city");
        copy(out, whois, "state");
        String post = firstNonBlank(whois.get("postcode"), whois.get("zipcode"));
        if (post != null) {
            out.put("postcode", post);
        }
        copy(out, whois, "country");
        copy(out, whois, "phonenumber");
        return out;
    }

    private static void copy(Map<String, String> out, Map<String, String> src, String key) {
        String v = src.get(key);
        if (v != null && !v.isBlank()) {
            out.put(key, v.trim());
        }
    }

    private static String joinName(String first, String last) {
        String f = first == null ? "" : first.trim();
        String l = last == null ? "" : last.trim();
        String joined = (f + " " + l).trim();
        return joined.isEmpty() ? null : joined;
    }

    private static List<String> normalizeNs(List<String> nameservers) {
        if (nameservers == null) {
            return List.of();
        }
        return nameservers.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .toList();
    }

    private static String url(String base, String action) {
        String b = base == null ? "" : base.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        String a = action == null ? "" : action.trim();
        if (!a.startsWith("/")) {
            a = "/" + a;
        }
        return b + a;
    }

    private static String encodePath(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodeForm(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private boolean looksLikeApiError(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root.has("error") && !root.get("error").isNull() && !root.get("error").asText("").isBlank()) {
                return true;
            }
            if (root.has("success") && root.get("success").isBoolean() && !root.get("success").asBoolean()) {
                return true;
            }
            String status = text(root, "status");
            return status != null && ("error".equalsIgnoreCase(status) || "failed".equalsIgnoreCase(status));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String formatFailure(int status, String body) {
        String snippet = truncate(body);
        return "HTTP " + status + (snippet.isBlank() ? "" : ": " + snippet);
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        String t = body.trim();
        return t.length() <= 400 ? t : t.substring(0, 400) + "…";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "{}" : s;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || field == null) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText(null);
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    public record RegisterResult(boolean ok, boolean skipped, String error, String raw, int httpStatus) {
        public static RegisterResult ok(String raw) {
            return new RegisterResult(true, false, null, raw, 200);
        }

        public static RegisterResult skipped(String reason) {
            return new RegisterResult(false, true, reason, null, 0);
        }

        public static RegisterResult failed(String error) {
            return new RegisterResult(false, false, error, null, 0);
        }

        public static RegisterResult failed(String error, int httpStatus) {
            return new RegisterResult(false, false, error, null, httpStatus);
        }
    }

    public record NameserverResult(boolean ok, boolean skipped, String error, List<String> nameservers, int httpStatus) {
        public static NameserverResult ok(List<String> ns) {
            return new NameserverResult(true, false, null, ns, 200);
        }

        public static NameserverResult skipped(String reason) {
            return new NameserverResult(false, true, reason, List.of(), 0);
        }

        public static NameserverResult failed(String error) {
            return new NameserverResult(false, false, error, List.of(), 0);
        }

        public static NameserverResult failed(String error, int httpStatus) {
            return new NameserverResult(false, false, error, List.of(), httpStatus);
        }
    }

    public record CreditsResult(boolean ok, boolean skipped, String error, String credit, String raw, int httpStatus) {
        public static CreditsResult ok(String credit, String raw) {
            return new CreditsResult(true, false, null, credit, raw, 200);
        }

        public static CreditsResult skipped(String reason) {
            return new CreditsResult(false, true, reason, null, null, 0);
        }

        public static CreditsResult failed(String error) {
            return new CreditsResult(false, false, error, null, null, 0);
        }

        public static CreditsResult failed(String error, int httpStatus) {
            return new CreditsResult(false, false, error, null, null, httpStatus);
        }
    }
}
