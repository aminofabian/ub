package zelisline.ub.platform.media;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import lombok.Getter;
import lombok.Setter;

/** Cloudflare R2 (S3-compatible) media bucket. Off unless {@code APP_MEDIA_R2_ENABLED=true}. */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.media.r2")
public class R2Properties {

    private boolean enabled = false;
    private String accountId = "";
    /** Optional; derived from {@link #accountId} when blank. */
    private String endpoint = "";
    private String bucket = "";
    private String accessKeyId = "";
    private String secretAccessKey = "";
    /** Public delivery base, e.g. {@code https://media.example.com} or the bucket's r2.dev URL. */
    private String publicBaseUrl = "";

    public String resolvedEndpoint() {
        if (StringUtils.hasText(endpoint)) {
            return trimTrailingSlash(endpoint.trim());
        }
        return StringUtils.hasText(accountId)
                ? "https://" + accountId.trim() + ".r2.cloudflarestorage.com"
                : "";
    }

    public String resolvedPublicBaseUrl() {
        return trimTrailingSlash(publicBaseUrl == null ? "" : publicBaseUrl.trim());
    }

    /** Env var names of the settings that are still blank. */
    public List<String> missingSettings() {
        List<String> missing = new ArrayList<>();
        if (!StringUtils.hasText(resolvedEndpoint())) missing.add("R2_ACCOUNT_ID or R2_ENDPOINT");
        if (!StringUtils.hasText(bucket)) missing.add("R2_BUCKET");
        if (!StringUtils.hasText(accessKeyId)) missing.add("R2_ACCESS_KEY_ID");
        if (!StringUtils.hasText(secretAccessKey)) missing.add("R2_SECRET_ACCESS_KEY");
        if (!StringUtils.hasText(resolvedPublicBaseUrl())) missing.add("R2_PUBLIC_BASE_URL");
        return missing;
    }

    private static String trimTrailingSlash(String value) {
        return value.replaceAll("/+$", "");
    }
}
