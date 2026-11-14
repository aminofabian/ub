package zelisline.ub.serviceops.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.serviceops.api.dto.HelperRoleDtos.HelperRoleResponse;
import zelisline.ub.serviceops.api.dto.HelperRoleDtos.SaveHelperRoleRequest;
import zelisline.ub.serviceops.domain.ServiceHelperRole;
import zelisline.ub.serviceops.repository.ServiceHelperRoleRepository;

/**
 * The usual helpers of a shop and what each is paid for a service (ADR 0016, amendment 4). The
 * owner sets them once; the till then names a person against a role and the pay comes from here.
 */
@Service
@RequiredArgsConstructor
public class ServiceHelperRoleService {

    static final int MAX_ROLES = 20;
    private static final BigDecimal MAX_PERCENT = new BigDecimal("99");

    private final ServiceHelperRoleRepository roleRepository;

    @Transactional(readOnly = true)
    public List<HelperRoleResponse> list(String businessId) {
        return roleRepository.findByBusinessIdOrderBySortOrderAscNameAsc(businessId).stream()
                .map(ServiceHelperRoleService::toResponse)
                .toList();
    }

    @Transactional
    public HelperRoleResponse create(String businessId, SaveHelperRoleRequest request) {
        List<ServiceHelperRole> rows = roleRepository.findByBusinessIdOrderBySortOrderAscNameAsc(businessId);
        if (rows.size() >= MAX_ROLES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A shop can have " + MAX_ROLES + " helper roles");
        }
        ServiceHelperRole row = new ServiceHelperRole();
        row.setBusinessId(businessId);
        row.setSortOrder(rows.stream().mapToInt(ServiceHelperRole::getSortOrder).max().orElse(0) + 1);
        apply(row, request);
        return toResponse(saveUnique(row));
    }

    @Transactional
    public HelperRoleResponse update(String businessId, String roleId, SaveHelperRoleRequest request) {
        ServiceHelperRole row = require(businessId, roleId);
        apply(row, request);
        return toResponse(saveUnique(row));
    }

    /** Visits and sales keep the pay that was worked out when the role was used. */
    @Transactional
    public void delete(String businessId, String roleId) {
        roleRepository.delete(require(businessId, roleId));
    }

    /** The role, only if it belongs to this business. */
    @Transactional(readOnly = true)
    public ServiceHelperRole require(String businessId, String roleId) {
        return roleRepository.findByIdAndBusinessId(roleId == null ? "" : roleId.trim(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Helper role not found"));
    }

    private static void apply(ServiceHelperRole row, SaveHelperRoleRequest request) {
        BigDecimal value = request.payValue();
        if (ServiceHelperRole.PAY_PERCENT.equals(request.payKind())) {
            if (value.compareTo(MAX_PERCENT) > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "A helper's share must leave something for the person leading the service");
            }
            value = value.setScale(2, RoundingMode.HALF_UP);
        } else {
            if (value.stripTrailingZeros().scale() > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the amount in whole shillings");
            }
            value = value.setScale(2, RoundingMode.UNNECESSARY);
        }
        row.setName(request.name().trim());
        row.setPayKind(request.payKind());
        row.setPayValue(value);
    }

    private ServiceHelperRole saveUnique(ServiceHelperRole row) {
        try {
            return roleRepository.saveAndFlush(row);
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "There is already a helper role called " + row.getName());
        }
    }

    private static HelperRoleResponse toResponse(ServiceHelperRole row) {
        return new HelperRoleResponse(row.getId(), row.getName(), row.getPayKind(), row.getPayValue());
    }
}
