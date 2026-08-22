package zelisline.ub.desktop.license;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.KeyPair;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.desktop.license.DesktopLicenseIssuerConfigService.GenerateKeyResult;
import zelisline.ub.desktop.license.DesktopLicenseIssuerConfigService.IssuerStatus;
import zelisline.ub.desktop.license.DesktopLicenseIssuerConfigService.SetIssuerKeyRequest;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;

/**
 * Console-managed signing key (Super Admin → Platform → Desktop licenses →
 * "License issuer key"): status resolution (env vs console vs none), storing a
 * pasted key pair with a real signature pairing check, generating a fresh pair,
 * and clearing the console key.
 */
class DesktopLicenseIssuerConfigServiceTest {

    private final KeyPair keys = LicenseService.generateKeyPair();
    private final String privateKey = LicenseService.encodePrivateKey(keys.getPrivate());
    private final String publicKey = LicenseService.encodePublicKey(keys.getPublic());

    private final CredentialEncryptionService encryption = mock(CredentialEncryptionService.class);
    private final DesktopLicenseIssuerConfigRepository repo =
        mock(DesktopLicenseIssuerConfigRepository.class);

    /** Simulates the DB row written by save() being visible to later findById(). */
    private final AtomicReference<DesktopLicenseIssuerConfig> stored = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        when(encryption.encryptSecret(anyString())).thenAnswer(inv -> "enc:" + inv.getArgument(0));
        when(encryption.decrypt(anyString())).thenAnswer(inv -> {
            String enc = inv.getArgument(0);
            return enc.startsWith("enc:") ? enc.substring(4) : null;
        });
        when(encryption.usesEphemeralKey()).thenReturn(false);
        when(repo.findById(DesktopLicenseIssuerConfig.SINGLETON_ID))
            .thenAnswer(inv -> Optional.ofNullable(stored.get()));
        when(repo.save(any(DesktopLicenseIssuerConfig.class))).thenAnswer(inv -> {
            DesktopLicenseIssuerConfig row = inv.getArgument(0);
            stored.set(row);
            return row;
        });
        doAnswer(inv -> {
            stored.set(null);
            return null;
        }).when(repo).deleteById(anyString());
    }

    /** Issuer backed by the mocked DB (the Spring wiring in production). */
    private DesktopLicenseIssuer dbIssuer() {
        return new DesktopLicenseIssuer("", repo, encryption);
    }

    private DesktopLicenseIssuerConfigService service(DesktopLicenseIssuer issuer) {
        return new DesktopLicenseIssuerConfigService(repo, encryption, issuer);
    }

    @Test
    void envKeyIsReportedAsSourceAndWinsOverConsole() {
        DesktopLicenseIssuerConfig row = new DesktopLicenseIssuerConfig();
        row.setId(DesktopLicenseIssuerConfig.SINGLETON_ID);
        row.setPrivateKeyEnc("enc:" + privateKey);
        row.setPublicKey(publicKey);
        stored.set(row);

        // env key set AND a console row exists → env wins, still configured.
        DesktopLicenseIssuerConfigService service =
            service(new DesktopLicenseIssuer(privateKey, repo, encryption));
        IssuerStatus status = service.status();

        assertTrue(status.configured());
        assertEquals("env", status.source());
        assertEquals(publicKey, status.publicKey(), "stored public key is still surfaced for JAR pairing");
    }

    @Test
    void consoleKeyMakesIssuerConfigured() {
        DesktopLicenseIssuerConfigService service = service(dbIssuer());
        assertFalse(service.status().configured());

        service.setKey(new SetIssuerKeyRequest(privateKey, publicKey));
        IssuerStatus status = service.status();

        assertTrue(status.configured(), "saved console key must be usable immediately (no restart)");
        assertEquals("console", status.source());
        assertEquals(publicKey, status.publicKey());
        assertNotNull(status.updatedAt());

        // The same key must actually sign a token the till's verifier accepts.
        DesktopLicenseIssuer.IssuedLicense issued =
            dbIssuer().issue("Console Shop", "shop", null, "b".repeat(64));
        LicensePayload payload = new LicenseService(publicKey).decodeAndVerify(issued.token());
        assertNotNull(payload, "token issued with the console key must verify against its public key");
    }

    @Test
    void setKeyStoresPrivateKeyEncrypted() {
        DesktopLicenseIssuerConfigService service = service(dbIssuer());
        service.setKey(new SetIssuerKeyRequest(privateKey, null));

        verify(encryption).encryptSecret(privateKey);
        verify(repo).save(any(DesktopLicenseIssuerConfig.class));
        DesktopLicenseIssuerConfig saved = stored.get();
        assertNotNull(saved);
        assertEquals("enc:" + privateKey, saved.getPrivateKeyEnc());
        assertNull(saved.getPublicKey(), "no public key supplied → not recorded");
    }

    @Test
    void setKeyRejectsMismatchedPair() {
        KeyPair other = LicenseService.generateKeyPair();
        DesktopLicenseIssuerConfigService service = service(dbIssuer());

        ResponseStatusException ex = assertThrows(
            ResponseStatusException.class,
            () -> service.setKey(new SetIssuerKeyRequest(
                privateKey, LicenseService.encodePublicKey(other.getPublic())))
        );
        assertEquals(400, ex.getStatusCode().value());
        assertFalse(stored.get() != null, "mismatched pair must not be persisted");
    }

    @Test
    void setKeyRejectsInvalidPrivateKey() {
        DesktopLicenseIssuerConfigService service = service(dbIssuer());

        ResponseStatusException ex = assertThrows(
            ResponseStatusException.class,
            () -> service.setKey(new SetIssuerKeyRequest("not-a-key", null))
        );
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void generateReturnsPublicKeyAndConfiguresIssuer() {
        DesktopLicenseIssuerConfigService service = service(dbIssuer());

        GenerateKeyResult result = service.generate();

        assertNotNull(result.publicKey());
        assertNotEquals(publicKey, result.publicKey(), "generated key is a fresh pair — must NOT equal the test fixture key");
        IssuerStatus status = service.status();
        assertTrue(status.configured());
        assertEquals("console", status.source());
        assertEquals(result.publicKey(), status.publicKey());
        verify(encryption).encryptSecret(anyString());
    }

    @Test
    void clearRemovesConsoleKey() {
        DesktopLicenseIssuerConfigService service = service(dbIssuer());
        service.setKey(new SetIssuerKeyRequest(privateKey, publicKey));
        assertTrue(service.status().configured());

        IssuerStatus after = service.clear();

        verify(repo).deleteById(DesktopLicenseIssuerConfig.SINGLETON_ID);
        assertEquals("none", after.source());
        assertFalse(after.configured());
    }

    @Test
    void unconfiguredStatusReportsNone() {
        IssuerStatus status = service(dbIssuer()).status();
        assertEquals("none", status.source());
        assertFalse(status.configured());
        assertNull(status.publicKey());
        assertNull(status.updatedAt());
    }
}
