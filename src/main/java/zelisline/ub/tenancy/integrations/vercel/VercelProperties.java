package zelisline.ub.tenancy.integrations.vercel;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Vercel REST credentials for project domain attach / verify (Coolify secrets).
 * When {@link #token} or {@link #projectId} is blank, clients no-op and manual
 * connect falls back to DNS-instruction-only pending state.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.integrations.vercel")
public class VercelProperties {

    /** Bearer token ({@code VERCEL_TOKEN}). */
    private String token = "";

    /** Team / org id ({@code VERCEL_TEAM_ID}). Optional for personal accounts. */
    private String teamId = "";

    /** Next.js project id ({@code VERCEL_PROJECT_ID}). */
    private String projectId = "";

    /** API base, no trailing slash. */
    private String apiBaseUrl = "https://api.vercel.com";

    public boolean configured() {
        return token != null && !token.isBlank()
                && projectId != null && !projectId.isBlank();
    }
}
