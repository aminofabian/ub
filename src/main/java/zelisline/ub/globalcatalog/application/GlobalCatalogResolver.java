package zelisline.ub.globalcatalog.application;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;
import zelisline.ub.globalcatalog.domain.GlobalCatalog;
import zelisline.ub.globalcatalog.domain.GlobalProductStatus;
import zelisline.ub.globalcatalog.repository.GlobalCatalogRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Resolves which published global catalog a tenant browses/adopts from.
 *
 * <pre>
 * 1. business.settings.globalCatalogCode (explicit override)
 * 2. business.country_code → global_catalogs.region_code
 * 3. catalog code = default
 * 4. any published catalog
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class GlobalCatalogResolver {

    public static final String SETTINGS_KEY = "globalCatalogCode";
    public static final String DEFAULT_CATALOG_CODE = "default";

    private static final String STATUS_PUBLISHED = GlobalProductStatus.PUBLISHED;

    private final BusinessRepository businessRepository;
    private final GlobalCatalogRepository globalCatalogRepository;
    private final ObjectMapper objectMapper;

    public GlobalCatalog resolveForBusiness(String businessId) {
        return resolveDetailedForBusiness(businessId).catalog();
    }

    public GlobalCatalog resolveForBusiness(Business business) {
        return resolveDetailedForBusiness(business).catalog();
    }

    public ResolvedGlobalCatalog resolveDetailedForBusiness(String businessId) {
        Business business = businessRepository.findById(businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found"));
        return resolveDetailedForBusiness(business);
    }

    public ResolvedGlobalCatalog resolveDetailedForBusiness(Business business) {
        String overrideCode = readOverrideCode(business.getSettings());
        if (overrideCode != null) {
            GlobalCatalog catalog = globalCatalogRepository.findByCodeAndStatus(overrideCode, STATUS_PUBLISHED)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "Configured globalCatalogCode '" + overrideCode + "' is missing or not published"));
            return new ResolvedGlobalCatalog(catalog, "override");
        }

        String countryCode = business.getCountryCode() != null
                ? business.getCountryCode().trim().toUpperCase()
                : null;
        if (countryCode != null && !countryCode.isBlank()) {
            var byRegion = globalCatalogRepository.findFirstByRegionCodeAndStatusOrderByVersionDesc(
                    countryCode, STATUS_PUBLISHED);
            if (byRegion.isPresent()) {
                return new ResolvedGlobalCatalog(byRegion.get(), "region");
            }
        }

        GlobalCatalog fallback = globalCatalogRepository.findByCode(DEFAULT_CATALOG_CODE)
                .or(() -> globalCatalogRepository.findFirstByStatusOrderByVersionDesc(STATUS_PUBLISHED))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No global catalog available"));
        return new ResolvedGlobalCatalog(fallback, "default");
    }

    public String readOverrideCode(String settingsJson) {
        if (settingsJson == null || settingsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = parseSettingsDocument(settingsJson);
            if (!root.isObject()) {
                return null;
            }
            JsonNode node = root.get(SETTINGS_KEY);
            if (node == null || node.isNull() || !node.isTextual()) {
                return null;
            }
            String trimmed = node.asText().trim();
            return trimmed.isEmpty() ? null : trimmed;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * @param code null = leave unchanged; blank = clear override
     */
    public String mergeOverrideCode(String currentSettings, String code) {
        if (code == null) {
            return currentSettings;
        }
        try {
            ObjectNode root = parseRoot(currentSettings);
            String trimmed = code.trim();
            if (trimmed.isEmpty()) {
                root.remove(SETTINGS_KEY);
            } else {
                // Validate exists + published before persisting.
                globalCatalogRepository.findByCodeAndStatus(trimmed, STATUS_PUBLISHED)
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.BAD_REQUEST,
                                "Unknown or unpublished global catalog code: " + trimmed));
                root.put(SETTINGS_KEY, trimmed);
            }
            return objectMapper.writeValueAsString(root);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not save globalCatalogCode");
        }
    }

    private ObjectNode parseRoot(String currentSettings) throws Exception {
        if (currentSettings == null || currentSettings.isBlank()) {
            return objectMapper.createObjectNode();
        }
        JsonNode root = parseSettingsDocument(currentSettings);
        return root.isObject() ? (ObjectNode) root.deepCopy() : objectMapper.createObjectNode();
    }

    /**
     * Some JDBC JSON mappings return a JSON string document; unwrap to the embedded object.
     */
    private JsonNode parseSettingsDocument(String raw) throws Exception {
        JsonNode n = objectMapper.readTree(raw);
        if (n.isTextual()) {
            return objectMapper.readTree(n.asText());
        }
        return n;
    }
}
