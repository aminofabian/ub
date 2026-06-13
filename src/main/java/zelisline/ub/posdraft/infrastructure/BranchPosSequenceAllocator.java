package zelisline.ub.posdraft.infrastructure;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.posdraft.domain.BranchPosSequence;
import zelisline.ub.posdraft.repository.BranchPosSequenceRepository;

@Component
@RequiredArgsConstructor
public class BranchPosSequenceAllocator {

    private final BranchPosSequenceRepository repository;

    @Transactional
    public long allocateTicketNumber(String branchId) {
        BranchPosSequence seq = repository.findByBranchIdForUpdate(branchId)
                .orElseGet(() -> {
                    BranchPosSequence created = new BranchPosSequence();
                    created.setBranchId(branchId);
                    created.setNextTicket(1L);
                    return repository.save(created);
                });
        long ticket = seq.getNextTicket();
        seq.setNextTicket(ticket + 1L);
        repository.save(seq);
        return ticket;
    }
}
