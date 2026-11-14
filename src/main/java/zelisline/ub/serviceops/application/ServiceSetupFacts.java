package zelisline.ub.serviceops.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payroll.application.StaffProfileService;
import zelisline.ub.serviceops.api.dto.ServiceResponse;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.repository.BranchRepository;

/**
 * How far a service business has got with setting itself up, for the setup progress banner: is
 * there anything on the menu, is there anyone to do it, can customers book online. Read-only and
 * scoped to one business.
 */
@Service
@RequiredArgsConstructor
public class ServiceSetupFacts {

    private final ServiceCatalogService serviceCatalogService;
    private final StaffProfileService staffProfileService;
    private final BranchRepository branchRepository;

    public record Facts(
            /** Services that are switched on and have a price, so they can be rung and booked. */
            long pricedServices,
            /** People on the team who can be chosen for a service. */
            long team,
            /** At least one branch has online booking hours set. */
            boolean onlineBooking
    ) {
    }

    @Transactional(readOnly = true)
    public Facts facts(String businessId) {
        long priced = 0;
        for (ServiceResponse service : serviceCatalogService
                .listServices(businessId, null, Boolean.TRUE, null, 0, 200).getContent()) {
            if (service.price() != null && service.price().signum() > 0) {
                priced++;
            }
        }
        long team = staffProfileService.commissionPlans(businessId).stream()
                .filter(plan -> plan.current() && plan.bookable())
                .count();
        boolean online = false;
        for (Branch branch : branchRepository.findByBusinessIdAndDeletedAtIsNullOrderByNameAsc(businessId)) {
            if (branch.isActive() && branch.getBookingHours() != null && !branch.getBookingHours().isBlank()) {
                online = true;
                break;
            }
        }
        return new Facts(priced, team, online);
    }
}
