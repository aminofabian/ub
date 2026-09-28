package zelisline.ub.desktop.application;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import zelisline.ub.desktop.api.dto.DesktopLogFile;
import zelisline.ub.desktop.api.dto.DesktopStorageStatus;
import zelisline.ub.desktop.logs.DesktopLogFiles;

/**
 * Read-only diagnostics for Settings → Desktop: the data folder's size + free
 * disk, and bounded tails of the till's own log files (so support can be shown
 * the real error without a filesystem trip).
 */
@Service
@Profile("desktop")
public class DesktopDiagnosticsService {

    private static final Logger log = LoggerFactory.getLogger(DesktopDiagnosticsService.class);

    /** Cap per log tail so the response stays small. */
    private static final long MAX_TAIL_BYTES = 64 * 1024;

    @Value("${APP_DATA:${user.home}/.palmart}")
    private String appData;

    public DesktopStorageStatus storage() {
        Path root = Path.of(appData);
        long free = -1;
        long total = -1;
        try {
            FileStore fs = Files.getFileStore(root);
            free = fs.getUsableSpace();
            total = fs.getTotalSpace();
        } catch (IOException e) {
            log.warn("[Desktop] could not read disk space for {}: {}", root, e.getMessage());
        }
        return new DesktopStorageStatus(
            root.toAbsolutePath().toString(),
            dirSize(root.resolve("db")),
            dirSize(root.resolve("media")),
            dirSize(root.resolve("backups")),
            free,
            total
        );
    }

    public List<DesktopLogFile> logs() {
        Path root = Path.of(appData);
        List<DesktopLogFile> out = new ArrayList<>();
        for (String name : DesktopLogFiles.NAMES) {
            Path file = root.resolve(name);
            long bytes = -1;
            String tail = "";
            if (Files.isRegularFile(file)) {
                try {
                    bytes = Files.size(file);
                } catch (IOException ignored) {
                    // leave -1
                }
                tail = DesktopLogFiles.readTail(file, MAX_TAIL_BYTES);
            }
            out.add(new DesktopLogFile(name, bytes, tail));
        }
        return out;
    }

    private static long dirSize(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return 0L;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }
}
