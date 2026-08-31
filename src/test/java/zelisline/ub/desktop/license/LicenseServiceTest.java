package zelisline.ub.desktop.license;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Machine-binding of license tokens: a token only activates on the till whose
 * fingerprint it carries. This is the guard that stops one person's key being
 * used on another person's machine.
 */
class LicenseServiceTest {

    private static final String BUSINESS = "Test Shop";
    private static final String THIS_MACHINE = "a".repeat(64);
    private static final String OTHER_MACHINE = "b".repeat(64);

    private final KeyPair keys = LicenseService.generateKeyPair();
    private final String privateKey = LicenseService.encodePrivateKey(keys.getPrivate());
    private final String publicKey = LicenseService.encodePublicKey(keys.getPublic());

    /** Till-side verifier bound to THIS_MACHINE. */
    private LicenseService till;

    @BeforeEach
    void setUp() {
        till = new LicenseService(publicKey, () -> THIS_MACHINE);
    }

    private String tokenFor(String fingerprint, String businessName, Instant expiresAt) {
        LicensePayload payload = new LicensePayload(
            businessName, "shop", Instant.now().minus(1, ChronoUnit.DAYS), expiresAt, fingerprint);
        return LicenseService.encodeToken(payload, LicenseService.decodePrivateKey(privateKey));
    }

    @Test
    void tokenForThisMachineIsActive() {
        LicenseStatus status = till.checkStatus(
            tokenFor(THIS_MACHINE, BUSINESS, Instant.now().plus(30, ChronoUnit.DAYS)),
            BUSINESS);

        assertEquals("active", status.state());
        assertFalse(status.readOnly());
        assertEquals(THIS_MACHINE, status.machineId());
    }

    @Test
    void tokenForAnotherMachineIsRejected() {
        LicenseStatus status = till.checkStatus(
            tokenFor(OTHER_MACHINE, BUSINESS, Instant.now().plus(30, ChronoUnit.DAYS)),
            BUSINESS);

        assertEquals("invalid", status.state());
        assertTrue(status.readOnly(), "a foreign-machine key must flip the till read-only");
        assertTrue(status.message().toLowerCase().contains("different machine"));
        assertEquals(THIS_MACHINE, status.machineId(), "the till still surfaces its own Machine ID");
    }

    @Test
    void tokenWithoutFingerprintIsRejected() {
        LicenseStatus status = till.checkStatus(
            tokenFor(null, BUSINESS, Instant.now().plus(30, ChronoUnit.DAYS)),
            BUSINESS);

        assertEquals("invalid", status.state());
        assertTrue(status.readOnly());
        assertTrue(status.message().toLowerCase().contains("not bound"));
    }

    @Test
    void fingerprintIsCaseInsensitive() {
        LicenseStatus status = till.checkStatus(
            tokenFor(THIS_MACHINE.toUpperCase(), BUSINESS, Instant.now().plus(30, ChronoUnit.DAYS)),
            BUSINESS);

        assertEquals("active", status.state());
    }

    @Test
    void wrongBusinessNameIsRejectedBeforeMachineCheck() {
        LicenseStatus status = till.checkStatus(
            tokenFor(THIS_MACHINE, "Other Shop", Instant.now().plus(30, ChronoUnit.DAYS)),
            BUSINESS);

        assertEquals("invalid", status.state());
        assertTrue(status.message().contains("'Other Shop'"));
    }

    @Test
    void expiredTokenIsExpiredNotInvalid() {
        LicenseStatus status = till.checkStatus(
            tokenFor(THIS_MACHINE, BUSINESS, Instant.now().minus(1, ChronoUnit.DAYS)),
            BUSINESS);

        assertEquals("expired", status.state());
        assertTrue(status.readOnly());
        assertEquals(THIS_MACHINE, status.machineId());
    }

    @Test
    void badSignatureIsInvalid() {
        LicenseStatus status = till.checkStatus("not-a-token", BUSINESS);

        assertEquals("invalid", status.state());
    }

    @Test
    void trialModeSurfacesMachineId() {
        // Trial path doesn't need a token — the machine id must still be shown.
        // (Which trial sub-state comes back depends on ~/.palmart/.initialized.)
        LicenseStatus status = till.checkStatus(null, BUSINESS);
        assertTrue(
            "trial".equals(status.state()) || "trial_expired".equals(status.state()),
            "expected a trial state, got " + status.state());
        assertNotNull(status.machineId());
    }

    @Test
    void decodeAndVerifyStillWorksWithoutProvider() {
        // The standalone constructor (vendor CLI verify) has no machine to
        // bind against — signature verification alone still works.
        LicenseService standalone = new LicenseService(publicKey);
        LicensePayload payload = standalone.decodeAndVerify(
            tokenFor(THIS_MACHINE, BUSINESS, Instant.now().plus(30, ChronoUnit.DAYS)));
        assertNotNull(payload);
        assertEquals(BUSINESS, payload.businessName());
        assertEquals(THIS_MACHINE, payload.machineFingerprint());
    }

    @Test
    void syncedConsoleKeyOverridesBakedKey() {
        // A license signed with the Super Admin console's key (the pasted key
        // that motivated the platform sync) must verify once the till has
        // synced that key — even though the baked key is a different pair.
        KeyPair consoleKeys = LicenseService.generateKeyPair();
        String consoleToken = LicenseService.encodeToken(
            new LicensePayload(BUSINESS, "shop", Instant.now(), null, THIS_MACHINE),
            LicenseService.decodePrivateKey(LicenseService.encodePrivateKey(consoleKeys.getPrivate())));

        // Baked key alone rejects the console-signed token.
        assertNull(till.decodeAndVerify(consoleToken));

        // After the syncer pushes the console public key, it verifies.
        till.updateSyncedPublicKey(consoleKeys.getPublic());
        assertNotNull(till.decodeAndVerify(consoleToken));

        // The synced key takes precedence: a token signed with the baked key
        // now fails.
        assertNull(till.decodeAndVerify(
            tokenFor(THIS_MACHINE, BUSINESS, Instant.now().plus(30, ChronoUnit.DAYS))));
    }

    @Test
    void derivedPublicKeyMatchesEncodedPair() {
        // The platform endpoint derives the public key from the private key;
        // it must equal the X.509 encoding of the same pair.
        assertEquals(
            publicKey,
            LicenseService.derivePublicKeyFromPrivate(privateKey));
    }

    @Test
    void activeKeySourceReflectsBakedThenSyncedThenNone() {
        // Baked key present, nothing synced yet.
        assertEquals("baked", till.activeKeySource());

        // Once the console key is synced, it takes over.
        KeyPair console = LicenseService.generateKeyPair();
        till.updateSyncedPublicKey(console.getPublic());
        assertEquals("synced", till.activeKeySource());

        // No baked key at all → trial-only until something is configured.
        LicenseService none = new LicenseService((String) null, null);
        assertEquals("none", none.activeKeySource());
    }
}
