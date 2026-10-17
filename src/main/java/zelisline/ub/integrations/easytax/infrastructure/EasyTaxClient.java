package zelisline.ub.integrations.easytax.infrastructure;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;

/**
 * Minimal EasyTax REST client.
 *
 * <p>Follows the same Unirest conventions as the Meta CAPI client: HTTP Basic
 * auth, explicit connect/socket timeouts, and a result record the caller can
 * persist and show to the operator.
 *
 * <p>Only the connectivity/authentication probe is implemented here. Invoice,
 * credit note, and item submission are added once Pergamon supplies the request
 * and response schemas — see {@code docs/EASYTAX_INTEGRATION_OPEN_QUESTIONS.md}.
 */
@Component
public class EasyTaxClient {

    private static final Logger log = LoggerFactory.getLogger(EasyTaxClient.class);

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int SOCKET_TIMEOUT_MS = 30_000;

    /**
     * A documented, protected endpoint used as a connectivity/auth probe — a 2xx
     * proves both that the host is reachable and that the credentials are accepted.
     */
    private static final String PROBE_PATH = "/customers";

    public ProbeResult probe(String baseUrl, String username, String password) {
        String root = trimTrailingSlash(baseUrl);
        if (root == null || root.isBlank()) {
            return ProbeResult.failed("Base URL is not set", null);
        }
        try {
            HttpResponse<String> response = Unirest.get(root + PROBE_PATH)
                    .header("Authorization", basicAuth(username, password))
                    .header("Accept", "application/json")
                    .connectTimeout(CONNECT_TIMEOUT_MS)
                    .socketTimeout(SOCKET_TIMEOUT_MS)
                    .asString();
            int status = response.getStatus();
            if (status >= 200 && status < 300) {
                return ProbeResult.ok("Connection succeeded (HTTP " + status + ")");
            }
            if (status == 401 || status == 403) {
                return ProbeResult.failed(
                        "Authentication failed — check the username and password", status);
            }
            if (status == 404) {
                return ProbeResult.failed(
                        "Reached the host but " + PROBE_PATH + " was not found — check the base URL "
                                + "includes the API path",
                        status);
            }
            return ProbeResult.failed("EasyTax responded HTTP " + status, status);
        } catch (Exception ex) {
            log.warn("EasyTax probe failed for {}: {}", root, ex.getMessage());
            return ProbeResult.failed("Could not reach EasyTax: " + ex.getMessage(), null);
        }
    }

    private static String basicAuth(String username, String password) {
        String user = username == null ? "" : username;
        String pass = password == null ? "" : password;
        String token = Base64.getEncoder()
                .encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }

    private static String trimTrailingSlash(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    public record ProbeResult(boolean ok, String message, Integer httpStatus) {

        public static ProbeResult ok(String message) {
            return new ProbeResult(true, message, 200);
        }

        public static ProbeResult failed(String message, Integer httpStatus) {
            return new ProbeResult(false, message, httpStatus);
        }
    }
}
