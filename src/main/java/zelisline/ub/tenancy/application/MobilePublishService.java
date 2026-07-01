package zelisline.ub.tenancy.application;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;
import zelisline.ub.tenancy.api.dto.MobilePublishStatusResponse;
import zelisline.ub.tenancy.api.dto.MobileTenantProfileExport;
import zelisline.ub.tenancy.api.dto.TenantBrandingDto;
import zelisline.ub.tenancy.api.dto.TenantConfigBundle;
import zelisline.ub.tenancy.api.dto.MobileSettingsResponse;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class MobilePublishService {

    private static final String KEY_MOBILE = BusinessMobileSettingsService.KEY_MOBILE;
    private static final String KEY_PUBLISH = "publish";
    private static final String KEY_STATUS = "status";
    private static final String KEY_REQUESTED_AT = "requestedAt";
    private static final String KEY_APP = "app";
    private static final String KEY_PLATFORM = "platform";
    private static final String KEY_WORKFLOW_URL = "workflowUrl";
    private static final String KEY_LAST_ERROR = "lastError";
    private static final String KEY_COMPLETED_AT = "completedAt";

    private final BusinessRepository businessRepository;
    private final BusinessMobileSettingsService businessMobileSettingsService;
    private final StorefrontSettingsService storefrontSettingsService;
    private final GitHubMobilePublishDispatcher githubDispatcher;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public MobilePublishStatusResponse getStatus(String businessId) {
        Business business = requireBusiness(businessId);
        return toStatus(readPublishNode(business.getSettings()), manualCommand(business.getSlug()));
    }

    @Transactional
    public MobilePublishStatusResponse requestPublish(
            String businessId,
            String app,
            String platform
    ) {
        Business business = requireBusiness(businessId);
        String settings = businessMobileSettingsService.provisionIfMissing(business);
        if (!settings.equals(business.getSettings())) {
            business.setSettings(settings);
            business = businessRepository.save(business);
        }

        MobileSettingsResponse mobile = businessMobileSettingsService.readFromSettingsJson(business.getSettings());
        if (!mobile.provisioned()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Mobile app profile is not provisioned yet"
            );
        }

        TenantConfigBundle tenantConfig = storefrontSettingsService.readTenantConfig(
                business.getSettings(),
                business.getName());
        TenantBrandingDto branding = tenantConfig.branding();
        String displayName = branding.displayName() != null && !branding.displayName().isBlank()
                ? branding.displayName().trim()
                : business.getName();
        MobileTenantProfileExport profile = businessMobileSettingsService.buildTenantProfileExport(
                business,
                displayName,
                branding,
                mobile);

        String workflowUrl = null;
        String lastError = null;
        String status = MobilePublishStatusResponse.STATUS_REQUESTED;

        if (githubDispatcher.isConfigured()) {
            try {
                workflowUrl = githubDispatcher.dispatch(business.getSlug(), app, platform, profile);
            } catch (IllegalStateException e) {
                status = MobilePublishStatusResponse.STATUS_FAILED;
                lastError = e.getMessage();
            }
        }

        String updated = mergePublishRequest(
                business.getSettings(),
                status,
                app,
                platform,
                workflowUrl,
                lastError
        );
        business.setSettings(updated);
        businessRepository.save(business);

        return toStatus(readPublishNode(updated), manualCommand(business.getSlug()));
    }

    @Transactional
    public void recordCallback(
            String slug,
            String status,
            String workflowUrl,
            String lastError,
            String providedSecret,
            String configuredSecret
    ) {
        if (configuredSecret == null || configuredSecret.isBlank()
                || !configuredSecret.equals(providedSecret)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid publish callback secret");
        }

        String normalizedSlug = slug == null ? "" : slug.trim();
        if (normalizedSlug.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "slug is required");
        }

        String normalizedStatus = normalizeCallbackStatus(status);
        Business business = businessRepository.findBySlugAndDeletedAtIsNull(normalizedSlug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found"));

        String updated = mergePublishCallback(
                business.getSettings(),
                normalizedStatus,
                workflowUrl,
                lastError
        );
        business.setSettings(updated);
        businessRepository.save(business);
    }

    static String mergePublishRequest(
            String currentSettings,
            String status,
            String app,
            String platform,
            String workflowUrl,
            String lastError
    ) {
        ObjectNode root = parseRootStatic(currentSettings);
        ObjectNode mobile = root.has(KEY_MOBILE) && root.get(KEY_MOBILE).isObject()
                ? (ObjectNode) root.get(KEY_MOBILE)
                : root.putObject(KEY_MOBILE);

        ObjectNode publish = mobile.has(KEY_PUBLISH) && mobile.get(KEY_PUBLISH).isObject()
                ? (ObjectNode) mobile.get(KEY_PUBLISH)
                : mobile.putObject(KEY_PUBLISH);

        publish.put(KEY_STATUS, status);
        publish.put(KEY_REQUESTED_AT, Instant.now().toString());
        publish.put(KEY_APP, app);
        publish.put(KEY_PLATFORM, platform);
        if (workflowUrl != null && !workflowUrl.isBlank()) {
            publish.put(KEY_WORKFLOW_URL, workflowUrl);
        } else {
            publish.putNull(KEY_WORKFLOW_URL);
        }
        if (lastError != null && !lastError.isBlank()) {
            publish.put(KEY_LAST_ERROR, lastError);
        } else {
            publish.putNull(KEY_LAST_ERROR);
        }
        publish.putNull(KEY_COMPLETED_AT);

        try {
            return new ObjectMapper().writeValueAsString(root);
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not save publish status"
            );
        }
    }

    static String mergePublishCallback(
            String currentSettings,
            String status,
            String workflowUrl,
            String lastError
    ) {
        ObjectNode root = parseRootStatic(currentSettings);
        ObjectNode mobile = root.has(KEY_MOBILE) && root.get(KEY_MOBILE).isObject()
                ? (ObjectNode) root.get(KEY_MOBILE)
                : root.putObject(KEY_MOBILE);

        ObjectNode publish = mobile.has(KEY_PUBLISH) && mobile.get(KEY_PUBLISH).isObject()
                ? (ObjectNode) mobile.get(KEY_PUBLISH)
                : mobile.putObject(KEY_PUBLISH);

        publish.put(KEY_STATUS, status);
        if (workflowUrl != null && !workflowUrl.isBlank()) {
            publish.put(KEY_WORKFLOW_URL, workflowUrl.trim());
        }
        if (MobilePublishStatusResponse.STATUS_FAILED.equals(status)) {
            publish.put(
                    KEY_LAST_ERROR,
                    lastError == null || lastError.isBlank() ? "Build failed" : lastError.trim()
            );
            publish.put(KEY_COMPLETED_AT, Instant.now().toString());
        } else if (MobilePublishStatusResponse.STATUS_SUBMITTED.equals(status)) {
            publish.putNull(KEY_LAST_ERROR);
            publish.put(KEY_COMPLETED_AT, Instant.now().toString());
        } else {
            publish.putNull(KEY_LAST_ERROR);
        }

        try {
            return new ObjectMapper().writeValueAsString(root);
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not save publish status"
            );
        }
    }

    private static String normalizeCallbackStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status is required");
        }
        String normalized = status.trim().toLowerCase();
        return switch (normalized) {
            case MobilePublishStatusResponse.STATUS_BUILDING,
                    MobilePublishStatusResponse.STATUS_SUBMITTED,
                    MobilePublishStatusResponse.STATUS_FAILED -> normalized;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid publish status");
        };
    }

    private MobilePublishStatusResponse toStatus(JsonNode publish, String manualCommand) {
        if (publish == null || !publish.isObject()) {
            return new MobilePublishStatusResponse(
                    MobilePublishStatusResponse.STATUS_IDLE,
                    null,
                    "shopper",
                    "all",
                    null,
                    null,
                    null,
                    githubDispatcher.isConfigured(),
                    manualCommand
            );
        }
        return new MobilePublishStatusResponse(
                textOrDefault(publish.path(KEY_STATUS), MobilePublishStatusResponse.STATUS_IDLE),
                instantOrNull(publish.path(KEY_REQUESTED_AT)),
                textOrDefault(publish.path(KEY_APP), "shopper"),
                textOrDefault(publish.path(KEY_PLATFORM), "all"),
                textOrNull(publish.path(KEY_WORKFLOW_URL)),
                textOrNull(publish.path(KEY_LAST_ERROR)),
                instantOrNull(publish.path(KEY_COMPLETED_AT)),
                githubDispatcher.isConfigured(),
                manualCommand
        );
    }

    private JsonNode readPublishNode(String settingsJson) {
        try {
            if (settingsJson == null || settingsJson.isBlank()) {
                return null;
            }
            JsonNode root = objectMapper.readTree(settingsJson);
            if (root.isTextual()) {
                root = objectMapper.readTree(root.asText());
            }
            return root.path(KEY_MOBILE).path(KEY_PUBLISH);
        } catch (Exception e) {
            return null;
        }
    }

    private static String manualCommand(String slug) {
        String s = slug == null ? "<slug>" : slug.trim();
        return "cd mobile && node scripts/publish-tenant-shopper.js tenants/" + s + ".json";
    }

    private Business requireBusiness(String businessId) {
        return businessRepository.findByIdAndDeletedAtIsNull(businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found"));
    }

    private static ObjectNode parseRootStatic(String currentSettings) {
        ObjectMapper mapper = new ObjectMapper();
        if (currentSettings == null || currentSettings.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode root = mapper.readTree(currentSettings);
            if (root.isTextual()) {
                root = mapper.readTree(root.asText());
            }
            return root.isObject() ? (ObjectNode) root : mapper.createObjectNode();
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private static String textOrNull(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String textOrDefault(JsonNode node, String fallback) {
        String value = textOrNull(node);
        return value == null ? fallback : value;
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
}
