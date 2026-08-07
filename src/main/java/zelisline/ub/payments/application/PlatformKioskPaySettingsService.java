package zelisline.ub.payments.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.api.dto.PlatformKioskPaySettingsResponse;
import zelisline.ub.payments.api.dto.UpdatePlatformKioskPaySettingsRequest;
import zelisline.ub.payments.domain.PlatformKioskPaySettings;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.payments.infrastructure.PaystackPaymentGateway;
import zelisline.ub.payments.repository.PlatformKioskPaySettingsRepository;

@Service
@RequiredArgsConstructor
public class PlatformKioskPaySettingsService {

    private final PlatformKioskPaySettingsRepository repository;
    private final CredentialEncryptionService encryptionService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public PlatformKioskPaySettingsResponse getForSuperAdmin() {
        return toResponse(loadSingleton());
    }

    @Transactional
    public PlatformKioskPaySettingsResponse update(UpdatePlatformKioskPaySettingsRequest body) {
        PlatformKioskPaySettings row = loadSingleton();
        if (body.enabled() != null) {
            row.setEnabled(body.enabled());
        }
        if (body.feePercent() != null) {
            row.setFeePercent(clampFee(body.feePercent()));
        }
        if (body.minWithdrawAmount() != null) {
            row.setMinWithdrawAmount(body.minWithdrawAmount().max(BigDecimal.ZERO));
        }
        if (body.dailyWithdrawLimit() != null) {
            row.setDailyWithdrawLimit(body.dailyWithdrawLimit().max(BigDecimal.ZERO));
        }
        if (body.currency() != null && !body.currency().isBlank()) {
            row.setCurrency(body.currency().trim().toUpperCase());
        }
        if (body.paystackEnvironment() != null && !body.paystackEnvironment().isBlank()) {
            row.setPaystackEnvironment(normalizeEnv(body.paystackEnvironment()));
        }
        if (body.kopokopoEnvironment() != null && !body.kopokopoEnvironment().isBlank()) {
            row.setKopokopoEnvironment(normalizeEnv(body.kopokopoEnvironment()));
        }

        if (Boolean.TRUE.equals(body.clearPaystackCredentials())) {
            row.setPaystackCredentialsEnc(null);
        } else {
            mergePaystack(row, body);
        }
        if (Boolean.TRUE.equals(body.clearKopokopoCredentials())) {
            row.setKopokopoCredentialsEnc(null);
        } else {
            mergeKopokopo(row, body);
        }

        return toResponse(repository.save(row));
    }

    @Transactional(readOnly = true)
    public boolean isProductEnabled() {
        return loadSingleton().isEnabled();
    }

    @Transactional(readOnly = true)
    public PlatformKioskPaySettings requireEnabledSettings() {
        PlatformKioskPaySettings row = loadSingleton();
        if (!row.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kiosk Pay is not enabled on this platform");
        }
        return row;
    }

    @Transactional(readOnly = true)
    public Optional<Map<String, String>> paystackCredentials() {
        PlatformKioskPaySettings row = loadSingleton();
        return decryptMap(row.getPaystackCredentialsEnc());
    }

    @Transactional(readOnly = true)
    public Optional<Map<String, String>> kopokopoCredentials() {
        PlatformKioskPaySettings row = loadSingleton();
        return decryptMap(row.getKopokopoCredentialsEnc());
    }

    @Transactional(readOnly = true)
    public PlatformKioskPaySettings loadSingleton() {
        return repository.findById(PlatformKioskPaySettings.SINGLETON_ID).orElseGet(() -> {
            PlatformKioskPaySettings created = new PlatformKioskPaySettings();
            created.setId(PlatformKioskPaySettings.SINGLETON_ID);
            return repository.save(created);
        });
    }

    private void mergePaystack(PlatformKioskPaySettings row, UpdatePlatformKioskPaySettingsRequest body) {
        if (body.paystackPublicKey() == null && body.paystackSecretKey() == null) {
            return;
        }
        Map<String, String> merged = decryptMap(row.getPaystackCredentialsEnc()).orElseGet(LinkedHashMap::new);
        if (body.paystackPublicKey() != null && !body.paystackPublicKey().isBlank()) {
            merged.put("publicKey", body.paystackPublicKey().trim());
        }
        if (body.paystackSecretKey() != null && !body.paystackSecretKey().isBlank()) {
            merged.put("secretKey", body.paystackSecretKey().trim());
        }
        merged.put("environment", row.getPaystackEnvironment());
        var mismatch = PaystackPaymentGateway.validateKeyEnvironment(merged);
        if (mismatch != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, mismatch.errorMessage());
        }
        row.setPaystackCredentialsEnc(encryptionService.encrypt(writeJson(merged)));
    }

    private void mergeKopokopo(PlatformKioskPaySettings row, UpdatePlatformKioskPaySettingsRequest body) {
        if (body.kopokopoClientId() == null
                && body.kopokopoClientSecret() == null
                && body.kopokopoApiKey() == null
                && body.kopokopoTillNumber() == null) {
            return;
        }
        Map<String, String> merged = decryptMap(row.getKopokopoCredentialsEnc()).orElseGet(LinkedHashMap::new);
        if (body.kopokopoClientId() != null && !body.kopokopoClientId().isBlank()) {
            merged.put("clientId", body.kopokopoClientId().trim());
        }
        if (body.kopokopoClientSecret() != null && !body.kopokopoClientSecret().isBlank()) {
            merged.put("clientSecret", body.kopokopoClientSecret().trim());
        }
        if (body.kopokopoApiKey() != null && !body.kopokopoApiKey().isBlank()) {
            merged.put("apiKey", body.kopokopoApiKey().trim());
        }
        if (body.kopokopoTillNumber() != null && !body.kopokopoTillNumber().isBlank()) {
            merged.put("tillNumber", body.kopokopoTillNumber().trim());
        }
        merged.put("environment", row.getKopokopoEnvironment());
        row.setKopokopoCredentialsEnc(encryptionService.encrypt(writeJson(merged)));
    }

    private PlatformKioskPaySettingsResponse toResponse(PlatformKioskPaySettings row) {
        Map<String, String> paystack = decryptMap(row.getPaystackCredentialsEnc()).orElse(Map.of());
        String publicKey = paystack.get("publicKey");
        String hint = publicKey != null && publicKey.length() > 12
                ? publicKey.substring(0, 10) + "…"
                : (publicKey != null && !publicKey.isBlank() ? "••••" : null);
        return new PlatformKioskPaySettingsResponse(
                row.isEnabled(),
                row.getFeePercent(),
                row.getMinWithdrawAmount(),
                row.getDailyWithdrawLimit(),
                row.getCurrency(),
                row.getPaystackEnvironment(),
                row.getPaystackCredentialsEnc() != null && !row.getPaystackCredentialsEnc().isBlank(),
                hint,
                row.getKopokopoEnvironment(),
                row.getKopokopoCredentialsEnc() != null && !row.getKopokopoCredentialsEnc().isBlank(),
                row.getUpdatedAt() != null ? row.getUpdatedAt() : Instant.now());
    }

    private Optional<Map<String, String>> decryptMap(String enc) {
        if (enc == null || enc.isBlank()) {
            return Optional.empty();
        }
        try {
            String json = encryptionService.decrypt(enc);
            Map<String, String> map = objectMapper.readValue(json, new TypeReference<>() {
            });
            return Optional.of(new LinkedHashMap<>(map));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String writeJson(Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not serialize credentials");
        }
    }

    private static BigDecimal clampFee(BigDecimal fee) {
        if (fee.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP);
        }
        if (fee.compareTo(new BigDecimal("50")) > 0) {
            return new BigDecimal("50.000");
        }
        return fee.setScale(3, RoundingMode.HALF_UP);
    }

    private static String normalizeEnv(String env) {
        return "production".equalsIgnoreCase(env.trim()) ? "production" : "sandbox";
    }
}
