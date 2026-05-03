package zelisline.ub.platform.media;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Merges {@link CloudinaryProperties} from {@code CLOUDINARY_URL} and applies enable rules:
 * {@code APP_MEDIA_CLOUDINARY_ENABLED} wins when set; otherwise uploads are enabled iff all three fields are non-blank.
 */
@Component
public class CloudinaryPropertiesConfigurer implements InitializingBean, EnvironmentAware {

    private static final Logger log = LoggerFactory.getLogger(CloudinaryPropertiesConfigurer.class);

    private final CloudinaryProperties properties;
    private Environment environment;

    public CloudinaryPropertiesConfigurer(CloudinaryProperties properties) {
        this.properties = properties;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        String url = resolveCloudinaryUrl();
        if (url != null) {
            applyParsedUrl(url);
        }
        properties.setCloudName(trimToEmpty(properties.getCloudName()));
        properties.setApiKey(trimToEmpty(properties.getApiKey()));
        properties.setApiSecret(trimToEmpty(properties.getApiSecret()));

        if (!applyExplicitEnableFlag()) {
            applyEnableFromCredentials();
        }
    }

    private String resolveCloudinaryUrl() {
        String v = trimToNull(System.getenv("CLOUDINARY_URL"));
        if (v != null) {
            return v;
        }
        v = trimToNull(System.getProperty("CLOUDINARY_URL"));
        if (v != null) {
            return v;
        }
        v = trimToNull(environment.getProperty("CLOUDINARY_URL"));
        if (v != null) {
            return v;
        }
        if (allowDotEnvFiles()) {
            return trimToNull(CloudinaryDotEnvSupport.readCloudinaryUrl());
        }
        return null;
    }

    private boolean allowDotEnvFiles() {
        return !environment.acceptsProfiles(Profiles.of("test"));
    }

    private void applyParsedUrl(String url) {
        Optional<CloudinaryUrlParser.Parts> parsed = CloudinaryUrlParser.parse(url);
        if (parsed.isEmpty()) {
            log.warn(
                    "CLOUDINARY_URL is set but does not match cloudinary://KEY:SECRET@CLOUD_NAME — image uploads stay disabled");
            return;
        }
        CloudinaryUrlParser.Parts parts = parsed.get();
        if (isBlank(properties.getCloudName())) {
            properties.setCloudName(parts.cloudName());
        }
        if (isBlank(properties.getApiKey())) {
            properties.setApiKey(parts.apiKey());
        }
        if (isBlank(properties.getApiSecret())) {
            properties.setApiSecret(parts.apiSecret());
        }
    }

    /** @return {@code true} if explicit flag was found and applied (early exit). */
    private boolean applyExplicitEnableFlag() {
        String envFlag = trimToNull(System.getenv("APP_MEDIA_CLOUDINARY_ENABLED"));
        if (envFlag == null) {
            envFlag = trimToNull(environment.getProperty("APP_MEDIA_CLOUDINARY_ENABLED"));
        }
        if (envFlag == null && allowDotEnvFiles()) {
            envFlag = trimToNull(CloudinaryDotEnvSupport.readFirstDefined("APP_MEDIA_CLOUDINARY_ENABLED"));
        }
        if (envFlag == null) {
            return false;
        }
        properties.setEnabled(Boolean.parseBoolean(envFlag));
        return true;
    }

    private void applyEnableFromCredentials() {
        boolean creds = !isBlank(properties.getCloudName())
                && !isBlank(properties.getApiKey())
                && !isBlank(properties.getApiSecret());
        properties.setEnabled(creds);
        if (!creds) {
            log.warn(
                    "Cloudinary catalog uploads are disabled: set CLOUDINARY_URL (or CLOUDINARY_CLOUD_NAME + "
                            + "CLOUDINARY_API_KEY + CLOUDINARY_API_SECRET), then restart the JVM. "
                            + "The API also reads CLOUDINARY_URL from backend/.env or ./.env next to "
                            + "user.dir (see backend/.env.example).");
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String trimToEmpty(String s) {
        if (s == null) {
            return "";
        }
        return s.trim();
    }
}
