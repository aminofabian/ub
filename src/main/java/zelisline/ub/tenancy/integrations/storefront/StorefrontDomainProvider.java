package zelisline.ub.tenancy.integrations.storefront;

import java.util.List;
import java.util.Map;

/**
 * Attaches / checks / detaches a custom hostname on the storefront host
 * (Vercel project or Coolify app). Selected by {@code app.domains.provider}.
 */
public interface StorefrontDomainProvider {

    String id();

    boolean configured();

    /** Recommended A/CNAME records for the tenant's registrar. */
    Map<String, Object> recommendedDnsInstructions(String hostname);

    /**
     * Called when the merchant connects a domain.
     * Coolify: instructions only (no API call). Vercel: attach to the project.
     */
    Result connect(String hostname);

    /**
     * Called when the merchant chooses Check connection.
     * Coolify: public DNS check, then Coolify attach + deploy when DNS matches.
     * Vercel: project domain verify.
     */
    Result check(String hostname);

    /** Remove the hostname from the hosting provider (best-effort). */
    void detach(String hostname);

    enum Outcome {
        /** DNS / ownership not ready yet — stay PENDING. */
        NOT_READY,
        /** Hostname attached; TLS still issuing — stay VERIFYING. */
        AWAITING_CERTIFICATE,
        /** Hostname is live over HTTPS. */
        VERIFIED,
        /** Hard failure. */
        FAILED,
        /** Provider not configured — caller decides. */
        SKIPPED
    }

    record Result(
            Outcome outcome,
            boolean ok,
            boolean skipped,
            boolean verified,
            Map<String, Object> dnsInstructions,
            String error,
            Integer httpStatus
    ) {
        public static Result skipped(String reason) {
            return new Result(Outcome.SKIPPED, false, true, false, Map.of(), reason, null);
        }

        public static Result notReady(Map<String, Object> dns, String message) {
            return new Result(Outcome.NOT_READY, true, false, false, dns == null ? Map.of() : dns, message, null);
        }

        public static Result awaitingCertificate(Map<String, Object> dns) {
            return new Result(Outcome.AWAITING_CERTIFICATE, true, false, false, dns == null ? Map.of() : dns, null, null);
        }

        public static Result verified(Map<String, Object> dns) {
            return new Result(Outcome.VERIFIED, true, false, true, dns == null ? Map.of() : dns, null, null);
        }

        public static Result failed(String error) {
            return failed(error, null, Map.of());
        }

        public static Result failed(String error, Integer httpStatus, Map<String, Object> dns) {
            return new Result(Outcome.FAILED, false, false, false, dns == null ? Map.of() : dns, error, httpStatus);
        }
    }

    record DnsChallenge(String type, String domain, String value, String reason) {}
}
