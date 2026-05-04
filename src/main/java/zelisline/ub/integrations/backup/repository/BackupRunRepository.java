package zelisline.ub.integrations.backup.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.integrations.backup.domain.BackupRun;

public interface BackupRunRepository extends JpaRepository<BackupRun, String> {}
