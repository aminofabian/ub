package zelisline.ub.tenancy.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;
import zelisline.ub.tenancy.api.dto.MobileAppSettingsDto;
import zelisline.ub.tenancy.api.dto.MobileSettingsResponse;
import zelisline.ub.tenancy.api.dto.MobileStoreLinksDto;
import zelisline.ub.tenancy.api.dto.MobileTenantAppProfileExport;
import zelisline.ub.tenancy.api.dto.MobileTenantAssetsExport;
import zelisline.ub.tenancy.api.dto.MobileTenantProfileExport;
import zelisline.ub.tenancy.api.dto.TenantBrandingDto;
import zelisline.ub.tenancy.domain.Business;

/**
 * Persists per-tenant mobile app provisioning under {@code settings.mobile}.
 *
 * <p>Every business with an ecommerce storefront can launch a branded shopper app;
 * bundle IDs and deep links are minted automatically at business creation.
 */
@Service
@RequiredArgsConstructor
public class BusinessMobileSettingsService {

    static final String KEY_MOBILE = "mobile";
    private static final String KEY_PROVISIONED_AT = "provisionedAt";
    private static final String KEY_SCHEME = "scheme";
    private static final String KEY_APPS = "apps";
    private static final String KEY_STORE_LINKS = "storeLinks";
    private static final String KEY_NAME = "name";
    private static final String KEY_BUNDLE_ID = "bundleId";
    private static final String KEY_WHITE_LABEL = "whiteLabel";

    private static final List<String> APP_ROLES = List.of(
            "shopper", "cashier", "grocery", "admin", "stock"
    );

    private final ObjectMapper objectMapper;

    @Value("${app.mobile.api-base-url:https://api.kiosk.ke}")
    private String apiBaseUrl;

    @Value("${app.tenancy.slug-domain-suffix:}")
    private String slugDomainSuffix;

    public MobileSettingsResponse readFromSettingsJson(String settingsJson) {
        if (settingsJson == null || settingsJson.isBlank()) {
            return MobileSettingsResponse.notProvisioned();
        }
        try {
            JsonNode root = parseSettingsDocument(settingsJson);
            if (!root.isObject()) {
                return MobileSettingsResponse.notProvisioned();
            }
            JsonNode mobile = root.path(KEY_MOBILE);
            if (!mobile.isObject() || mobile.path(KEY_PROVISIONED_AT).isMissingNode()) {
                return MobileSettingsResponse.notProvisioned();
            }
            Instant provisionedAt = instantOrNull(mobile.path(KEY_PROVISIONED_AT));
            String scheme = textOrNull(mobile.path(KEY_SCHEME));
            Map<String, MobileAppSettingsDto> apps = readApps(mobile.path(KEY_APPS));
            MobileStoreLinksDto storeLinks = readStoreLinks(mobile.path(KEY_STORE_LINKS));
            return new MobileSettingsResponse(true, provisionedAt, scheme, apps, storeLinks);
        } catch (Exception e) {
            return MobileSettingsResponse.notProvisioned();
        }
    }

    /**
     * Seeds mobile distribution metadata for a new business. Idempotent when already provisioned.
     */
    public String mergeInitialProvision(String currentSettings, String slug, String businessName) {
        MobileSettingsResponse current = readFromSettingsJson(currentSettings);
        if (current.provisioned()) {
            return currentSettings;
        }
        return writeProvision(currentSettings, slug, businessName);
    }

    /**
     * Backfills mobile settings for businesses created before auto-provision existed.
     */
    public String provisionIfMissing(Business business) {
        String settings = business.getSettings();
        MobileSettingsResponse current = readFromSettingsJson(settings);
        if (current.provisioned()) {
            return settings;
        }
        return writeProvision(settings, business.getSlug(), business.getName());
    }

    public MobileTenantProfileExport buildTenantProfileExport(
            Business business,
            String displayName,
            TenantBrandingDto branding,
            MobileSettingsResponse mobile
    ) {
        String slug = business.getSlug();
        String scheme = mobile.scheme() != null && !mobile.scheme().isBlank()
                ? mobile.scheme()
                : defaultScheme(slug);
        String suffix = slugDomainSuffix == null ? "" : slugDomainSuffix.trim();
        String primary = branding.primaryColor() != null && !branding.primaryColor().isBlank()
                ? branding.primaryColor().trim()
                : "#28a745";

        Map<String, MobileTenantAppProfileExport> apps = new LinkedHashMap<>();
        for (String role : APP_ROLES) {
            MobileAppSettingsDto app = mobile.apps().get(role);
            if (app == null) {
                continue;
            }
            apps.put(role, new MobileTenantAppProfileExport(
                    app.name(),
                    slug + "-" + role,
                    app.bundleId()
            ));
        }

        return new MobileTenantProfileExport(
                slug,
                displayName,
                suffix.isEmpty() ? null : suffix,
                apiBaseUrl.replaceAll("/$", ""),
                primary,
                primary,
                scheme,
                apps,
                MobileTenantAssetsExport.placeholders(slug)
        );
    }

    public static String bundleIdForSlug(String slug, String role) {
        String base = slug == null ? "" : slug.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (base.isEmpty()) {
            base = "store";
        }
        return "com." + base + "." + role;
    }

    public static String appDisplayName(String businessName, String role) {
        String name = businessName == null ? "" : businessName.trim();
        if (name.isEmpty()) {
            name = "Store";
        }
        return switch (role) {
            case "shopper" -> name;
            case "cashier" -> name + " Cashier";
            case "grocery" -> name + " Grocery";
            case "admin" -> name + " Admin";
            case "stock" -> name + " Stock";
            default -> name;
        };
    }

    private String writeProvision(String currentSettings, String slug, String businessName) {
        ObjectNode root = parseRoot(currentSettings);
        ObjectNode mobile = objectMapper.createObjectNode();
        mobile.put(KEY_PROVISIONED_AT, Instant.now().toString());
        mobile.put(KEY_SCHEME, defaultScheme(slug));

        ObjectNode apps = objectMapper.createObjectNode();
        for (String role : APP_ROLES) {
            ObjectNode app = objectMapper.createObjectNode();
            app.put(KEY_NAME, appDisplayName(businessName, role));
            app.put(KEY_BUNDLE_ID, bundleIdForSlug(slug, role));
            app.put(KEY_WHITE_LABEL, true);
            apps.set(role, app);
        }
        mobile.set(KEY_APPS, apps);
        mobile.set(KEY_STORE_LINKS, objectMapper.createObjectNode());

        root.set(KEY_MOBILE, mobile);
        return writeRoot(root);
    }

    private static String defaultScheme(String slug) {
        String s = slug == null ? "" : slug.trim().toLowerCase(Locale.ROOT);
        if (s.matches("^[a-z][a-z0-9+.-]*$")) {
            return s;
        }
        String stripped = s.replaceAll("[^a-z0-9]", "");
        return stripped.isEmpty() ? "kiosk" : stripped;
    }

    private Map<String, MobileAppSettingsDto> readApps(JsonNode appsNode) {
        Map<String, MobileAppSettingsDto> out = new LinkedHashMap<>();
        if (!appsNode.isObject()) {
            return out;
        }
        for (String role : APP_ROLES) {
            JsonNode app = appsNode.path(role);
            if (!app.isObject()) {
                continue;
            }
            String name = textOrNull(app.path(KEY_NAME));
            String bundleId = textOrNull(app.path(KEY_BUNDLE_ID));
            if (name == null || bundleId == null) {
                continue;
            }
            boolean whiteLabel = app.path(KEY_WHITE_LABEL).asBoolean(true);
            out.put(role, new MobileAppSettingsDto(name, bundleId, whiteLabel));
        }
        return out;
    }

    private MobileStoreLinksDto readStoreLinks(JsonNode node) {
        if (!node.isObject()) {
            return MobileStoreLinksDto.empty();
        }
        return new MobileStoreLinksDto(
                textOrNull(node.path("ios")),
                textOrNull(node.path("android"))
        );
    }

    private static String textOrNull(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Instant instantOrNull(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        try {
            return Instant.parse(node.asText());
        } catch (Exception e) {
            return null;
        }
    }

    private ObjectNode parseRoot(String currentSettings) {
        if (currentSettings == null || currentSettings.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode root = parseSettingsDocument(currentSettings);
            return root.isObject() ? (ObjectNode) root : objectMapper.createObjectNode();
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private JsonNode parseSettingsDocument(String raw) throws JsonProcessingException {
        JsonNode n = objectMapper.readTree(raw);
        if (n.isTextual()) {
            return objectMapper.readTree(n.asText());
        }
        return n;
    }

    private String writeRoot(ObjectNode root) {
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not save mobile settings"
            );
        }
    }
}
