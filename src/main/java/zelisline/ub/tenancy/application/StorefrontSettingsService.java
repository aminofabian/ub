package zelisline.ub.tenancy.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;
import zelisline.ub.tenancy.api.dto.BrandingPatchRequest;
import zelisline.ub.tenancy.api.dto.StorefrontPatchRequest;
import zelisline.ub.tenancy.api.dto.StorefrontSettingsResponse;
import zelisline.ub.tenancy.api.dto.TenantAuthConfigDto;
import zelisline.ub.tenancy.api.dto.TenantBrandingDto;
import zelisline.ub.tenancy.api.dto.TenantConfigBundle;
import zelisline.ub.tenancy.api.dto.TenantPasswordPolicyDto;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.repository.BranchRepository;

@Service
@RequiredArgsConstructor
public class StorefrontSettingsService {

    private static final String KEY_STOREFRONT = "storefront";
    private static final String KEY_BRANDING = "branding";
    private static final String KEY_AUTH = "authConfig";
    private static final String KEY_FEATURES = "featureFlags";

    private static final List<String> SUPPORTED_AUTH_METHODS = List.of("password", "google", "microsoft", "saml");

    private final ObjectMapper objectMapper;
    private final BranchRepository branchRepository;

    public StorefrontSettingsResponse readFromSettingsJson(String settingsJson) {
        if (settingsJson == null || settingsJson.isBlank()) {
            return StorefrontSettingsResponse.defaults();
        }
        try {
            JsonNode root = parseSettingsDocument(settingsJson);
            if (!root.isObject()) {
                return StorefrontSettingsResponse.defaults();
            }
            JsonNode sf = root.path(KEY_STOREFRONT);
            if (sf.isMissingNode() || !sf.isObject()) {
                return StorefrontSettingsResponse.defaults();
            }
            return new StorefrontSettingsResponse(
                    readEnabled(sf),
                    textOrNull(sf.get("catalogBranchId")),
                    textOrNull(sf.get("label")),
                    textOrNull(sf.get("announcement")),
                    readFeaturedIds(sf.get("featuredItemIds"))
            );
        } catch (Exception e) {
            return StorefrontSettingsResponse.defaults();
        }
    }

    public TenantConfigBundle readTenantConfig(String settingsJson, String fallbackDisplayName) {
        if (settingsJson == null || settingsJson.isBlank()) {
            return TenantConfigBundle.defaults(fallbackDisplayName);
        }
        try {
            JsonNode root = parseSettingsDocument(settingsJson);
            if (!root.isObject()) {
                return TenantConfigBundle.defaults(fallbackDisplayName);
            }
            return new TenantConfigBundle(
                    readBranding(root.path(KEY_BRANDING), fallbackDisplayName),
                    readAuthConfig(root.path(KEY_AUTH)),
                    readFeatureFlags(root.path(KEY_FEATURES))
            );
        } catch (Exception e) {
            return TenantConfigBundle.defaults(fallbackDisplayName);
        }
    }

    private static TenantBrandingDto readBranding(JsonNode node, String fallbackDisplayName) {
        if (node.isMissingNode() || !node.isObject()) {
            return TenantBrandingDto.defaults(fallbackDisplayName);
        }
        String display = textOrNull(node.get("displayName"));
        return new TenantBrandingDto(
                display != null ? display : fallbackDisplayName,
                textOrNull(node.get("logoUrl")),
                textOrNull(node.get("faviconUrl")),
                textOrNull(node.get("primaryColor")),
                textOrNull(node.get("accentColor"))
        );
    }

    private static TenantAuthConfigDto readAuthConfig(JsonNode node) {
        if (node.isMissingNode() || !node.isObject()) {
            return TenantAuthConfigDto.defaults();
        }
        List<String> methods = readAuthMethods(node.get("methods"));
        List<String> sso = readStringList(node.get("ssoProviders"));
        TenantPasswordPolicyDto policy = readPasswordPolicy(node.get("passwordPolicy"));
        return new TenantAuthConfigDto(methods, sso, policy);
    }

    private static List<String> readAuthMethods(JsonNode arrayNode) {
        List<String> raw = readStringList(arrayNode);
        if (raw.isEmpty()) {
            return TenantAuthConfigDto.defaults().methods();
        }
        List<String> filtered = new ArrayList<>();
        for (String m : raw) {
            String lc = m.toLowerCase();
            if (SUPPORTED_AUTH_METHODS.contains(lc) && !filtered.contains(lc)) {
                filtered.add(lc);
            }
        }
        return filtered.isEmpty() ? TenantAuthConfigDto.defaults().methods() : List.copyOf(filtered);
    }

    private static TenantPasswordPolicyDto readPasswordPolicy(JsonNode node) {
        if (node == null || node.isMissingNode() || !node.isObject()) {
            return TenantPasswordPolicyDto.defaults();
        }
        TenantPasswordPolicyDto def = TenantPasswordPolicyDto.defaults();
        int minLength = node.path("minLength").asInt(def.minLength());
        boolean requireNumber = node.path("requireNumber").asBoolean(def.requireNumber());
        boolean requireSymbol = node.path("requireSymbol").asBoolean(def.requireSymbol());
        return new TenantPasswordPolicyDto(Math.max(minLength, 1), requireNumber, requireSymbol);
    }

    private static Map<String, Boolean> readFeatureFlags(JsonNode node) {
        if (node.isMissingNode() || !node.isObject()) {
            return Map.of();
        }
        Map<String, Boolean> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value.isBoolean()) {
                out.put(entry.getKey(), value.booleanValue());
            }
        });
        return Map.copyOf(out);
    }

    private static List<String> readStringList(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode n : arrayNode) {
            if (n.isTextual()) {
                String s = n.asText().trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return List.copyOf(out);
    }

    public String mergeBranding(String currentSettings, BrandingPatchRequest patch) {
        ObjectNode root = parseRoot(currentSettings);
        ObjectNode branding = copyNamespace(root, KEY_BRANDING);
        applyBrandingPatch(branding, patch);
        root.set(KEY_BRANDING, branding);
        return writeRoot(root);
    }

    /**
     * Replaces the {@code branding.logoUrl}/{@code logoPublicId} fields after a
     * Cloudinary upload. Returns the merged settings JSON.
     */
    public String mergeBrandingLogo(String currentSettings, String secureUrl, String publicId) {
        ObjectNode root = parseRoot(currentSettings);
        ObjectNode branding = copyNamespace(root, KEY_BRANDING);
        if (secureUrl == null || secureUrl.isBlank()) {
            branding.remove("logoUrl");
            branding.remove("logoPublicId");
        } else {
            branding.put("logoUrl", secureUrl);
            if (publicId != null && !publicId.isBlank()) {
                branding.put("logoPublicId", publicId.trim());
            } else {
                branding.remove("logoPublicId");
            }
        }
        root.set(KEY_BRANDING, branding);
        return writeRoot(root);
    }

    public String readBrandingLogoPublicId(String currentSettings) {
        if (currentSettings == null || currentSettings.isBlank()) {
            return null;
        }
        try {
            JsonNode root = parseSettingsDocument(currentSettings);
            JsonNode branding = root.path(KEY_BRANDING);
            if (!branding.isObject()) {
                return null;
            }
            return textOrNull(branding.get("logoPublicId"));
        } catch (Exception e) {
            return null;
        }
    }

    private static void applyBrandingPatch(ObjectNode branding, BrandingPatchRequest patch) {
        putOrRemoveString(branding, "displayName", patch.displayName());
        putOrRemoveString(branding, "logoUrl", patch.logoUrl());
        putOrRemoveString(branding, "logoPublicId", patch.logoPublicId());
        putOrRemoveString(branding, "faviconUrl", patch.faviconUrl());
        putOrRemoveString(branding, "primaryColor", patch.primaryColor());
        putOrRemoveString(branding, "accentColor", patch.accentColor());
    }

    /**
     * "{@code null} ignores the field, empty string clears it" semantics that
     * the rest of the patch surface relies on. Trimming is intentional so users
     * can't accidentally smuggle whitespace into branding values.
     */
    private static void putOrRemoveString(ObjectNode node, String key, String value) {
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            node.remove(key);
        } else {
            node.put(key, trimmed);
        }
    }

    private ObjectNode copyNamespace(ObjectNode root, String key) {
        if (root.has(key) && root.get(key).isObject()) {
            return (ObjectNode) root.get(key).deepCopy();
        }
        return objectMapper.createObjectNode();
    }

    private String writeRoot(ObjectNode root) {
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not save settings"
            );
        }
    }

    public String mergeAndValidate(String businessId, String currentSettings, StorefrontPatchRequest patch) {
        ObjectNode root = parseRoot(currentSettings);
        ObjectNode storefront = copyStorefront(root);
        applyPatch(storefront, patch);
        validateStorefrontForBusiness(businessId, storefront);
        root.set(KEY_STOREFRONT, storefront);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not save storefront settings");
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

    /**
     * Some JDBC JSON mappings return a JSON string document; unwrap to the embedded object.
     */
    private JsonNode parseSettingsDocument(String raw) throws JsonProcessingException {
        JsonNode n = objectMapper.readTree(raw);
        if (n.isTextual()) {
            return objectMapper.readTree(n.asText());
        }
        return n;
    }

    private ObjectNode copyStorefront(ObjectNode root) {
        if (root.has(KEY_STOREFRONT) && root.get(KEY_STOREFRONT).isObject()) {
            return (ObjectNode) root.get(KEY_STOREFRONT).deepCopy();
        }
        return objectMapper.createObjectNode();
    }

    private void applyPatch(ObjectNode storefront, StorefrontPatchRequest patch) {
        if (patch.enabled() != null) {
            storefront.put("enabled", patch.enabled());
        }
        if (patch.catalogBranchId() != null) {
            String id = patch.catalogBranchId().trim();
            if (id.isEmpty()) {
                storefront.remove("catalogBranchId");
            } else {
                storefront.put("catalogBranchId", id);
            }
        }
        if (patch.label() != null) {
            String label = patch.label().trim();
            if (label.isEmpty()) {
                storefront.remove("label");
            } else {
                storefront.put("label", label);
            }
        }
        if (patch.announcement() != null) {
            String ann = patch.announcement().trim();
            if (ann.isEmpty()) {
                storefront.remove("announcement");
            } else {
                storefront.put("announcement", ann);
            }
        }
        if (patch.featuredItemIds() != null) {
            if (patch.featuredItemIds().isEmpty()) {
                storefront.remove("featuredItemIds");
            } else {
                ArrayNode arr = objectMapper.createArrayNode();
                for (String raw : patch.featuredItemIds()) {
                    if (raw != null && !raw.isBlank()) {
                        arr.add(raw.trim());
                    }
                }
                storefront.set("featuredItemIds", arr);
            }
        }
    }

    private void validateStorefrontForBusiness(String businessId, ObjectNode storefront) {
        if (!readEnabled(storefront)) {
            return;
        }
        String branchId = textOrNull(storefront.get("catalogBranchId"));
        if (branchId == null) {
            throw badRequest("Catalog branch is required when the online storefront is enabled");
        }
        Branch branch = branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(branchId, businessId)
                .orElseThrow(() -> badRequest("Catalog branch not found for this business"));
        if (!branch.isActive()) {
            throw badRequest("Catalog branch must be active");
        }
        JsonNode featured = storefront.get("featuredItemIds");
        if (featured == null || featured.isNull()) {
            return;
        }
        if (!featured.isArray()) {
            throw badRequest("featuredItemIds must be an array");
        }
        if (featured.size() > 12) {
            throw badRequest("At most 12 featured item ids are allowed");
        }
        List<String> parsed = new ArrayList<>();
        for (JsonNode n : featured) {
            if (!n.isTextual()) {
                throw badRequest("Each featured item id must be a string UUID");
            }
            String v = n.asText().trim();
            try {
                UUID.fromString(v);
            } catch (IllegalArgumentException e) {
                throw badRequest("Invalid featured item id: " + v);
            }
            parsed.add(v);
        }
        ArrayNode normalized = objectMapper.createArrayNode();
        for (String id : parsed) {
            normalized.add(id);
        }
        storefront.set("featuredItemIds", normalized);
    }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private static boolean readEnabled(JsonNode storefront) {
        JsonNode n = storefront.get("enabled");
        if (n == null || n.isNull()) {
            return false;
        }
        if (n.isBoolean()) {
            return n.booleanValue();
        }
        if (n.isTextual()) {
            return Boolean.parseBoolean(n.asText().trim());
        }
        if (n.isNumber()) {
            return n.intValue() != 0;
        }
        return false;
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || !node.isTextual()) {
            return null;
        }
        String s = node.asText().trim();
        return s.isEmpty() ? null : s;
    }

    private static List<String> readFeaturedIds(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode n : arrayNode) {
            if (n.isTextual()) {
                String s = n.asText().trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return List.copyOf(out);
    }
}
