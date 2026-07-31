package zelisline.ub.tenancy.integrations.hostafrica;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * HostAfrica Domains API — buy-path only (no rDNS / PTR).
 * Credentials resolve from Super Admin → Platform → Domains (DB), with env fallback.
 *
 * @see <a href="https://api.hostafrica.com/docs/#tag/domains">HostAfrica domains</a>
 */
@Component
@RequiredArgsConstructor
public class HostAfricaClient {

    private static final Logger log = LoggerFactory.getLogger(HostAfricaClient.class);

    private final PlatformDomainSettingsService domainSettingsService;
    private final ObjectMapper objectMapper;

    public boolean configured() {
        return cfg().hostafricaConfigured();
    }

    private ResolvedDomainIntegrationsConfig cfg() {
        return domainSettingsService.resolve();
    }

    public AvailabilityResult checkAvailability(String domainOrBatch) {
        if (!configured()) {
            return AvailabilityResult.skipped("hostafrica_not_configured");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        String raw = domainOrBatch == null ? "" : domainOrBatch.trim().toLowerCase(Locale.ROOT);
        if (raw.contains(",")) {
            body.put("domains", raw);
        } else {
            body.put("domain", raw);
        }
        if (cfg().hostafricaCurrency() != null && !cfg().hostafricaCurrency().isBlank()) {
            body.put("currency", cfg().hostafricaCurrency().trim());
        }
        return postAvailability("/domain/check-availability", body);
    }

    public AvailabilityResult suggest(String query) {
        if (!configured()) {
            return AvailabilityResult.skipped("hostafrica_not_configured");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query == null ? "" : query.trim());
        if (cfg().hostafricaCurrency() != null && !cfg().hostafricaCurrency().isBlank()) {
            body.put("currency", cfg().hostafricaCurrency().trim());
        }
        // Some HA builds accept "domain" / "keyword"; send both for compatibility.
        body.put("domain", query == null ? "" : query.trim());
        body.put("keyword", query == null ? "" : query.trim());
        return postAvailability("/domain/suggest", body);
    }

    public ListDomainsResult listDomains() {
        if (!configured()) {
            return ListDomainsResult.skipped("hostafrica_not_configured");
        }
        try {
            HttpResponse<String> response = Unirest.post(url("/domain/list-domains"))
                    .header("Authorization", "Bearer " + cfg().hostafricaApiKey().trim())
                    .header("Content-Type", "application/json")
                    .asString();
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                log.warn("HostAfrica list-domains HTTP {} body={}", response.getStatus(), truncate(response.getBody()));
                return ListDomainsResult.failed(formatFailure(response.getStatus(), response.getBody()), response.getStatus());
            }
            JsonNode root = objectMapper.readTree(nullToEmpty(response.getBody()));
            JsonNode data = root.has("data") ? root.get("data") : root;
            JsonNode domains = data.get("domains");
            List<OwnedDomain> out = new ArrayList<>();
            if (domains != null && domains.isArray()) {
                for (JsonNode d : domains) {
                    out.add(parseOwned(d));
                }
            }
            return ListDomainsResult.ok(out);
        } catch (Exception ex) {
            log.warn("HostAfrica list-domains failed: {}", ex.getMessage());
            return ListDomainsResult.failed("error: " + ex.getMessage());
        }
    }

    public Optional<OwnedDomain> findOwnedByFqdn(String fqdn) {
        String needle = normalize(fqdn);
        if (needle == null) {
            return Optional.empty();
        }
        ListDomainsResult listed = listDomains();
        if (!listed.ok()) {
            return Optional.empty();
        }
        return listed.domains().stream()
                .filter(d -> needle.equals(normalize(d.domain())))
                .findFirst();
    }

    public GetDomainResult getDomain(String domainId) {
        if (!configured()) {
            return GetDomainResult.skipped("hostafrica_not_configured");
        }
        if (domainId == null || domainId.isBlank()) {
            return GetDomainResult.failed("domain_id required");
        }
        try {
            Map<String, Object> body = Map.of("domain_id", domainId.trim());
            HttpResponse<String> response = Unirest.post(url("/domain/get-domain"))
                    .header("Authorization", "Bearer " + cfg().hostafricaApiKey().trim())
                    .header("Content-Type", "application/json")
                    .body(objectMapper.writeValueAsString(body))
                    .asString();
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                log.warn("HostAfrica get-domain HTTP {} body={}", response.getStatus(), truncate(response.getBody()));
                return GetDomainResult.failed(formatFailure(response.getStatus(), response.getBody()), response.getStatus());
            }
            JsonNode root = objectMapper.readTree(nullToEmpty(response.getBody()));
            JsonNode data = root.has("data") ? root.get("data") : root;
            JsonNode domain = data.has("domain") ? data.get("domain") : data;
            return GetDomainResult.ok(parseOwned(domain));
        } catch (Exception ex) {
            log.warn("HostAfrica get-domain failed: {}", ex.getMessage());
            return GetDomainResult.failed("error: " + ex.getMessage());
        }
    }

    /**
     * Sets registrar nameservers (Vercel intended NS). Available as of HostAfrica API 2026-07.
     */
    public NameserverResult updateNameservers(String domainId, List<String> nameservers) {
        if (!configured()) {
            return NameserverResult.skipped("hostafrica_not_configured");
        }
        if (domainId == null || domainId.isBlank()) {
            return NameserverResult.failed("domain_id required");
        }
        List<String> ns = nameservers == null ? List.of() : nameservers.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .toList();
        if (ns.size() < 2) {
            return NameserverResult.failed("at least ns1 and ns2 required");
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("domain_id", domainId.trim());
            body.put("ns1", ns.get(0));
            body.put("ns2", ns.get(1));
            if (ns.size() > 2) {
                body.put("ns3", ns.get(2));
            }
            if (ns.size() > 3) {
                body.put("ns4", ns.get(3));
            }
            if (ns.size() > 4) {
                body.put("ns5", ns.get(4));
            }
            HttpResponse<String> response = Unirest.post(url("/domain/update-nameservers"))
                    .header("Authorization", "Bearer " + cfg().hostafricaApiKey().trim())
                    .header("Content-Type", "application/json")
                    .body(objectMapper.writeValueAsString(body))
                    .asString();
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                log.warn("HostAfrica update-nameservers HTTP {} body={}", response.getStatus(), truncate(response.getBody()));
                return NameserverResult.failed(formatFailure(response.getStatus(), response.getBody()), response.getStatus());
            }
            return NameserverResult.ok(ns);
        } catch (Exception ex) {
            log.warn("HostAfrica update-nameservers failed: {}", ex.getMessage());
            return NameserverResult.failed("error: " + ex.getMessage());
        }
    }

    /**
     * Point all WHOIS roles at the HostAfrica client (platform) profile.
     */
    public SimpleResult updateContactsToOwner(String domainId) {
        if (!configured()) {
            return SimpleResult.skipped("hostafrica_not_configured");
        }
        if (domainId == null || domainId.isBlank()) {
            return SimpleResult.failed("domain_id required");
        }
        try {
            Map<String, Object> owner = Map.of("type", "owner");
            Map<String, Object> contacts = new LinkedHashMap<>();
            contacts.put("Registrant", owner);
            contacts.put("Admin", owner);
            contacts.put("Tech", owner);
            contacts.put("Billing", owner);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("domain_id", domainId.trim());
            body.put("contacts", contacts);
            HttpResponse<String> response = Unirest.post(url("/domain/update-domain-contacts"))
                    .header("Authorization", "Bearer " + cfg().hostafricaApiKey().trim())
                    .header("Content-Type", "application/json")
                    .body(objectMapper.writeValueAsString(body))
                    .asString();
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                log.warn("HostAfrica update-domain-contacts HTTP {} body={}",
                        response.getStatus(), truncate(response.getBody()));
                return SimpleResult.failed(formatFailure(response.getStatus(), response.getBody()), response.getStatus());
            }
            return SimpleResult.success();
        } catch (Exception ex) {
            log.warn("HostAfrica update-domain-contacts failed: {}", ex.getMessage());
            return SimpleResult.failed("error: " + ex.getMessage());
        }
    }

    public DomainsRequiringDataResult listDomainsRequiringData() {
        if (!configured()) {
            return DomainsRequiringDataResult.skipped("hostafrica_not_configured");
        }
        try {
            HttpResponse<String> response = Unirest.post(url("/domain/list-domains-requiring-data"))
                    .header("Authorization", "Bearer " + cfg().hostafricaApiKey().trim())
                    .header("Content-Type", "application/json")
                    .asString();
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                log.warn("HostAfrica list-domains-requiring-data HTTP {} body={}",
                        response.getStatus(), truncate(response.getBody()));
                return DomainsRequiringDataResult.failed(
                        formatFailure(response.getStatus(), response.getBody()), response.getStatus());
            }
            JsonNode root = objectMapper.readTree(nullToEmpty(response.getBody()));
            JsonNode data = root.has("data") ? root.get("data") : root;
            List<DomainRequiringData> out = new ArrayList<>();
            JsonNode domains = data.get("domains");
            if (domains != null && domains.isArray()) {
                for (JsonNode d : domains) {
                    List<RequiredField> fields = new ArrayList<>();
                    JsonNode additional = d.get("additionalFields");
                    if (additional != null && additional.isArray()) {
                        for (JsonNode f : additional) {
                            fields.add(new RequiredField(
                                    text(f, "name"),
                                    text(f, "displayname"),
                                    text(f, "type"),
                                    f.path("required").asBoolean(false),
                                    text(f, "value")
                            ));
                        }
                    }
                    out.add(new DomainRequiringData(
                            text(d, "domain_id"),
                            normalize(text(d, "domain")),
                            text(d, "status"),
                            text(d, "tld"),
                            fields
                    ));
                }
            }
            return DomainsRequiringDataResult.ok(out);
        } catch (Exception ex) {
            log.warn("HostAfrica list-domains-requiring-data failed: {}", ex.getMessage());
            return DomainsRequiringDataResult.failed("error: " + ex.getMessage());
        }
    }

    public SimpleResult saveDomainRequiredData(String domainId, Map<String, String> fields) {
        if (!configured()) {
            return SimpleResult.skipped("hostafrica_not_configured");
        }
        if (domainId == null || domainId.isBlank()) {
            return SimpleResult.failed("domain_id required");
        }
        if (fields == null || fields.isEmpty()) {
            return SimpleResult.failed("fields required");
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("domain_id", domainId.trim());
            body.put("fields", fields);
            HttpResponse<String> response = Unirest.post(url("/domain/save-domain-required-data"))
                    .header("Authorization", "Bearer " + cfg().hostafricaApiKey().trim())
                    .header("Content-Type", "application/json")
                    .body(objectMapper.writeValueAsString(body))
                    .asString();
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                log.warn("HostAfrica save-domain-required-data HTTP {} body={}",
                        response.getStatus(), truncate(response.getBody()));
                return SimpleResult.failed(formatFailure(response.getStatus(), response.getBody()), response.getStatus());
            }
            return SimpleResult.success();
        } catch (Exception ex) {
            log.warn("HostAfrica save-domain-required-data failed: {}", ex.getMessage());
            return SimpleResult.failed("error: " + ex.getMessage());
        }
    }

    private AvailabilityResult postAvailability(String path, Map<String, Object> body) {
        try {
            HttpResponse<String> response = Unirest.post(url(path))
                    .header("Authorization", "Bearer " + cfg().hostafricaApiKey().trim())
                    .header("Content-Type", "application/json")
                    .body(objectMapper.writeValueAsString(body))
                    .asString();
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                log.warn("HostAfrica {} HTTP {} body={}", path, response.getStatus(), truncate(response.getBody()));
                return AvailabilityResult.failed(formatFailure(response.getStatus(), response.getBody()), response.getStatus());
            }
            JsonNode root = objectMapper.readTree(nullToEmpty(response.getBody()));
            JsonNode data = root.has("data") ? root.get("data") : root;
            List<DomainQuote> quotes = new ArrayList<>();
            JsonNode domains = data.get("domains");
            if (domains != null && domains.isArray()) {
                for (JsonNode d : domains) {
                    quotes.add(parseQuote(d, text(data, "currency_code")));
                }
            }
            List<String> suggestions = new ArrayList<>();
            JsonNode sugg = data.get("suggestions");
            if (sugg != null && sugg.isArray()) {
                for (JsonNode s : sugg) {
                    if (s != null && !s.asText("").isBlank()) {
                        suggestions.add(s.asText().trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
            String currency = firstNonBlank(text(data, "currency_code"), cfg().hostafricaCurrency());
            return AvailabilityResult.ok(quotes, suggestions, currency);
        } catch (Exception ex) {
            log.warn("HostAfrica {} failed: {}", path, ex.getMessage());
            return AvailabilityResult.failed("error: " + ex.getMessage());
        }
    }

    private DomainQuote parseQuote(JsonNode d, String fallbackCurrency) {
        String domain = text(d, "domain");
        String status = text(d, "status");
        boolean available = resolveAvailable(d, status);
        String registerUrl = text(d, "register_url");
        Long priceCents = null;
        Integer periodYears = 1;
        JsonNode pricing = d.get("pricing");
        if (pricing != null) {
            JsonNode register = pricing.get("domainregister");
            if (register != null && register.isArray() && register.size() > 0) {
                JsonNode first = register.get(0);
                periodYears = first.has("period") ? first.get("period").asInt(1) : 1;
                priceCents = parsePriceToCents(text(first, "price"));
            }
        }
        if (priceCents == null) {
            priceCents = parsePriceToCents(text(d, "price"));
        }
        // No quote price → not buyable through Palmart (premium / reserved / taken).
        if (available && (priceCents == null || priceCents <= 0)) {
            available = false;
            if (status == null || status.isBlank()) {
                status = "unavailable";
            }
        }
        return new DomainQuote(
                normalize(domain),
                available,
                status,
                priceCents,
                firstNonBlank(fallbackCurrency, cfg().hostafricaCurrency()),
                periodYears,
                registerUrl,
                d.path("premium").asBoolean(false),
                d.path("requires_additional_info").asBoolean(false)
        );
    }

    /**
     * HostAfrica status strings include {@code unavailable} — never use {@code contains("available")}
     * because that matches the substring inside unavailable.
     */
    private static boolean resolveAvailable(JsonNode d, String status) {
        if (d != null && d.has("available") && !d.get("available").isNull()) {
            JsonNode av = d.get("available");
            if (av.isBoolean()) {
                return av.asBoolean();
            }
            if (av.isNumber()) {
                return av.asInt() != 0;
            }
            String raw = av.asText("").trim().toLowerCase(Locale.ROOT);
            if (!raw.isEmpty()) {
                if (isUnavailableToken(raw)) {
                    return false;
                }
                if ("available".equals(raw) || "true".equals(raw) || "1".equals(raw) || "yes".equals(raw)) {
                    return true;
                }
            }
        }
        if (status == null || status.isBlank()) {
            return false;
        }
        String s = status.trim().toLowerCase(Locale.ROOT);
        if (isUnavailableToken(s)) {
            return false;
        }
        // Exact / token match only — do not use contains("available").
        return "available".equals(s)
                || s.startsWith("available ")
                || s.endsWith(" available")
                || s.contains(" available ");
    }

    private static boolean isUnavailableToken(String s) {
        return "unavailable".equals(s)
                || "taken".equals(s)
                || "registered".equals(s)
                || "reserved".equals(s)
                || "premium".equals(s)
                || s.contains("unavail")
                || s.contains("not available")
                || s.contains("already registered")
                || s.contains("taken");
    }

    private OwnedDomain parseOwned(JsonNode d) {
        List<String> nameservers = new ArrayList<>();
        JsonNode ns = d.get("domain_nameservers");
        if (ns != null && ns.isArray()) {
            for (JsonNode n : ns) {
                if (n != null && !n.asText("").isBlank()) {
                    nameservers.add(n.asText().trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return new OwnedDomain(
                text(d, "domain_id"),
                normalize(text(d, "domain")),
                text(d, "status"),
                text(d, "expirydate"),
                text(d, "nextduedate"),
                nameservers,
                d.path("has_dns_manager_zone").asBoolean(false)
        );
    }

    static Long parsePriceToCents(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.replaceAll("[^0-9.]", "");
        if (cleaned.isBlank()) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(cleaned);
            return value.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (Exception ex) {
            return null;
        }
    }

    private String url(String path) {
        String base = cfg().hostafricaApiBaseUrl() == null || cfg().hostafricaApiBaseUrl().isBlank()
                ? "https://api.hostafrica.com"
                : cfg().hostafricaApiBaseUrl().trim().replaceAll("/+$", "");
        return base + path;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        String v = node.get(field).asText();
        return v == null || v.isBlank() ? null : v;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "{}" : s;
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

    public record DomainQuote(
            String domain,
            boolean available,
            String status,
            Long priceCents,
            String currency,
            Integer periodYears,
            String registerUrl,
            boolean premium,
            boolean requiresAdditionalInfo
    ) {
    }

    public record OwnedDomain(
            String domainId,
            String domain,
            String status,
            String expiryDate,
            String nextDueDate,
            List<String> nameservers,
            boolean hasDnsManagerZone
    ) {
        public boolean active() {
            return status != null && status.toLowerCase(Locale.ROOT).contains("active");
        }
    }

    public record AvailabilityResult(
            boolean ok,
            boolean skipped,
            List<DomainQuote> quotes,
            List<String> suggestions,
            String currency,
            String error,
            Integer httpStatus
    ) {
        public static AvailabilityResult skipped(String reason) {
            return new AvailabilityResult(false, true, List.of(), List.of(), null, reason, null);
        }

        public static AvailabilityResult failed(String error) {
            return failed(error, null);
        }

        public static AvailabilityResult failed(String error, Integer httpStatus) {
            return new AvailabilityResult(false, false, List.of(), List.of(), null, error, httpStatus);
        }

        public static AvailabilityResult ok(List<DomainQuote> quotes, List<String> suggestions, String currency) {
            return new AvailabilityResult(true, false, quotes, suggestions, currency, null, null);
        }
    }

    public record ListDomainsResult(
            boolean ok,
            boolean skipped,
            List<OwnedDomain> domains,
            String error,
            Integer httpStatus
    ) {
        public static ListDomainsResult skipped(String reason) {
            return new ListDomainsResult(false, true, List.of(), reason, null);
        }

        public static ListDomainsResult failed(String error) {
            return failed(error, null);
        }

        public static ListDomainsResult failed(String error, Integer httpStatus) {
            return new ListDomainsResult(false, false, List.of(), error, httpStatus);
        }

        public static ListDomainsResult ok(List<OwnedDomain> domains) {
            return new ListDomainsResult(true, false, domains, null, null);
        }
    }

    public record GetDomainResult(
            boolean ok,
            boolean skipped,
            OwnedDomain domain,
            String error,
            Integer httpStatus
    ) {
        public static GetDomainResult skipped(String reason) {
            return new GetDomainResult(false, true, null, reason, null);
        }

        public static GetDomainResult failed(String error) {
            return failed(error, null);
        }

        public static GetDomainResult failed(String error, Integer httpStatus) {
            return new GetDomainResult(false, false, null, error, httpStatus);
        }

        public static GetDomainResult ok(OwnedDomain domain) {
            return new GetDomainResult(true, false, domain, null, null);
        }
    }

    public record NameserverResult(
            boolean ok,
            boolean skipped,
            List<String> nameservers,
            String error,
            Integer httpStatus
    ) {
        public static NameserverResult skipped(String reason) {
            return new NameserverResult(false, true, List.of(), reason, null);
        }

        public static NameserverResult failed(String error) {
            return failed(error, null);
        }

        public static NameserverResult failed(String error, Integer httpStatus) {
            return new NameserverResult(false, false, List.of(), error, httpStatus);
        }

        public static NameserverResult ok(List<String> nameservers) {
            return new NameserverResult(true, false, nameservers, null, null);
        }
    }

    public record SimpleResult(boolean ok, boolean skipped, String error, Integer httpStatus) {
        public static SimpleResult skipped(String reason) {
            return new SimpleResult(false, true, reason, null);
        }

        public static SimpleResult failed(String error) {
            return failed(error, null);
        }

        public static SimpleResult failed(String error, Integer httpStatus) {
            return new SimpleResult(false, false, error, httpStatus);
        }

        public static SimpleResult success() {
            return new SimpleResult(true, false, null, null);
        }
    }

    public record RequiredField(
            String name,
            String displayName,
            String type,
            boolean required,
            String value
    ) {}

    public record DomainRequiringData(
            String domainId,
            String domain,
            String status,
            String tld,
            List<RequiredField> additionalFields
    ) {}

    public record DomainsRequiringDataResult(
            boolean ok,
            boolean skipped,
            List<DomainRequiringData> domains,
            String error,
            Integer httpStatus
    ) {
        public static DomainsRequiringDataResult skipped(String reason) {
            return new DomainsRequiringDataResult(false, true, List.of(), reason, null);
        }

        public static DomainsRequiringDataResult failed(String error) {
            return failed(error, null);
        }

        public static DomainsRequiringDataResult failed(String error, Integer httpStatus) {
            return new DomainsRequiringDataResult(false, false, List.of(), error, httpStatus);
        }

        public static DomainsRequiringDataResult ok(List<DomainRequiringData> domains) {
            return new DomainsRequiringDataResult(true, false, domains, null, null);
        }
    }
}
