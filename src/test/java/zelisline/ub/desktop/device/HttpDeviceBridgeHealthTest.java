package zelisline.ub.desktop.device;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * The device-bridge health probe backs the Settings → Desktop status pill. It
 * must read the sidecar's {@code /health} payload and, just as importantly,
 * report an unreachable bridge as a state rather than throwing at the settings
 * page.
 */
class HttpDeviceBridgeHealthTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void readsHealthPayloadFromSidecar() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            byte[] body =
                "{\"ok\":true,\"cups\":true,\"platform\":\"macos\",\"port\":19500}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();

        DeviceBridge.BridgeHealth health = new HttpDeviceBridge(url).health();

        assertThat(health.reachable()).isTrue();
        assertThat(health.cups()).isTrue();
        assertThat(health.platform()).isEqualTo("macos");
        assertThat(health.error()).isNull();
    }

    @Test
    void unreachableSidecarIsReportedNotThrown() {
        // Nothing is listening on this port — the probe must still return.
        DeviceBridge.BridgeHealth health =
            new HttpDeviceBridge("http://127.0.0.1:1").health();

        assertThat(health.reachable()).isFalse();
        assertThat(health.error()).isNotBlank();
    }
}
