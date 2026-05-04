package zelisline.ub.integrations.csvimport.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ImportJobPayloadStorage {

    private final Path baseDir;

    public ImportJobPayloadStorage(@Value("${app.integrations.import.jobs.storage-dir}") String storageDir) {
        this.baseDir = Path.of(storageDir).toAbsolutePath().normalize();
    }

    /** Writes CSV bytes atomically for a new job id; returns a relative path token stored on {@link zelisline.ub.integrations.csvimport.domain.ImportJob}. */
    public String persistPayload(String jobId, byte[] csvBytes) throws IOException {
        Files.createDirectories(baseDir);
        String relative = jobId + ".csv";
        Path dest = baseDir.resolve(relative);
        Files.write(dest, csvBytes, StandardOpenOption.CREATE_NEW);
        return relative;
    }

    public byte[] readPayload(String relativePath) throws IOException {
        return Files.readAllBytes(baseDir.resolve(relativePath));
    }

    public void deleteQuietly(String relativePath) {
        try {
            Files.deleteIfExists(baseDir.resolve(relativePath));
        } catch (IOException ignored) {
        }
    }
}
