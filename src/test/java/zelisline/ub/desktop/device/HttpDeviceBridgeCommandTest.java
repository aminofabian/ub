package zelisline.ub.desktop.device;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * The Settings → Desktop control actions (restart, open data folder) reach the
 * shell through the device bridge. These pin the routes so the JVM and the Rust
 * sidecar cannot silently drift apart.
 */
class HttpDeviceBridgeCommandTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Records the request path and answers 202 Accepted. */
    private String recordingServer(AtomicReference<String> path) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(202, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void restartBackendPostsToRestartRoute() throws IOException {
        AtomicReference<String> path = new AtomicReference<>();
        HttpDeviceBridge bridge = new HttpDeviceBridge(recordingServer(path));

        bridge.restartBackend();

        assertThat(path.get()).isEqualTo("/restart");
    }

    @Test
    void openDataFolderPostsToOpenRoute() throws IOException {
        AtomicReference<String> path = new AtomicReference<>();
        HttpDeviceBridge bridge = new HttpDeviceBridge(recordingServer(path));

        bridge.openDataFolder();

        assertThat(path.get()).isEqualTo("/open-data-folder");
    }
}
