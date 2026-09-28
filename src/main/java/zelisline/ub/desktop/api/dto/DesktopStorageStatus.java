package zelisline.ub.desktop.api.dto;

/**
 * Storage facts for the till's data folder, so an operator can see where the
 * data lives and whether the disk is filling up (-1 = unknown).
 */
public record DesktopStorageStatus(
        String appDataPath,
        long databaseBytes,
        long mediaBytes,
        long backupsBytes,
        long diskFreeBytes,
        long diskTotalBytes
) {}
