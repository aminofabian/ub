package zelisline.ub.integrations.pickupmtaani.application;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;
import zelisline.ub.integrations.pickupmtaani.api.dto.PickupMtaaniCredentialResponse;
import zelisline.ub.integrations.pickupmtaani.infrastructure.PickupMtaaniApiException;
import zelisline.ub.integrations.pickupmtaani.infrastructure.PickupMtaaniClient;
import zelisline.ub.integrations.pickupmtaani.infrastructure.PickupMtaaniClient.AccountInfo;
import zelisline.ub.integrations.pickupmtaani.infrastructure.PickupMtaaniClient.BusinessInfo;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.KEY_ACCOUNT_MODE;
import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.KEY_API_KEY_ENC;
import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.KEY_BUSINESS_ID;
import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.KEY_BUSINESS_NAME;
import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.KEY_ENABLED;
import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.KEY_LAST_VERIFIED_AT;
import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.KEY_STATUS;
import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.KEY_STATUS_DETAIL;
import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.MODE_MULTI_BUSINESS;
import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.MODE_SINGLE_BUSINESS;
import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.STATUS_CONNECTED;
import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.STATUS_DISCONNECTED;
import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.STATUS_ERROR;
import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.longOrNull;
import static zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsJson.textOrNull;

/**
 * Super-admin-owned Pickup Mtaani credential (scope §6). One legacy API key per
 * Palmart business, encrypted at rest and never returned by any API.
 *
 * <p>A failed verify never enables checkout — the key is stored and the status is
 * set to {@code error} with a safe detail, so the operator can see why.
 */
@Service
@RequiredArgsConstructor
public class PickupMtaaniCredentialService {

    private static final String MULTI_BUSINESS_MESSAGE =
            "This key can act for more than one Pickup Mtaani business. V1 needs a key tied to one business.";

    private final PickupMtaaniSettingsJson json;
    private final CredentialEncryptionService credentialEncryptionService;
    private final BusinessRepository businessRepository;
    private final PickupMtaaniClient pickupMtaaniClient;

    @Transactional(readOnly = true)
    public PickupMtaaniCredentialResponse read(String businessId) {
        ObjectNode ns = json.namespaceOrNull(requireBusiness(businessId).getSettings());
        if (ns == null || textOrNull(ns.path(KEY_API_KEY_ENC)) == null) {
            return PickupMtaaniCredentialResponse.disconnected();
        }
        return new PickupMtaaniCredentialResponse(
                true,
                longOrNull(ns.path(KEY_BUSINESS_ID)),
                textOrNull(ns.path(KEY_BUSINESS_NAME)),
                textOrNull(ns.path(KEY_ACCOUNT_MODE)),
                textOrNull(ns.path(KEY_LAST_VERIFIED_AT)),
                textOrNull(ns.path(KEY_STATUS)),
                textOrNull(ns.path(KEY_STATUS_DETAIL))
        );
    }

    /** Saves or replaces the key, encrypts it, then verifies upstream. */
    @Transactional
    public PickupMtaaniCredentialResponse save(String businessId, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "apiKey is required");
        }
        Business business = requireBusiness(businessId);
        ObjectNode root = json.parseRoot(business.getSettings());
        ObjectNode ns = json.copyNamespace(root);
        ns.put(KEY_API_KEY_ENC, credentialEncryptionService.encryptSecret(apiKey.trim()));
        runVerification(ns, apiKey.trim());
        root.set(PickupMtaaniSettingsJson.KEY_NAMESPACE, ns);
        business.setSettings(json.write(root));
        businessRepository.save(business);
        return read(businessId);
    }

    /** Re-runs {@code GET /account} + {@code GET /business} with the stored key. */
    @Transactional
    public PickupMtaaniCredentialResponse verify(String businessId) {
        Business business = requireBusiness(businessId);
        ObjectNode root = json.parseRoot(business.getSettings());
        ObjectNode ns = json.copyNamespace(root);
        String apiKey = resolveApiKey(ns);
        if (apiKey == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Save a Pickup Mtaani API key before verifying.");
        }
        runVerification(ns, apiKey);
        root.set(PickupMtaaniSettingsJson.KEY_NAMESPACE, ns);
        business.setSettings(json.write(root));
        businessRepository.save(business);
        return read(businessId);
    }

    /** Deletes the credential and turns the merchant option off. */
    @Transactional
    public PickupMtaaniCredentialResponse disconnect(String businessId) {
        Business business = requireBusiness(businessId);
        ObjectNode root = json.parseRoot(business.getSettings());
        ObjectNode ns = json.copyNamespace(root);
        ns.remove(KEY_API_KEY_ENC);
        ns.remove(KEY_BUSINESS_ID);
        ns.remove(KEY_BUSINESS_NAME);
        ns.remove(KEY_ACCOUNT_MODE);
        ns.remove(KEY_LAST_VERIFIED_AT);
        ns.remove(KEY_STATUS_DETAIL);
        ns.put(KEY_ENABLED, false);
        ns.put(KEY_STATUS, STATUS_DISCONNECTED);
        root.set(PickupMtaaniSettingsJson.KEY_NAMESPACE, ns);
        business.setSettings(json.write(root));
        businessRepository.save(business);
        return read(businessId);
    }

    private void runVerification(ObjectNode ns, String apiKey) {
        try {
            AccountInfo account = pickupMtaaniClient.getAccount(apiKey);
            String mode = account.mode() == null ? MODE_SINGLE_BUSINESS : account.mode();
            if (MODE_MULTI_BUSINESS.equals(mode)) {
                ns.put(KEY_ACCOUNT_MODE, MODE_MULTI_BUSINESS);
                ns.put(KEY_ENABLED, false);
                ns.put(KEY_STATUS, STATUS_ERROR);
                ns.put(KEY_STATUS_DETAIL, MULTI_BUSINESS_MESSAGE);
                return;
            }
            BusinessInfo info = pickupMtaaniClient.getBusiness(apiKey);
            ns.put(KEY_ACCOUNT_MODE, MODE_SINGLE_BUSINESS);
            if (info != null && info.id() != null) {
                ns.put(KEY_BUSINESS_ID, info.id());
            }
            if (info != null && info.name() != null) {
                ns.put(KEY_BUSINESS_NAME, info.name());
            }
            ns.put(KEY_STATUS, STATUS_CONNECTED);
            ns.remove(KEY_STATUS_DETAIL);
            ns.put(KEY_LAST_VERIFIED_AT, Instant.now().toString());
        } catch (PickupMtaaniApiException ex) {
            ns.put(KEY_STATUS, STATUS_ERROR);
            ns.put(KEY_STATUS_DETAIL, ex.getMessage());
        } catch (RuntimeException ex) {
            ns.put(KEY_STATUS, STATUS_ERROR);
            ns.put(KEY_STATUS_DETAIL, "Could not reach Pickup Mtaani. Try again.");
        }
    }

    private String resolveApiKey(ObjectNode ns) {
        String apiKeyEnc = textOrNull(ns.path(KEY_API_KEY_ENC));
        return apiKeyEnc == null ? null : credentialEncryptionService.decrypt(apiKeyEnc);
    }

    private Business requireBusiness(String businessId) {
        return businessRepository.findByIdAndDeletedAtIsNull(businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found"));
    }
}
