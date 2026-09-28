package zelisline.ub.desktop.device;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Component
@Profile("desktop")
@ConditionalOnProperty(name = "app.desktop.device.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class HttpDeviceBridge implements DeviceBridge {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build();

    private final String sidecarUrl;

    public HttpDeviceBridge(
        @Value("${app.desktop.device.sidecar-url:http://127.0.0.1:19500}") String sidecarUrl
    ) {
        this.sidecarUrl = sidecarUrl.endsWith("/")
            ? sidecarUrl.substring(0, sidecarUrl.length() - 1)
            : sidecarUrl;
    }

    @Override
    public void printEscPos(byte[] data) {
        postBytes("/print", data, "print");
    }

    @Override
    public void openCashDrawer() {
        postBytes("/drawer/kick", new byte[0], "drawer kick");
    }

    @Override
    public void restartBackend() {
        postBytes("/restart", new byte[0], "restart");
    }

    @Override
    public void openDataFolder() {
        postBytes("/open-data-folder", new byte[0], "open data folder");
    }

    @Override
    public BridgeHealth health() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(sidecarUrl + "/health"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
            HttpResponse<String> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new BridgeHealth(false, null, null, "Device bridge HTTP " + response.statusCode());
            }
            JsonNode node = JSON.readTree(response.body());
            return new BridgeHealth(
                node.path("ok").asBoolean(true),
                node.hasNonNull("cups") ? node.path("cups").asBoolean() : null,
                node.path("platform").asText(null),
                null
            );
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new BridgeHealth(false, null, null, "Device bridge unavailable");
        }
    }

    private void postBytes(String path, byte[] body, String label) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(sidecarUrl + path))
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .header("Content-Type", "application/octet-stream")
                .build();
            HttpResponse<String> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Device bridge " + label + " failed: HTTP " + response.statusCode()
                        + (response.body() != null && !response.body().isBlank()
                            ? " — " + response.body()
                            : "")
                );
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Device bridge {} failed: {}", label, e.toString());
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Device bridge unavailable. Is the Palmart desktop app running?"
            );
        }
    }
}
