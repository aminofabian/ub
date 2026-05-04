package zelisline.ub.integrations.backup.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import zelisline.ub.integrations.backup.config.BackupProperties;

class BackupEncryptionServiceTest {

    @Test
    void encryptBytes_roundTrips() throws Exception {
        BackupProperties props = new BackupProperties();
        props.getEncryption().setPassphrase("phase8-slice4-unit-test-secret");
        BackupEncryptionService svc = new BackupEncryptionService(props);

        byte[] plain = "tenant data snapshot".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] cipher = svc.encryptBytes(plain);
        assertThat(cipher).isNotEqualTo(plain);
        byte[] back = svc.decryptBytes(cipher);
        assertThat(back).isEqualTo(plain);
    }

    @Test
    void decryptBytes_rejectsBadMagic() {
        BackupProperties props = new BackupProperties();
        props.getEncryption().setPassphrase("x");
        BackupEncryptionService svc = new BackupEncryptionService(props);
        org.junit.jupiter.api.Assertions.assertThrows(
                java.security.GeneralSecurityException.class,
                () -> svc.decryptBytes("bogus".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
