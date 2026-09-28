package zelisline.ub.desktop.api.dto;

/**
 * Tail of one of the till's log files ({@code bytes} = -1 when the file does
 * not exist yet). Bounded so the response stays small.
 */
public record DesktopLogFile(String name, long bytes, String tail) {}
