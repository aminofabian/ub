package zelisline.ub.tenancy.integrations.storefront;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;

import javax.naming.Context;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves tenant DNS against a public recursive resolver and compares to the
 * configured storefront A / CNAME targets.
 */
@Component
public class DnsTargetChecker {

    private static final Logger log = LoggerFactory.getLogger(DnsTargetChecker.class);

    private final StorefrontDomainsProperties properties;

    public DnsTargetChecker(StorefrontDomainsProperties properties) {
        this.properties = properties;
    }

    public CheckResult check(String hostname) {
        String host = hostname == null ? "" : hostname.trim().toLowerCase(Locale.ROOT);
        if (host.isBlank()) {
            return CheckResult.mismatch("empty hostname");
        }

        String expectedA = properties.resolvedATarget();
        String expectedCname = properties.resolvedCnameTarget().toLowerCase(Locale.ROOT);

        List<String> messages = new ArrayList<>();
        boolean apexOk;
        boolean wwwOk;

        if (StorefrontDnsInstructions.isLikelyApex(host)) {
            apexOk = matchesA(host, expectedA, messages);
            wwwOk = matchesCnameOrA("www." + host, expectedCname, expectedA, messages);
            if (apexOk && wwwOk) {
                return CheckResult.match();
            }
            // Apex alone is enough to attach (www can follow); warn but allow.
            if (apexOk) {
                return CheckResult.matchWithNotes(String.join("; ", messages));
            }
            return CheckResult.mismatch(String.join("; ", messages));
        }

        if (host.startsWith("www.")) {
            String apex = host.substring(4);
            apexOk = matchesA(apex, expectedA, messages);
            wwwOk = matchesCnameOrA(host, expectedCname, expectedA, messages);
            if (apexOk || wwwOk) {
                return apexOk && wwwOk
                        ? CheckResult.match()
                        : CheckResult.matchWithNotes(String.join("; ", messages));
            }
            return CheckResult.mismatch(String.join("; ", messages));
        }

        // Subdomain leaf → must CNAME (or A) to our targets.
        if (matchesCnameOrA(host, expectedCname, expectedA, messages)) {
            return CheckResult.match();
        }
        return CheckResult.mismatch(String.join("; ", messages));
    }

    private boolean matchesA(String host, String expectedIp, List<String> messages) {
        List<String> addrs = resolve(host, "A");
        if (addrs.isEmpty()) {
            messages.add(host + " has no A record (expected " + expectedIp + ")");
            return false;
        }
        if (addrs.stream().anyMatch(a -> a.equals(expectedIp))) {
            return true;
        }
        messages.add(host + " A record points to " + String.join(", ", addrs) + ", expected " + expectedIp);
        return false;
    }

    private boolean matchesCnameOrA(
            String host,
            String expectedCname,
            String expectedA,
            List<String> messages
    ) {
        List<String> cnames = resolve(host, "CNAME");
        if (!cnames.isEmpty()) {
            boolean ok = cnames.stream()
                    .map(c -> c.toLowerCase(Locale.ROOT).replaceAll("\\.$", ""))
                    .anyMatch(c -> c.equals(expectedCname) || c.endsWith("." + expectedCname));
            if (ok) {
                return true;
            }
            messages.add(host + " CNAME points to " + String.join(", ", cnames) + ", expected " + expectedCname);
            // Fall through — Cloudflare-proxied hosts often expose A only.
        }
        List<String> addrs = resolve(host, "A");
        if (addrs.stream().anyMatch(a -> a.equals(expectedA))) {
            return true;
        }
        if (cnames.isEmpty() && addrs.isEmpty()) {
            messages.add(host + " has no CNAME/A (expected CNAME " + expectedCname + " or A " + expectedA + ")");
        } else if (!addrs.isEmpty()) {
            messages.add(host + " A record points to " + String.join(", ", addrs) + ", expected " + expectedA);
        }
        return false;
    }

    private List<String> resolve(String host, String type) {
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
            env.put(Context.PROVIDER_URL, "dns://1.1.1.1");
            InitialDirContext ctx = new InitialDirContext(env);
            Attributes attrs = ctx.getAttributes(host, new String[] {type});
            Attribute attr = attrs.get(type);
            ctx.close();
            if (attr == null) {
                return List.of();
            }
            List<String> out = new ArrayList<>(attr.size());
            for (int i = 0; i < attr.size(); i++) {
                Object v = attr.get(i);
                if (v != null) {
                    out.add(v.toString().trim());
                }
            }
            return out;
        } catch (Exception ex) {
            // Fallback: JVM default resolver for A only.
            if ("A".equals(type)) {
                try {
                    InetAddress[] all = InetAddress.getAllByName(host);
                    List<String> out = new ArrayList<>(all.length);
                    for (InetAddress a : all) {
                        out.add(a.getHostAddress());
                    }
                    return out;
                } catch (Exception ignored) {
                    log.debug("DNS resolve failed for {}: {}", host, ex.getMessage());
                }
            }
            log.debug("DNS {} lookup failed for {}: {}", type, host, ex.getMessage());
            return List.of();
        }
    }

    public record CheckResult(boolean matched, String message) {
        public static CheckResult match() {
            return new CheckResult(true, null);
        }

        public static CheckResult matchWithNotes(String notes) {
            return new CheckResult(true, notes);
        }

        public static CheckResult mismatch(String message) {
            return new CheckResult(false, message);
        }
    }
}
