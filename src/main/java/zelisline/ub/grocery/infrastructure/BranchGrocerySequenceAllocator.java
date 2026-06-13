package zelisline.ub.grocery.infrastructure;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.grocery.domain.BranchGrocerySequence;
import zelisline.ub.grocery.repository.BranchGrocerySequenceRepository;

@Component
@RequiredArgsConstructor
public class BranchGrocerySequenceAllocator {

    private final BranchGrocerySequenceRepository repository;

    @Transactional
    public long allocateCounterNumber(String branchId) {
        BranchGrocerySequence seq = repository.findByBranchIdForUpdate(branchId)
                .orElseGet(() -> {
                    BranchGrocerySequence created = new BranchGrocerySequence();
                    created.setBranchId(branchId);
                    created.setNextCounter(1L);
                    return repository.save(created);
                });
        long counter = seq.getNextCounter();
        seq.setNextCounter(counter + 1L);
        repository.save(seq);
        return counter;
    }
}
