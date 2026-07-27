package zelisline.ub.marketplace.application;

import java.time.Instant;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.domain.PlatformSupplierNumberSeq;
import zelisline.ub.marketplace.repository.PlatformSupplierNumberSeqRepository;

@Component
@RequiredArgsConstructor
public class SupplierNumberAllocator {

    private final PlatformSupplierNumberSeqRepository repository;

    @Transactional
    public String allocateNext() {
        PlatformSupplierNumberSeq seq = repository.findByIdForUpdate(PlatformSupplierNumberSeq.SINGLETON_ID)
                .orElseGet(() -> {
                    PlatformSupplierNumberSeq created = new PlatformSupplierNumberSeq();
                    created.setId(PlatformSupplierNumberSeq.SINGLETON_ID);
                    created.setNextValue(1L);
                    return repository.saveAndFlush(created);
                });
        long value = seq.getNextValue();
        seq.setNextValue(value + 1L);
        seq.setUpdatedAt(Instant.now());
        repository.save(seq);
        return SupplierNumberFormat.format(value);
    }
}
