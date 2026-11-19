package zelisline.ub.serviceops.application;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.serviceops.application.ServiceResourceService.RoomRecord;
import zelisline.ub.serviceops.domain.RoomMaintenanceIssue;
import zelisline.ub.serviceops.repository.RoomMaintenanceIssueRepository;

/**
 * Maintenance issues on lodging rooms (ADR 0030 Phase 2). An issue is a persistent ticket — what
 * broke, who is on it, is it done — that outlives the room's housekeeping flag, which is wiped when
 * the room is turned CLEAN. Raising an issue moves the room to {@code MAINTENANCE}; closing the last
 * open issue is left to leave housekeeping alone (staff turn the room round themselves). Every call
 * is scoped by {@code businessId}; a room from another tenant or branch is a generic 404.
 */
@Service
@RequiredArgsConstructor
public class RoomMaintenanceService {

    private static final int MAX_DESCRIPTION = 500;
    private static final int MAX_URL = 1024;
    private static final List<String> OPEN_STATUSES =
            List.of(RoomMaintenanceIssue.STATUS_OPEN, RoomMaintenanceIssue.STATUS_IN_PROGRESS);

    private final RoomMaintenanceIssueRepository issueRepository;
    private final ServiceResourceService resourceService;

    /** An issue as the API returns it — the room's name resolved by the caller. */
    public record IssueRecord(
            String id, String roomResourceId, String roomName, String description, String status,
            String photoUrl, String assignedStaffProfileId, String openedBy, Instant openedAt, Instant closedAt) {
    }

    /** Raise an issue on a room. The room must belong to this business; it is moved to MAINTENANCE. */
    @Transactional
    public IssueRecord open(String businessId, String roomId, String description, String photoUrl,
            String assignedStaffProfileId, String openedBy) {
        RoomRecord room = requireRoom(businessId, roomId);
        String text = description == null ? "" : description.trim();
        if (text.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say what needs fixing");
        }
        RoomMaintenanceIssue issue = new RoomMaintenanceIssue();
        issue.setBusinessId(businessId);
        issue.setBranchId(room.branchId());
        issue.setRoomResourceId(room.id());
        issue.setDescription(cap(text, MAX_DESCRIPTION));
        issue.setStatus(RoomMaintenanceIssue.STATUS_OPEN);
        issue.setPhotoUrl(blankToNull(photoUrl, MAX_URL));
        issue.setAssignedStaffProfileId(blankToNull(assignedStaffProfileId, 36));
        issue.setOpenedBy(blankToNull(openedBy, 36));
        issueRepository.save(issue);
        // A raised issue takes the room out of service; staff put it back when the work is done.
        resourceService.setRoomHousekeeping(businessId, roomId, ServiceResourceService.HOUSEKEEPING_MAINTENANCE);
        return toRecord(issue, room.name());
    }

    /**
     * Update an issue: its status, assignee or photo. A null field is left as is. Marking it DONE
     * stamps the close time; reopening clears it. Does not touch the room's housekeeping — staff
     * decide when the room is fit to let again.
     */
    @Transactional
    public IssueRecord update(String businessId, String issueId, String status, String assignedStaffProfileId,
            String photoUrl, boolean clearAssignee, boolean clearPhoto) {
        RoomMaintenanceIssue issue = issueRepository.findByIdAndBusinessId(issueId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Issue not found"));
        if (status != null && !status.isBlank()) {
            String next = normalizeStatus(status);
            issue.setStatus(next);
            issue.setClosedAt(RoomMaintenanceIssue.STATUS_DONE.equals(next) ? Instant.now() : null);
        }
        if (clearAssignee) {
            issue.setAssignedStaffProfileId(null);
        } else if (assignedStaffProfileId != null && !assignedStaffProfileId.isBlank()) {
            issue.setAssignedStaffProfileId(assignedStaffProfileId.trim());
        }
        if (clearPhoto) {
            issue.setPhotoUrl(null);
        } else if (photoUrl != null && !photoUrl.isBlank()) {
            issue.setPhotoUrl(cap(photoUrl.trim(), MAX_URL));
        }
        issueRepository.save(issue);
        String roomName = resourceService.room(businessId, issue.getRoomResourceId())
                .map(RoomRecord::name).orElse(null);
        return toRecord(issue, roomName);
    }

    /** A branch's issues. {@code openOnly} limits to not-yet-done ones for the front-desk list. */
    @Transactional(readOnly = true)
    public List<IssueRecord> list(String businessId, String branchId, boolean openOnly) {
        List<RoomMaintenanceIssue> rows = openOnly
                ? issueRepository.findByBusinessIdAndBranchIdAndStatusInOrderByOpenedAtDesc(
                        businessId, branchId, OPEN_STATUSES)
                : issueRepository.findByBusinessIdAndBranchIdOrderByOpenedAtDesc(businessId, branchId);
        return rows.stream().map(row -> toRecord(row, roomName(businessId, row.getRoomResourceId()))).toList();
    }

    /** One room's full history, newest first. */
    @Transactional(readOnly = true)
    public List<IssueRecord> forRoom(String businessId, String roomId) {
        RoomRecord room = requireRoom(businessId, roomId);
        return issueRepository.findByBusinessIdAndRoomResourceIdOrderByOpenedAtDesc(businessId, roomId)
                .stream().map(row -> toRecord(row, room.name())).toList();
    }

    /** How many not-yet-done issues a room has — for the front-desk badge. */
    @Transactional(readOnly = true)
    public long openCount(String businessId, String roomId) {
        return issueRepository.countByBusinessIdAndRoomResourceIdAndStatusNot(
                businessId, roomId, RoomMaintenanceIssue.STATUS_DONE);
    }

    /**
     * Open-issue counts for every room at a branch, in one query — so a front desk of many rooms does
     * not run a count per room. A room with no open issues is simply absent from the map.
     */
    @Transactional(readOnly = true)
    public java.util.Map<String, Integer> openCountsByRoom(String businessId, String branchId) {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (RoomMaintenanceIssue issue : issueRepository
                .findByBusinessIdAndBranchIdAndStatusInOrderByOpenedAtDesc(businessId, branchId, OPEN_STATUSES)) {
            counts.merge(issue.getRoomResourceId(), 1, Integer::sum);
        }
        return counts;
    }

    // ------------------------------------------------------------------ helpers

    private RoomRecord requireRoom(String businessId, String roomId) {
        return resourceService.room(businessId, roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
    }

    private String roomName(String businessId, String roomId) {
        return resourceService.room(businessId, roomId).map(RoomRecord::name).orElse(null);
    }

    private static String normalizeStatus(String raw) {
        String s = raw.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (s) {
            case RoomMaintenanceIssue.STATUS_OPEN,
                    RoomMaintenanceIssue.STATUS_IN_PROGRESS,
                    RoomMaintenanceIssue.STATUS_DONE -> s;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status");
        };
    }

    private static IssueRecord toRecord(RoomMaintenanceIssue i, String roomName) {
        return new IssueRecord(i.getId(), i.getRoomResourceId(), roomName, i.getDescription(), i.getStatus(),
                i.getPhotoUrl(), i.getAssignedStaffProfileId(), i.getOpenedBy(), i.getOpenedAt(), i.getClosedAt());
    }

    private static String cap(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static String blankToNull(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return cap(value.trim(), max);
    }
}
