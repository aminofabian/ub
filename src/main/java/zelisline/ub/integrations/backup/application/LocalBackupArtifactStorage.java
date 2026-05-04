package zelisline.ub.integrations.backup.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;

import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.integrations.backup.config.BackupProperties;

/** Writes encrypted artefacts under {@code local-dir}/{objectKey}. */
@RequiredArgsConstructor
@Slf4j
public class LocalBackupArtifactStorage implements BackupArtifactStorage {

    private final BackupProperties properties;

    @Override
    public String storeEncryptedFile(Path encryptedFile, String objectKey) throws IOException {
        if (!StringUtils.hasText(properties.getLocalDir())) {
            throw new IllegalStateException("local-dir is blank");
        }
        Path root = Path.of(properties.getLocalDir());
        Path dest = root;
        for (String part : objectKey.split("/")) {
            if (!part.isEmpty()) {
                dest = dest.resolve(part);
            }
        }
        Files.createDirectories(dest.getParent());
        Files.copy(encryptedFile, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return objectKey;
    }

    @Override
    public void deleteObjectsOlderThan(Instant cutoff, String prefixFilter) {
        Path root = Path.of(properties.getLocalDir());
        if (StringUtils.hasText(prefixFilter)) {
            String p = prefixFilter.endsWith("/") ? prefixFilter.substring(0, prefixFilter.length() - 1) : prefixFilter;
            for (String part : p.split("/")) {
                if (!part.isEmpty()) {
                    root = root.resolve(part);
                }
            }
        }
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            if (Files.getLastModifiedTime(path).toInstant().isBefore(cutoff)) {
                                Files.deleteIfExists(path);
                                log.debug("Retention deleted {}", path);
                            }
                        } catch (IOException e) {
                            log.warn("Retention skip {}", path, e);
                        }
                    });
        } catch (IOException e) {
            log.warn("Retention walk failed under {}", root, e);
        }
    }
}
