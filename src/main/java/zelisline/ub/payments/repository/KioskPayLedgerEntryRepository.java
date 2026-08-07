package zelisline.ub.payments.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.payments.domain.KioskPayLedgerEntry;

public interface KioskPayLedgerEntryRepository extends JpaRepository<KioskPayLedgerEntry, String> {

    Optional<KioskPayLedgerEntry> findByReference(String reference);

    List<KioskPayLedgerEntry> findByBusinessIdOrderByCreatedAtDesc(String businessId, Pageable pageable);
}
