package zelisline.ub.platform.media;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Downloads a remote image for re-hosting. Cloudinary used to fetch these on its own
 * servers; now the backend does, so private / loopback / link-local targets are refused
 * to keep the endpoint from being used to reach internal services.
 */
class RemoteImageFetcher {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);
    private static final int HTTP_OK_MIN = 200;
    private static final int HTTP_OK_MAX = 299;

    private final HttpClient httpClient;
    private final int maxBytes;

    RemoteImageFetcher(int maxBytes) {
        this(HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(), maxBytes);
    }

    RemoteImageFetcher(HttpClient httpClient, int maxBytes) {
        this.httpClient = httpClient;
        this.maxBytes = maxBytes;
    }

    byte[] fetch(String remoteUrl) {
        URI uri = parsePublicHttpUri(remoteUrl);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).GET().build();
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < HTTP_OK_MIN || response.statusCode() > HTTP_OK_MAX) {
                throw badGateway("Remote image returned HTTP " + response.statusCode());
            }
            return readCapped(response.body());
        } catch (IOException e) {
            throw badGateway("Could not download remote image: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw badGateway("Remote image download interrupted");
        }
    }

    static URI parsePublicHttpUri(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty remote image URL");
        }
        URI uri;
        try {
            uri = URI.create(remoteUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid remote image URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Remote image URL must be http(s)");
        }
        if (uri.getHost() == null || isInternalHost(uri.getHost())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Remote image host is not allowed");
        }
        return uri;
    }

    private static boolean isInternalHost(String host) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                        || address.isAnyLocalAddress() || address.isMulticastAddress()) {
                    return true;
                }
            }
            return false;
        } catch (UnknownHostException e) {
            throw badGateway("Remote image host could not be resolved");
        }
    }

    private byte[] readCapped(InputStream body) throws IOException {
        try (body) {
            byte[] bytes = body.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Remote image exceeds size limit");
            }
            return bytes;
        }
    }

    private static ResponseStatusException badGateway(String message) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
    }
}
