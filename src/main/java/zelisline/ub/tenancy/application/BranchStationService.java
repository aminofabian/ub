package zelisline.ub.tenancy.application;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.repository.BranchRepository;

/** How many chairs (stations, rooms) a branch of a service business has. */
@Service
@RequiredArgsConstructor
public class BranchStationService {

    public static final int MAX_STATIONS = 50;

    private final BranchRepository branchRepository;

    @Transactional(readOnly = true)
    public int stationCount(String businessId, String branchId) {
        return requireBranch(businessId, branchId).getServiceStationCount();
    }

    @Transactional
    public int setStationCount(String businessId, String branchId, int count) {
        if (count < 0 || count > MAX_STATIONS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Enter a number of chairs from 0 to " + MAX_STATIONS);
        }
        Branch branch = requireBranch(businessId, branchId);
        branch.setServiceStationCount(count);
        branchRepository.save(branch);
        return count;
    }

    private Branch requireBranch(String businessId, String branchId) {
        if (branchId == null || branchId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a branch");
        }
        return branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(branchId.trim(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found"));
    }
}
