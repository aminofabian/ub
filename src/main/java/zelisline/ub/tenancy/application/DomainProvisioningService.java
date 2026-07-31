package zelisline.ub.tenancy.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.tenancy.domain.DomainMapping;
import zelisline.ub.tenancy.domain.DomainNsStatus;
import zelisline.ub.tenancy.domain.DomainOrder;
import zelisline.ub.tenancy.domain.DomainOrderStatus;
import zelisline.ub.tenancy.domain.DomainSource;
import zelisline.ub.tenancy.domain.DomainStatus;
import zelisline.ub.tenancy.domain.DomainZoneSource;
import zelisline.ub.tenancy.integrations.hostafrica.HostAfricaClient;
import zelisline.ub.tenancy.integrations.hostafrica.HostAfricaResellerClient;
import zelisline.ub.tenancy.integrations.vercel.VercelDnsClient;
import zelisline.ub.tenancy.integrations.vercel.VercelDomainZoneClient;
import zelisline.ub.tenancy.integrations.vercel.VercelProjectDomainClient;
import zelisline.ub.tenancy.repository.DomainMappingRepository;
import zelisline.ub.tenancy.repository.DomainOrderRepository;

/**
 * After HostAfrica ownership: Vercel zone + records + project attach + HA NS cutover + verify.
 */
@Service
@RequiredArgsConstructor
public class DomainProvisioningService {

    private final DomainOrderRepository domainOrderRepository;
    private final DomainMappingRepository domainMappingRepository;
    private final VercelDomainZoneClient zoneClient;
    private final VercelDnsClient dnsClient;
    private final VercelProjectDomainClient projectDomainClient;
    private final HostAfricaClient hostAfricaClient;
    private final HostAfricaResellerClient hostAfricaResellerClient;
    private final ObjectMapper objectMapper;

    @Transactional
    public void provision(DomainOrder order) {
        if (!zoneClient.configured() || !projectDomainClient.configured()) {
            order.setLastError("vercel_not_configured");
            domainOrderRepository.save(order);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Vercel is not configured");
        }

        order.setStatus(DomainOrderStatus.PROVISIONING);
        String apex = order.getFqdn().trim().toLowerCase(Locale.ROOT);
        String www = "www." + apex;

        // 1) DNS zone
        var zone = zoneClient.addZone(apex);
        if (!zone.ok()) {
            order.setLastError("Vercel zone: " + zone.error());
            domainOrderRepository.save(order);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Vercel zone failed: " + zone.error());
        }
        order.setVercelZoneReady(true);
        List<String> intendedNs = zone.intendedNameservers() == null ? List.of() : zone.intendedNameservers();

        // 2) Apex + www records (may only apply fully after NS cutover)
        var dns = dnsClient.ensureStorefrontRecords(apex);
        List<String> dnsNotes = new ArrayList<>();
        if (dns.ok()) {
            dnsNotes.addAll(dns.created());
            dnsNotes.addAll(dns.warnings());
        } else if (!dns.skipped()) {
            dnsNotes.add("dns_pending: " + dns.error());
        }

        // 3) Ensure mapping row (inactive until verified)
        DomainMapping mapping = ensureMapping(order, apex, intendedNs, dnsNotes);
        order.setDomainMappingId(mapping.getId());

        // 4) Attach apex + www to project
        var attachApex = projectDomainClient.addDomain(apex);
        if (!attachApex.ok() && !attachApex.skipped()) {
            order.setLastError("project attach apex: " + attachApex.error());
            writeInstructions(mapping, intendedNs, dnsNotes, attachApex.dnsInstructions());
            domainMappingRepository.save(mapping);
            domainOrderRepository.save(order);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Vercel project attach failed: " + attachApex.error());
        }
        var attachWww = projectDomainClient.addDomain(www);
        if (!attachWww.ok() && !attachWww.skipped()) {
            // www is best-effort; continue with apex
            dnsNotes.add("www_attach: " + attachWww.error());
        }

        // 5) NS cutover via HostAfrica (when domain_id known); reseller SaveNameServers fallback
        if (intendedNs.size() >= 2) {
            boolean nsOk = false;
            String nsError = null;
            if (order.getHostafricaDomainId() != null && !order.getHostafricaDomainId().isBlank()) {
                var ns = hostAfricaClient.updateNameservers(order.getHostafricaDomainId(), intendedNs);
                if (ns.ok()) {
                    nsOk = true;
                } else if (!ns.skipped()) {
                    nsError = ns.error();
                }
            }
            if (!nsOk && hostAfricaResellerClient.configured()) {
                var resellerNs = hostAfricaResellerClient.saveNameServers(apex, intendedNs);
                if (resellerNs.ok()) {
                    nsOk = true;
                    nsError = null;
                } else if (!resellerNs.skipped() && nsError == null) {
                    nsError = resellerNs.error();
                } else if (!resellerNs.skipped() && nsError != null) {
                    nsError = nsError + "; reseller: " + resellerNs.error();
                }
            }
            if (nsOk) {
                order.setNsStatus(DomainNsStatus.ACTIVE);
            } else {
                order.setNsStatus(DomainNsStatus.PENDING_OPS);
                if (nsError != null) {
                    order.setLastError("NS cutover: " + nsError);
                }
                dnsNotes.add("Set nameservers at HostAfrica to: " + String.join(", ", intendedNs));
            }
        } else {
            order.setNsStatus(DomainNsStatus.PENDING_OPS);
            dnsNotes.add("Set nameservers to: " + String.join(", ", intendedNs));
        }

        // 6) Verify apex
        var verify = projectDomainClient.verifyDomain(apex);
        writeInstructions(mapping, intendedNs, dnsNotes, verify.dnsInstructions());
        if (verify.ok() && verify.verified()) {
            mapping.setActive(true);
            mapping.setStatus(DomainStatus.ACTIVE);
            mapping.setVerifiedAt(Instant.now());
            mapping.setLastError(null);
            domainMappingRepository.save(mapping);
            // Also ensure www mapping exists for host resolve (optional secondary row)
            ensureWwwMapping(order.getBusinessId(), www, mapping);
            order.setStatus(DomainOrderStatus.LIVE);
            order.setLastError(null);
            order.setNsStatus(order.getNsStatus() == DomainNsStatus.ACTIVE
                    ? DomainNsStatus.ACTIVE
                    : DomainNsStatus.PENDING_OPS);
        } else {
            mapping.setStatus(DomainStatus.PENDING);
            mapping.setActive(false);
            mapping.setLastError(verify.ok() ? "awaiting_dns_propagation" : verify.error());
            domainMappingRepository.save(mapping);
            order.setLastError(mapping.getLastError());
        }
        domainOrderRepository.save(order);
    }

    private DomainMapping ensureMapping(
            DomainOrder order,
            String apex,
            List<String> intendedNs,
            List<String> dnsNotes
    ) {
        DomainMapping mapping;
        if (order.getDomainMappingId() != null) {
            mapping = domainMappingRepository.findById(order.getDomainMappingId()).orElse(null);
            if (mapping != null && mapping.getDeletedAt() == null) {
                writeInstructions(mapping, intendedNs, dnsNotes, Map.of());
                return domainMappingRepository.save(mapping);
            }
        }
        var existing = domainMappingRepository.findByDomainAndDeletedAtIsNull(apex);
        if (existing.isPresent()) {
            mapping = existing.get();
            if (!mapping.getBusinessId().equals(order.getBusinessId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Domain is already mapped to another business");
            }
        } else {
            mapping = new DomainMapping();
            mapping.setBusinessId(order.getBusinessId());
            mapping.setDomain(apex);
            mapping.setPrimary(false);
            mapping.setActive(false);
            mapping.setStatus(DomainStatus.PENDING);
            mapping.setSource(DomainSource.HOSTAFRICA_PURCHASE);
            mapping.setZoneSource(DomainZoneSource.VERCEL);
            mapping.setHostafricaDomainId(order.getHostafricaDomainId());
        }
        writeInstructions(mapping, intendedNs, dnsNotes, Map.of());
        return domainMappingRepository.save(mapping);
    }

    private void ensureWwwMapping(String businessId, String www, DomainMapping apex) {
        if (domainMappingRepository.findByDomainAndDeletedAtIsNull(www).isPresent()) {
            return;
        }
        DomainMapping row = new DomainMapping();
        row.setBusinessId(businessId);
        row.setDomain(www);
        row.setPrimary(false);
        row.setActive(true);
        row.setStatus(DomainStatus.ACTIVE);
        row.setSource(DomainSource.HOSTAFRICA_PURCHASE);
        row.setZoneSource(DomainZoneSource.VERCEL);
        row.setHostafricaDomainId(apex.getHostafricaDomainId());
        row.setVerifiedAt(Instant.now());
        domainMappingRepository.save(row);
    }

    private void writeInstructions(
            DomainMapping mapping,
            List<String> intendedNs,
            List<String> dnsNotes,
            Map<String, Object> vercelHints
    ) {
        Map<String, Object> instructions = new LinkedHashMap<>();
        instructions.put("provider", "vercel");
        instructions.put("hostname", mapping.getDomain());
        instructions.put("intendedNameservers", intendedNs);
        instructions.put("notes", dnsNotes);
        instructions.put(
                "recommendedRecords",
                List.of(
                        Map.of("type", "A", "name", "@", "value", VercelDnsClient.APEX_A_TARGET),
                        Map.of("type", "CNAME", "name", "www", "value", VercelDnsClient.WWW_CNAME_TARGET)
                )
        );
        if (vercelHints != null && !vercelHints.isEmpty()) {
            instructions.put("vercel", vercelHints);
        }
        try {
            mapping.setDnsInstructionJson(objectMapper.writeValueAsString(instructions));
        } catch (Exception ex) {
            mapping.setDnsInstructionJson(null);
        }
    }
}
