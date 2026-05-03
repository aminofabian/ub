package zelisline.ub.platform.media;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads keys from a local {@code .env} file when the JVM environment does not define them — Spring Boot does
 * not load dotenv files by itself.
 */
final class CloudinaryDotEnvSupport {

    private CloudinaryDotEnvSupport() {
    }

    static String readCloudinaryUrl() {
        return readFirstDefined("CLOUDINARY_URL");
    }

    static String readFirstDefined(String key) {
        for (Path file : dotEnvCandidates()) {
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                String v = valueForKey(Files.readAllLines(file, StandardCharsets.UTF_8), key);
                if (v != null && !v.isBlank()) {
                    return v.trim();
                }
            } catch (IOException ignored) {
                /* try next file */
            }
        }
        return null;
    }

    private static List<Path> dotEnvCandidates() {
        Set<Path> unique = new LinkedHashSet<>();
        String userDir = System.getProperty("user.dir", ".");
        Path root = Path.of(userDir).toAbsolutePath().normalize();
        unique.add(root.resolve(".env"));
        unique.add(root.resolve("backend").resolve(".env"));
        Path parent = root.getParent();
        if (parent != null) {
            unique.add(parent.resolve(".env"));
            unique.add(parent.resolve("backend").resolve(".env"));
        }
        return new ArrayList<>(unique);
    }

    static String valueForKey(List<String> lines, String key) {
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = line.substring(0, eq).trim();
            if (!key.equals(k)) {
                continue;
            }
            String val = line.substring(eq + 1).trim();
            if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'"))) {
                val = val.substring(1, val.length() - 1);
            }
            return val;
        }
        return null;
    }
}
