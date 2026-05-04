package zelisline.ub.integrations.webhook.support;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Webhook subscription SSRF guard — blocks loopback, RFC1918 (unless opted-in), link-local (always),
 * IPv6 ULA (unless opted-in), and well-known metadata hostnames.
 */
@Component
public class WebhookTargetHostValidator {

    private static final ExecutorService DNS_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "webhook-dns-verify");
        t.setDaemon(true);
        return t;
    });

    private final boolean allowLoopbackAndRfc1918;
    private final boolean resolveHostnames;
    private final long resolveTimeoutMs;

    public WebhookTargetHostValidator(
            @Value("${app.integrations.webhook.allow-loopback-and-rfc1918-targets:false}")
                    boolean allowLoopbackAndRfc1918,
            @Value("${app.integrations.webhook.ssrf.resolve-hostnames:true}") boolean resolveHostnames,
            @Value("${app.integrations.webhook.ssrf.resolve-timeout-ms:1500}") long resolveTimeoutMs
    ) {
        this.allowLoopbackAndRfc1918 = allowLoopbackAndRfc1918;
        this.resolveHostnames = resolveHostnames;
        this.resolveTimeoutMs = Math.max(200, resolveTimeoutMs);
    }

    /** Validates {@link URI#getHost()} after scheme/host normalization. */
    public void validateResolvedHost(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "target_url must include a host");
        }
        String host = hostHeader.trim().toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.endsWith(".localhost")) {
            if (!allowLoopbackAndRfc1918) {
                throw blocked();
            }
            return;
        }
        if ("metadata.google.internal".equals(host)) {
            throw blocked();
        }

        byte[] v4 = tryParseIpv4Octets(host);
        if (v4 != null) {
            assertInetAddressAllowed(inetFromIpv4Bytes(v4));
            return;
        }

        if (host.contains(":")) {
            try {
                assertInetAddressAllowed(InetAddress.getByName(host.startsWith("[") ? host : "[" + host + "]"));
            } catch (UnknownHostException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid target_url host");
            }
            return;
        }

        if (!resolveHostnames) {
            return;
        }

        String lookup = safeToUnicode(host);
        try {
            Future<InetAddress[]> fut = DNS_POOL.submit(() -> InetAddress.getAllByName(lookup));
            InetAddress[] addrs = fut.get(resolveTimeoutMs, TimeUnit.MILLISECONDS);
            if (addrs == null || addrs.length == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not resolve webhook host");
            }
            for (InetAddress a : addrs) {
                assertInetAddressAllowed(a);
            }
        } catch (TimeoutException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Webhook host DNS lookup timed out");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof UnknownHostException) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown webhook host");
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not resolve webhook host");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Webhook host lookup interrupted");
        }
    }

    private InetAddress inetFromIpv4Bytes(byte[] v4) {
        try {
            return InetAddress.getByAddress(v4);
        } catch (UnknownHostException e) {
            throw blocked();
        }
    }

    private void assertInetAddressAllowed(InetAddress addr) {
        InetAddress effective = unwrapIpv4Mapped(addr);
        byte[] raw = effective.getAddress();

        if (effective.isLinkLocalAddress()) {
            throw blocked();
        }
        if (!allowLoopbackAndRfc1918
                && (effective.isLoopbackAddress() || effective.isSiteLocalAddress())) {
            throw blocked();
        }
        if (raw.length == 16) {
            int hi = raw[0] & 0xff;
            if (!allowLoopbackAndRfc1918 && (hi & 0xfe) == 0xfc) {
                throw blocked();
            }
        }
    }

    private static InetAddress unwrapIpv4Mapped(InetAddress addr) {
        byte[] raw = addr.getAddress();
        if (raw.length == 16 && raw[10] == (byte) 0xff && raw[11] == (byte) 0xff) {
            try {
                return InetAddress.getByAddress(new byte[] {raw[12], raw[13], raw[14], raw[15]});
            } catch (UnknownHostException e) {
                throw blocked();
            }
        }
        return addr;
    }

    private static byte[] tryParseIpv4Octets(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] out = new byte[4];
        try {
            for (int i = 0; i < 4; i++) {
                int v = Integer.parseInt(parts[i]);
                if (v < 0 || v > 255) {
                    return null;
                }
                out[i] = (byte) v;
            }
            return out;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String safeToUnicode(String asciiHost) {
        try {
            return IDN.toUnicode(asciiHost);
        } catch (Exception e) {
            return asciiHost;
        }
    }

    private static ResponseStatusException blocked() {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "target_url host is not allowed (internal/link-local/metadata ranges blocked)");
    }
}
