package zelisline.ub.tenancy.application;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import zelisline.ub.tenancy.api.dto.MobileTenantProfileExport;

/**
 * Optionally triggers a GitHub Actions workflow to build a tenant mobile app.
 */
@Service
public class GitHubMobilePublishDispatcher {

    private static final Logger log = LoggerFactory.getLogger(GitHubMobilePublishDispatcher.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    @Value("${app.mobile.publish.github-token:}")
    private String githubToken;

    @Value("${app.mobile.publish.github-repo:}")
    private String githubRepo;

    @Value("${app.mobile.publish.event-type:mobile-tenant-publish}")
    private String eventType;

    public GitHubMobilePublishDispatcher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public boolean isConfigured() {
        return githubToken != null && !githubToken.isBlank()
                && githubRepo != null && !githubRepo.isBlank()
                && githubRepo.contains("/");
    }

    /**
     * @return GitHub Actions run search URL (best-effort) or repo actions page
     */
    public String dispatch(
            String slug,
            String app,
            String platform,
            MobileTenantProfileExport tenantProfile
    ) {
        if (!isConfigured()) {
            return null;
        }

        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("slug", slug);
            payload.put("app", app);
            payload.put("platform", platform);
            payload.set("tenantProfile", objectMapper.valueToTree(tenantProfile));

            ObjectNode body = objectMapper.createObjectNode();
            body.put("event_type", eventType);
            body.set("client_payload", payload);

            String[] parts = githubRepo.trim().split("/", 2);
            String owner = parts[0];
            String repo = parts[1];
            URI uri = URI.create("https://api.github.com/repos/" + owner + "/" + repo + "/dispatches");

            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + githubToken.trim())
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warn(
                        "GitHub dispatch failed for slug={} status={} body={}",
                        slug,
                        response.statusCode(),
                        response.body()
                );
                throw new IllegalStateException("GitHub workflow dispatch failed (" + response.statusCode() + ")");
            }

            return "https://github.com/" + owner + "/" + repo + "/actions";
        } catch (Exception e) {
            log.warn("GitHub dispatch error for slug={}: {}", slug, e.getMessage());
            throw new IllegalStateException("Could not start automated build: " + e.getMessage(), e);
        }
    }
}
