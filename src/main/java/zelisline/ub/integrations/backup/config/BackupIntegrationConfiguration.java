package zelisline.ub.integrations.backup.config;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import io.micrometer.core.instrument.MeterRegistry;
import zelisline.ub.integrations.backup.application.BackupArtifactStorage;
import zelisline.ub.integrations.backup.application.BackupEncryptionService;
import zelisline.ub.integrations.backup.application.DatabaseBackupOrchestrator;
import zelisline.ub.integrations.backup.application.ExternalProcessDatabaseDumper;
import zelisline.ub.integrations.backup.application.LocalBackupArtifactStorage;
import zelisline.ub.integrations.backup.application.S3BackupArtifactStorage;
import zelisline.ub.integrations.backup.repository.BackupRunRepository;

@Configuration
public class BackupIntegrationConfiguration {

    @Bean
    @ConditionalOnProperty(name = "app.integrations.backup.enabled", havingValue = "true")
    public BackupArtifactStorage backupArtifactStorage(BackupProperties properties) {
        if (StringUtils.hasText(properties.getLocalDir())) {
            return new LocalBackupArtifactStorage(properties);
        }
        return new S3BackupArtifactStorage(properties);
    }

    @Bean
    @ConditionalOnProperty(name = "app.integrations.backup.enabled", havingValue = "true")
    public DatabaseBackupOrchestrator databaseBackupOrchestrator(
            BackupProperties properties,
            BackupRunRepository backupRunRepository,
            ExternalProcessDatabaseDumper dumper,
            BackupEncryptionService encryption,
            BackupArtifactStorage artifactStorage,
            DataSource dataSource,
            MeterRegistry meterRegistry) {
        return new DatabaseBackupOrchestrator(
                properties,
                backupRunRepository,
                dumper,
                encryption,
                artifactStorage,
                dataSource,
                meterRegistry);
    }
}
