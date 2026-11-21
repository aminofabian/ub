package zelisline.ub.clinic.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.clinic.api.dto.ClinicDtos.Hats;
import zelisline.ub.clinic.api.dto.ClinicDtos.SettingsRequest;
import zelisline.ub.clinic.api.dto.ClinicDtos.SettingsResponse;
import zelisline.ub.clinic.domain.ClinicSettings;
import zelisline.ub.clinic.repository.ClinicSettingsRepository;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.PermissionRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.payroll.domain.StaffProfile;
import zelisline.ub.payroll.repository.StaffProfileRepository;
import zelisline.ub.serviceops.application.ServiceCatalogService;

/**
 * Whether a business runs a clinic and how (ADR 0034). The settings row is what backend rules
 * key off: a shop format only picks defaults and wording.
 */
@Service
@RequiredArgsConstructor
public class ClinicSettingsService {

    public static final String P_RECEPTION = "clinic.reception";
    public static final String P_CONSULT = "clinic.consult";
    public static final String P_PRESCRIBE = "clinic.prescribe";
    public static final String P_PERFORM = "clinic.perform";
    public static final String P_MANAGE = "clinic.manage";
    public static final String P_DISPENSE = "pharmacy.dispense";
    public static final String P_RECORDS = "pharmacy.records.read";
    public static final String P_SELL = "sales.sell";

    private final ClinicSettingsRepository repository;
    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final ServiceCatalogService serviceCatalogService;
    private final zelisline.ub.serviceops.repository.ServiceOfferingRepository serviceOfferingRepository;
    private final zelisline.ub.serviceops.repository.ServiceResourceRepository resourceRepository;

    @Transactional(readOnly = true)
    public boolean enabled(String businessId) {
        return businessId != null && repository.existsByBusinessIdAndEnabledTrue(businessId);
    }

    /** The settings of a clinic that is switched on; 409 otherwise. */
    @Transactional(readOnly = true)
    public ClinicSettings require(String businessId) {
        return repository.findById(businessId)
                .filter(ClinicSettings::isEnabled)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "Clinic is not switched on for this business. Switch it on in Clinic, Settings."));
    }

    @Transactional(readOnly = true)
    public SettingsResponse get(String businessId, String userId, String roleId) {
        ClinicSettings s = repository.findById(businessId).orElse(null);
        return toResponse(s, hats(businessId, userId, roleId));
    }

    @Transactional
    public SettingsResponse save(String businessId, SettingsRequest req, String userId, String roleId) {
        ClinicSettings s = repository.findById(businessId).orElseGet(() -> {
            ClinicSettings created = new ClinicSettings();
            created.setBusinessId(businessId);
            return created;
        });
        s.setEnabled(Boolean.TRUE.equals(req.enabled()));
        if (req.feeFirst() != null) {
            s.setFeeFirst(req.feeFirst());
        }
        if (req.freeReviewDays() != null) {
            s.setFreeReviewDays(req.freeReviewDays());
        }
        if (req.secondPersonForGated() != null) {
            s.setSecondPersonForGated(req.secondPersonForGated());
        }
        // Every service named must be one of this business's; the catalogue 404s otherwise.
        s.setConsultationServiceId(ownService(businessId, req.consultationServiceId()));
        s.setReviewServiceId(ownService(businessId, req.reviewServiceId()));
        if (req.performServiceIds() != null) {
            Set<String> ids = new LinkedHashSet<>();
            for (String id : req.performServiceIds()) {
                String own = ownService(businessId, id);
                if (own != null) {
                    ids.add(own);
                }
            }
            s.setPerformServiceIds(ids.isEmpty() ? null : String.join(",", ids));
        }
        if (s.isEnabled() && s.getConsultationServiceId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Choose the service a visit opens with (your consultation) before switching Clinic on");
        }
        if (s.getFreeReviewDays() > 0 && s.getReviewServiceId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Choose the service used for a free review visit, or set free review days to 0");
        }
        repository.save(s);
        return toResponse(s, hats(businessId, userId, roleId));
    }

    private String ownService(String businessId, String serviceId) {
        if (serviceId == null || serviceId.isBlank()) {
            return null;
        }
        return serviceCatalogService.requireSellable(businessId, serviceId.strip()).getId();
    }

    /** What the caller may do in the clinic. A person can hold several hats. */
    @Transactional(readOnly = true)
    public Hats hats(String businessId, String userId, String roleId) {
        Set<String> keys = permissionKeys(businessId, userId, roleId);
        String staffProfileId = userId == null ? null
                : staffProfileRepository.findByBusinessIdAndUserId(businessId, userId)
                        .map(StaffProfile::getId).orElse(null);
        return new Hats(
                keys.contains(P_RECEPTION), keys.contains(P_CONSULT), keys.contains(P_PRESCRIBE),
                keys.contains(P_PERFORM), keys.contains(P_DISPENSE), keys.contains(P_SELL),
                keys.contains(P_MANAGE), staffProfileId);
    }

    @Transactional(readOnly = true)
    public Set<String> permissionKeys(String businessId, String userId, String roleId) {
        String role = roleId;
        if (role == null || role.isBlank()) {
            role = userId == null ? null
                    : userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(userId, businessId)
                            .map(User::getRoleId).orElse(null);
        }
        if (role == null || role.isBlank()) {
            return Set.of();
        }
        return new LinkedHashSet<>(permissionRepository.findPermissionKeysByRoleId(role));
    }

    /** The services a visit can carry. Readable by anyone working in the clinic. */
    @Transactional(readOnly = true)
    public List<zelisline.ub.clinic.api.dto.ClinicDtos.ClinicService> services(String businessId) {
        List<String> perform = performIds(repository.findById(businessId).orElse(null));
        return serviceOfferingRepository
                .findByBusinessIdAndActiveTrueAndDeletedAtIsNullOrderBySortOrderAscNameAsc(businessId).stream()
                .map(s -> new zelisline.ub.clinic.api.dto.ClinicDtos.ClinicService(
                        s.getId(), s.getName(), s.getPrice(), s.isPriceIsVariable(), s.getDurationMinutes(),
                        perform.contains(s.getId()), s.getReceiptName()))
                .toList();
    }

    /** Sets or clears what a service is called on the receipt. */
    @Transactional
    public List<zelisline.ub.clinic.api.dto.ClinicDtos.ClinicService> setReceiptName(
            String businessId, String serviceId, String receiptName) {
        var service = serviceOfferingRepository.findById(serviceId)
                .filter(s -> businessId.equals(s.getBusinessId()) && s.getDeletedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found"));
        service.setReceiptName(receiptName == null || receiptName.isBlank() ? null : receiptName.strip());
        serviceOfferingRepository.save(service);
        return services(businessId);
    }

    /** Rooms and beds at a branch. Restaurant tables and rooms let for the night are not treatment rooms. */
    @Transactional(readOnly = true)
    public List<zelisline.ub.clinic.api.dto.ClinicDtos.Room> rooms(String businessId, String branchId) {
        return resourceRepository.findByBusinessIdAndBranchIdOrderBySortOrderAscNameAsc(businessId, branchId).stream()
                .filter(r -> r.isActive()
                        && !zelisline.ub.serviceops.application.ServiceResourceService.TYPE_TABLE.equals(r.getResourceType())
                        && r.getRoomTypeId() == null && r.getNightlyRate() == null)
                .map(r -> new zelisline.ub.clinic.api.dto.ClinicDtos.Room(r.getId(), r.getName()))
                .toList();
    }

    public static List<String> performIds(ClinicSettings s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.getPerformServiceIds() == null) {
            return out;
        }
        for (String id : s.getPerformServiceIds().split(",")) {
            if (!id.isBlank()) {
                out.add(id.strip());
            }
        }
        return out;
    }

    private static SettingsResponse toResponse(ClinicSettings s, Hats hats) {
        if (s == null) {
            return new SettingsResponse(false, false, 0, null, null, List.of(), false, hats);
        }
        return new SettingsResponse(
                s.isEnabled(), s.isFeeFirst(), s.getFreeReviewDays(), s.getConsultationServiceId(),
                s.getReviewServiceId(), performIds(s), s.isSecondPersonForGated(), hats);
    }
}
