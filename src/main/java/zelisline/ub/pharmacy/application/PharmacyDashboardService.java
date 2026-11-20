package zelisline.ub.pharmacy.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.inventory.api.dto.BatchSafetyCountsResponse;
import zelisline.ub.inventory.application.BatchSafetyQueryService;
import zelisline.ub.pharmacy.api.dto.MedicineClassCountsResponse;
import zelisline.ub.pharmacy.api.dto.PharmacyDashboardResponse;
import zelisline.ub.pharmacy.api.dto.PharmacyDashboardResponse.ChecklistStep;
import zelisline.ub.pharmacy.api.dto.PharmacyProfileResponse;
import zelisline.ub.pharmacy.repository.DispensingRecordRepository;

/**
 * The pharmacy home screen: what needs attention today, and a set-up checklist that takes a new
 * chemist from "signed up" to "first dispensed sale". The checklist is separate from the general
 * setup-progress score on purpose, so it cannot change that score or its rewards.
 */
@Service
@RequiredArgsConstructor
public class PharmacyDashboardService {

    private static final ZoneId SHOP_ZONE = ZoneId.of("Africa/Nairobi");

    private final MedicineDetailsService medicineDetailsService;
    private final PharmacyProfileService pharmacyProfileService;
    private final BatchSafetyQueryService batchSafetyQueryService;
    private final DispensingRecordRepository dispensingRecordRepository;

    @Transactional(readOnly = true)
    public PharmacyDashboardResponse dashboard(String businessId, String branchId) {
        MedicineClassCountsResponse medicines = medicineDetailsService.counts(businessId);
        PharmacyProfileResponse profile = pharmacyProfileService.get(businessId);
        BatchSafetyCountsResponse stock =
                batchSafetyQueryService.counts(businessId, branchId, profile.shortDatedDays());
        LocalDate today = LocalDate.now(SHOP_ZONE);
        Instant from = today.atStartOfDay(SHOP_ZONE).toInstant();
        Instant to = today.plusDays(1).atStartOfDay(SHOP_ZONE).toInstant();
        String branch = branchId == null || branchId.isBlank() ? null : branchId.strip();
        long dispensedToday = dispensingRecordRepository.countSearch(businessId, branch, from, to);
        long everDispensed = dispensingRecordRepository.countSearch(
                businessId, null, Instant.EPOCH, to);

        Long awaitingSecondCheck = null;
        if (profile.secondCheckEnabled()) {
            Instant since = today.minusDays(30).atStartOfDay(SHOP_ZONE).toInstant();
            awaitingSecondCheck = branch == null
                    ? dispensingRecordRepository
                            .countByBusinessIdAndVerifiedAtIsNullAndVoidedAtIsNullAndCreatedAtGreaterThanEqual(
                                    businessId, since)
                    : dispensingRecordRepository
                            .countByBusinessIdAndBranchIdAndVerifiedAtIsNullAndVoidedAtIsNullAndCreatedAtGreaterThanEqual(
                                    businessId, branch, since);
        }

        int staff = profile.staff().size();
        int staffWithoutPin = (int) profile.staff().stream().filter(s -> !s.hasPin()).count();
        boolean licenceEntered = profile.premisesLicenceNo() != null && profile.licenceExpiresOn() != null;

        List<ChecklistStep> steps = new ArrayList<>();
        steps.add(new ChecklistStep("licence", "Enter your premises licence details", licenceEntered,
                "Recording them here does not replace the licence itself."));
        steps.add(new ChecklistStep("staff", "Add the people who may dispense", staff > 0,
                "Give your pharmacist the Pharmacist role and a till PIN."));
        steps.add(new ChecklistStep("medicines", "Mark your medicines", medicines.total() > 0,
                "Mark a whole department at once, then fix the exceptions."));
        steps.add(new ChecklistStep("classify", "Give every medicine a dispensing class",
                medicines.total() > 0 && medicines.unclassified() == 0,
                "A medicine with no class cannot be sold."));
        steps.add(new ChecklistStep("stock", "Record a lot number and expiry for medicine stock",
                medicines.total() > 0 && stock.medicineMissingDetails() == 0,
                "Stock loaded before the medicine was marked needs them added."));
        steps.add(new ChecklistStep("first_dispense", "Dispense your first prescription", everDispensed > 0,
                "Ring a prescription-only medicine at the till to see the dispensing step."));

        return new PharmacyDashboardResponse(
                medicines,
                stock,
                dispensedToday,
                profile.premisesLicenceNo(),
                profile.licenceExpiresOn(),
                profile.licenceDaysLeft(),
                profile.controlledSalesEnabled(),
                staff,
                staffWithoutPin,
                steps,
                awaitingSecondCheck);
    }
}
