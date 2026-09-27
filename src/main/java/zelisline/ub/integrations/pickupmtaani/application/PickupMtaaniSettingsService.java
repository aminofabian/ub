package zelisline.ub.integrations.pickupmtaani.application;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;
import zelisline.ub.integrations.pickupmtaani.api.dto.PickupMtaaniGeoOption;
import zelisline.ub.integrations.pickupmtaani.api.dto.PickupMtaaniPatchRequest;
import zelisline.ub.integrations.pickupmtaani.api.dto.PickupMtaaniPublicConfig;
import zelisline.ub.integrations.pickupmtaani.api.dto.PickupMtaaniSettingsResponse;
import zelisline.ub.integrations.pickupmtaani.infrastructure.PickupMtaaniClient;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.KEY_ACCOUNT_MODE;
import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.KEY_API_KEY_ENC;
import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.KEY_BOOK_ON_DISPATCH;
import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.KEY_BUSINESS_NAME;
import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.KEY_ENABLED;
import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.KEY_FEE_MODE;
import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.KEY_MARKUP_KES;
import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.KEY_MODE_AGENT;
import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.KEY_MODE_DOORSTEP;
import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.KEY_ORIGIN_AGENT_ID;
import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.KEY_ORIGIN_AGENT_NAME;
import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.KEY_ORIGIN_LOCATION_NAME;
import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.KEY_STATUS;
import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.KEY_STATUS_DETAIL;
import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.MODE_SINGLE_BUSINESS;
import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.STATUS_CONNECTED;
import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.STATUS_DISCONNECTED;
import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.boolOrElse;
import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.boolOrNull;
import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.intOrElse;
import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.longOrNull;
import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.putOrRemove;
import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.textOrNull;

/**
 * The merchant's Pickup Mtaani option: on/off, origin agent, fee mode, and
 * book-on-dispatch, stored under {@code businesses.settings.pickupMtaani}.
 *
 * <p>The API key is <strong>not</strong> managed here — a merchant request that
 * carries one is rejected. Super-admin owns the credential (see
 * {@link PickupMtaaniCredentialService}); this service only reads it server-side
 * to price quotes and drive origin search.
 */
@Service
@RequiredArgsConstructor
public class PickupMtaaniSettingsService {

    private static final Set<String> FEE_MODES = Set.of(
            PickupMtaaniSettingsJson.FEE_MODE_PASS_THROUGH,
            PickupMtaaniSettingsJson.FEE_MODE_ABSORB,
            PickupMtaaniSettingsJson.FEE_MODE_MARKUP);

    private final PickupMtaaniSettingsJson json;
    private final CredentialEncryptionService credentialEncryptionService;
    private final BusinessRepository businessRepository;
    private final PickupMtaaniClient pickupMtaaniClient;

    // ── Merchant reads ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PickupMtaaniSettingsResponse settings(String businessId) {
        return readFromSettingsJson(requireBusiness(businessId).getSettings());
    }

    public PickupMtaaniSettingsResponse readFromSettingsJson(String settingsJson) {
        ObjectNode ns = json.namespaceOrNull(settingsJson);
        if (ns == null) {
            return PickupMtaaniSettingsResponse.disconnected();
        }
        String accountMode = textOrNull(ns.path(KEY_ACCOUNT_MODE));
        Long originAgentId = longOrNull(ns.path(KEY_ORIGIN_AGENT_ID));
        boolean enabled = Boolean.TRUE.equals(boolOrNull(ns.path(KEY_ENABLED)));
        boolean hasKey = textOrNull(ns.path(KEY_API_KEY_ENC)) != null;
        boolean ready = enabled && hasKey && MODE_SINGLE_BUSINESS.equals(accountMode) && originAgentId != null;
        String status = textOrNull(ns.path(KEY_STATUS));
        if (status == null) {
            status = hasKey ? STATUS_CONNECTED : STATUS_DISCONNECTED;
        }
        return new PickupMtaaniSettingsResponse(
                enabled,
                textOrNull(ns.path(KEY_BUSINESS_NAME)),
                originAgentId,
                textOrNull(ns.path(KEY_ORIGIN_AGENT_NAME)),
                textOrNull(ns.path(KEY_ORIGIN_LOCATION_NAME)),
                feeModeOrDefault(ns.path(KEY_FEE_MODE)),
                intOrElse(ns.path(KEY_MARKUP_KES), 0),
                boolOrElse(ns.path(KEY_MODE_AGENT), true),
                boolOrElse(ns.path(KEY_MODE_DOORSTEP), true),
                boolOrElse(ns.path(KEY_BOOK_ON_DISPATCH), true),
                status,
                textOrNull(ns.path(KEY_STATUS_DETAIL)),
                ready
        );
    }

    /**
     * Secret-free capability for storefront checkout. Returns {@code null} when
     * the shop does not offer the option, so the caller omits the block entirely.
     * KES-only in V1 (scope §4).
     */
    public PickupMtaaniPublicConfig readPublicConfig(String settingsJson, String currency) {
        if (currency == null || !"KES".equalsIgnoreCase(currency.trim())) {
            return null;
        }
        ObjectNode ns = json.namespaceOrNull(settingsJson);
        if (ns == null) {
            return null;
        }
        boolean enabled = Boolean.TRUE.equals(boolOrNull(ns.path(KEY_ENABLED)));
        boolean hasKey = textOrNull(ns.path(KEY_API_KEY_ENC)) != null;
        boolean single = MODE_SINGLE_BUSINESS.equals(textOrNull(ns.path(KEY_ACCOUNT_MODE)));
        Long originId = longOrNull(ns.path(KEY_ORIGIN_AGENT_ID));
        boolean agent = boolOrElse(ns.path(KEY_MODE_AGENT), true);
        boolean doorstep = boolOrElse(ns.path(KEY_MODE_DOORSTEP), true);
        if (!(enabled && hasKey && single && originId != null && (agent || doorstep))) {
            return null;
        }
        String label = textOrNull(ns.path(KEY_ORIGIN_AGENT_NAME));
        if (label == null) {
            label = textOrNull(ns.path(KEY_ORIGIN_LOCATION_NAME));
        }
        return new PickupMtaaniPublicConfig(true, agent, doorstep, label);
    }

    // ── Merchant writes ─────────────────────────────────────────────────────

    /**
     * Applies the merchant option patch. A body that carries an API key is
     * rejected: credentials are super-admin's to set (scope §6).
     */
    @Transactional
    public PickupMtaaniSettingsResponse update(String businessId, PickupMtaaniPatchRequest patch) {
        if (patch != null && patch.apiKey() != null && !patch.apiKey().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Pickup Mtaani credentials are managed by Palmart support, not here.");
        }
        Business business = requireBusiness(businessId);
        ObjectNode root = json.parseRoot(business.getSettings());
        ObjectNode ns = json.copyNamespace(root);
        applyConfig(ns, patch);
        root.set(PickupMtaaniSettingsJson.KEY_NAMESPACE, ns);
        business.setSettings(json.write(root));
        businessRepository.save(business);
        return readFromSettingsJson(business.getSettings());
    }

    // ── Proxied origin search ───────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PickupMtaaniGeoOption> listZones(String businessId) {
        String apiKey = requireApiKey(businessId);
        return toDtos(pickupMtaaniClient.listZones(apiKey));
    }

    @Transactional(readOnly = true)
    public List<PickupMtaaniGeoOption> listAreas(String businessId, Long zoneId) {
        String apiKey = requireApiKey(businessId);
        return toDtos(pickupMtaaniClient.listAreas(apiKey, zoneId));
    }

    @Transactional(readOnly = true)
    public List<PickupMtaaniGeoOption> listLocations(
            String businessId, Long areaId, String purpose, String searchKey) {
        String apiKey = requireApiKey(businessId);
        return toDtos(pickupMtaaniClient.listLocations(apiKey, areaId, normalizePurpose(purpose), searchKey));
    }

    @Transactional(readOnly = true)
    public List<PickupMtaaniGeoOption> listAgents(
            String businessId, Long locationId, String purpose, String searchKey) {
        String apiKey = requireApiKey(businessId);
        return toDtos(pickupMtaaniClient.listAgents(apiKey, locationId, normalizePurpose(purpose), searchKey));
    }

    // ── Server-side resolved config ─────────────────────────────────────────

    /**
     * Decrypted key plus the settings that decide eligibility and pricing, for
     * the quote and booking pipelines.
     */
    @Transactional(readOnly = true)
    public PickupMtaaniResolved resolve(String businessId) {
        ObjectNode ns = json.namespaceOrNull(requireBusiness(businessId).getSettings());
        if (ns == null) {
            return PickupMtaaniResolved.disabled();
        }
        String apiKeyEnc = textOrNull(ns.path(KEY_API_KEY_ENC));
        return new PickupMtaaniResolved(
                apiKeyEnc == null ? null : credentialEncryptionService.decrypt(apiKeyEnc),
                Boolean.TRUE.equals(boolOrNull(ns.path(KEY_ENABLED))),
                textOrNull(ns.path(KEY_ACCOUNT_MODE)),
                longOrNull(ns.path(KEY_ORIGIN_AGENT_ID)),
                textOrNull(ns.path(KEY_ORIGIN_AGENT_NAME)),
                textOrNull(ns.path(KEY_ORIGIN_LOCATION_NAME)),
                boolOrElse(ns.path(KEY_MODE_AGENT), true),
                boolOrElse(ns.path(KEY_MODE_DOORSTEP), true),
                feeModeOrDefault(ns.path(KEY_FEE_MODE)),
                intOrElse(ns.path(KEY_MARKUP_KES), 0),
                boolOrElse(ns.path(KEY_BOOK_ON_DISPATCH), true)
        );
    }

    public record PickupMtaaniResolved(
            String apiKey,
            boolean enabled,
            String accountMode,
            Long originAgentId,
            String originAgentName,
            String originLocationName,
            boolean agent,
            boolean doorstep,
            String feeMode,
            int markupKes,
            boolean bookOnDispatch
    ) {
        public static PickupMtaaniResolved disabled() {
            return new PickupMtaaniResolved(
                    null, false, null, null, null, null, true, true,
                    PickupMtaaniSettingsJson.FEE_MODE_PASS_THROUGH, 0, true);
        }

        public boolean ready() {
            return enabled && apiKey != null && !apiKey.isBlank()
                    && MODE_SINGLE_BUSINESS.equals(accountMode) && originAgentId != null;
        }
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private void applyConfig(ObjectNode ns, PickupMtaaniPatchRequest patch) {
        if (patch == null) {
            return;
        }
        if (patch.enabled() != null) {
            ns.put(KEY_ENABLED, patch.enabled());
        }
        if (patch.feeMode() != null) {
            String feeMode = patch.feeMode().trim().toLowerCase(Locale.ROOT);
            if (!FEE_MODES.contains(feeMode)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "feeMode must be pass_through, absorb, or markup");
            }
            ns.put(KEY_FEE_MODE, feeMode);
        }
        if (patch.markupKes() != null) {
            ns.put(KEY_MARKUP_KES, patch.markupKes());
        }
        if (patch.agent() != null) {
            ns.put(KEY_MODE_AGENT, patch.agent());
        }
        if (patch.doorstep() != null) {
            ns.put(KEY_MODE_DOORSTEP, patch.doorstep());
        }
        if (patch.bookOnDispatch() != null) {
            ns.put(KEY_BOOK_ON_DISPATCH, patch.bookOnDispatch());
        }
        if (patch.originAgentId() != null) {
            ns.put(KEY_ORIGIN_AGENT_ID, patch.originAgentId());
        }
        if (patch.originAgentName() != null) {
            putOrRemove(ns, KEY_ORIGIN_AGENT_NAME, patch.originAgentName());
        }
        if (patch.originLocationName() != null) {
            putOrRemove(ns, KEY_ORIGIN_LOCATION_NAME, patch.originLocationName());
        }
        if (PickupMtaaniSettingsJson.FEE_MODE_MARKUP.equals(feeModeOrDefault(ns.path(KEY_FEE_MODE)))
                && intOrElse(ns.path(KEY_MARKUP_KES), 0) <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "A markup fee mode requires markupKes greater than zero.");
        }
    }

    private String requireApiKey(String businessId) {
        String apiKey = resolve(businessId).apiKey();
        if (apiKey == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Pickup Mtaani is not connected for this shop.");
        }
        return apiKey;
    }

    private Business requireBusiness(String businessId) {
        return businessRepository.findByIdAndDeletedAtIsNull(businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found"));
    }

    private static List<PickupMtaaniGeoOption> toDtos(List<PickupMtaaniClient.GeoOption> options) {
        return options.stream()
                .map(o -> new PickupMtaaniGeoOption(o.id(), o.name(), o.zoneId(), o.areaId(), o.locationId()))
                .toList();
    }

    private static String normalizePurpose(String purpose) {
        if (purpose == null || purpose.isBlank()) {
            return null;
        }
        String p = purpose.trim().toLowerCase(Locale.ROOT);
        return ("origin".equals(p) || "destination".equals(p)) ? p : null;
    }

    private static String feeModeOrDefault(com.fasterxml.jackson.databind.JsonNode node) {
        String value = textOrNull(node);
        return value != null && FEE_MODES.contains(value)
                ? value
                : PickupMtaaniSettingsJson.FEE_MODE_PASS_THROUGH;
    }
}
