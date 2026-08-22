package zelisline.ub.desktop.license;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Machine fingerprint: deterministic SHA-256 of the raw identity, cached after
 * first computation, and overridable for VMs / tests.
 */
class MachineFingerprintTest {

    @Test
    void overrideIsHashedIntoTheFingerprint() {
        MachineFingerprint fp = new MachineFingerprint("vm-raw-id-123");
        String expected = MachineFingerprint.sha256Hex("vm-raw-id-123");
        assertEquals(expected, fp.get());
        assertEquals(64, fp.get().length(), "fingerprint is a SHA-256 hex digest");
        assertTrue(fp.get().matches("[0-9a-f]{64}"));
    }

    @Test
    void resultIsStableAcrossCalls() {
        MachineFingerprint fp = new MachineFingerprint("stable-raw");
        assertEquals(fp.get(), fp.get(), "cached — repeated reads return the same value");
    }

    @Test
    void differentRawIdsProduceDifferentFingerprints() {
        assertNotEquals(
            new MachineFingerprint("machine-a").get(),
            new MachineFingerprint("machine-b").get());
    }

    @Test
    void noOverrideStillProducesAValue() {
        // On any OS this must resolve to *something* (machine-guid / uuid /
        // machine-id / MAC fallback) — never null or blank.
        MachineFingerprint fp = new MachineFingerprint(null);
        assertNotNull(fp.get());
        assertTrue(fp.get().matches("[0-9a-f]{64}"));
    }
}
