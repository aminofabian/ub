package zelisline.ub.desktop.backup;

import java.io.IOException;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

@RestController
@Profile("desktop")
@RequestMapping("/api/v1/desktop/backups")
@RequiredArgsConstructor
public class DesktopBackupController {

    private final DesktopBackupService backupService;

    @GetMapping
    public List<DesktopBackupService.BackupInfo> list() {
        try {
            return backupService.listBackups();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @PostMapping("/now")
    public DesktopBackupService.BackupInfo backupNow() {
        try {
            String filename = backupService.backupNow();
            return backupService.listBackups().stream()
                .filter(b -> b.filename().equals(filename))
                .findFirst()
                .orElseThrow();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @PostMapping("/restore/{filename}")
    public void restore(@PathVariable String filename) {
        try {
            backupService.restore(filename);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
