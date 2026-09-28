package zelisline.ub.desktop.logs;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * The log files the shell and backend write into {@code APP_DATA}, plus a
 * bounded tail reader. Shared by the support-upload reporter and the
 * Settings → Desktop diagnostics view so the two can never drift apart.
 */
public final class DesktopLogFiles {

    /** Log files the shell/backend write into APP_DATA, in display order. */
    public static final List<String> NAMES =
        List.of("kiosk.log", "backend.out.log", "backend.err.log", "mariadb.log");

    private DesktopLogFiles() {
    }

    /**
     * Last {@code maxBytes} of {@code path} as UTF-8, or {@code ""} when the file
     * is missing or unreadable. Reads only the tail — a multi-hundred-MB log does
     * not get loaded into memory.
     */
    public static String readTail(Path path, long maxBytes) {
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            long len = raf.length();
            long start = Math.max(0, len - Math.max(1, maxBytes));
            byte[] buf = new byte[(int) (len - start)];
            raf.seek(start);
            raf.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
