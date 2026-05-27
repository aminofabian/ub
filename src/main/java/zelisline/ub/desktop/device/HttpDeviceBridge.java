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

import lombok.extern.slf4j.Slf4j;

@Component
@Profile("desktop")
@ConditionalOnProperty(name = "app.desktop.device.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class HttpDeviceBridge implements DeviceBridge {

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
