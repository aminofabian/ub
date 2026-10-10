package zelisline.ub.platform.application;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.platform.api.dto.PlatformWhatsAppAppSecretDtos.AppSecretRow;
import zelisline.ub.platform.domain.PlatformWhatsAppAppSecret;
import zelisline.ub.platform.repository.PlatformWhatsAppAppSecretRepository;

/**
 * Additional platform Meta app secrets accepted when verifying inbound WhatsApp webhooks.
 *
 * <p>The primary secret stays on {@link PlatformIntegrationSettingsService}; these are extras so a
 * WABA with more than one subscribed Meta app can be served without replacing the first secret.
 * Secrets are write-only: the API only ever returns a last-4 fingerprint.
 */
@Service
@RequiredArgsConstructor
public class PlatformWhatsAppAppSecretService {

    private final PlatformWhatsAppAppSecretRepository repository;
    private final CredentialEncryptionService encryptionService;

    /** A decrypted secret with the source label used in support logs. */
    public record LabeledSecret(String source, String secret) {
    }

    @Transactional(readOnly = true)
    public List<AppSecretRow> list() {
        List<AppSecretRow> rows = new ArrayList<>();
        for (PlatformWhatsAppAppSecret row : repository.findAll(Sort.by(Sort.Direction.ASC, "createdAt"))) {
            rows.add(toRow(row));
        }
        return rows;
    }

    @Transactional
    public AppSecretRow add(String label, String plaintextSecret) {
        String secret = plaintextSecret == null ? "" : plaintextSecret.trim();
        if (secret.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "App secret is required");
        }
        PlatformWhatsAppAppSecret row = new PlatformWhatsAppAppSecret();
        row.setLabel(trimToNull(label));
        row.setAppSecretEnc(encryptionService.encryptSecret(secret));
        return toRow(repository.save(row));
    }

    @Transactional
    public void delete(String id) {
        if (id == null || id.isBlank() || !repository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "App secret not found");
        }
        repository.deleteById(id);
    }

    /** Decrypted secrets for inbound webhook verification — used with the per-number candidates. */
    @Transactional(readOnly = true)
    public List<LabeledSecret> labeledSecrets() {
        List<LabeledSecret> out = new ArrayList<>();
        for (PlatformWhatsAppAppSecret row : repository.findAll()) {
            String secret = decrypt(row.getAppSecretEnc());
            if (secret != null && !secret.isBlank()) {
                String label = row.getLabel() == null || row.getLabel().isBlank() ? row.getId() : row.getLabel();
                out.add(new LabeledSecret("extra:" + label, secret.trim()));
            }
        }
        return out;
    }

    private AppSecretRow toRow(PlatformWhatsAppAppSecret row) {
        return new AppSecretRow(row.getId(), row.getLabel(), fingerprint(decrypt(row.getAppSecretEnc())),
                row.getCreatedAt());
    }

    private String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        try {
            return encryptionService.decrypt(encrypted);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String fingerprint(String secret) {
        if (secret == null || secret.isBlank()) {
            return null;
        }
        String trimmed = secret.trim();
        return trimmed.length() <= 4 ? "****" : "…" + trimmed.substring(trimmed.length() - 4);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
