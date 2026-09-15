package zelisline.ub.storeroom.api;

import java.time.Instant;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.storeroom.api.dto.CreateStoreRoomMovementRequest;
import zelisline.ub.storeroom.api.dto.DecideStoreRoomMovementRequest;
import zelisline.ub.storeroom.api.dto.StoreRoomActivityResponse;
import zelisline.ub.storeroom.api.dto.StoreRoomMovementResponse;
import zelisline.ub.storeroom.application.StoreRoomMovementService;
import zelisline.ub.tenancy.api.TenantRequestIds;

/**
 * Take-outs and put-ins, the activity trail behind "what left the store room?", and
 * the approval decision for take-outs that are big enough to need one.
 *
 * <p>Every endpoint requires only catalogue read, because the store room page itself
 * does. The write permission is decided per request inside the service: a movement
 * that changes real stock needs {@code inventory.write}, a local register adjustment
 * accepts {@code catalog.items.write} or {@code inventory.write}, and a decision
 * needs {@code inventory.write}.
 */
@Validated
@RestController
@RequestMapping("/api/v1/store-room/movements")
@RequiredArgsConstructor
public class StoreRoomMovementsController {

    private final StoreRoomMovementService storeRoomMovementService;

    /**
     * @param from      window start (ISO-8601 instant); defaults to 24h before {@code to}
     * @param to        window end (ISO-8601 instant); defaults to now
     * @param limit     page cap for the returned list (the summary covers the window)
     * @param reason    filter to one store-room reason
     * @param createdBy filter to one actor's user id
     * @param direction {@code in} or {@code out}
     * @param status    {@code applied}, {@code pending}, or {@code rejected}
     */
    @GetMapping
    @PreAuthorize("hasPermission(null, 'catalog.items.read')")
    public StoreRoomActivityResponse list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) String createdBy,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String status,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return storeRoomMovementService.activity(
                TenantRequestIds.resolveBusinessId(request),
                new StoreRoomMovementService.ActivityQuery(
                        from, to, limit, reason, createdBy, direction, status));
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'catalog.items.read')")
    @ResponseStatus(HttpStatus.CREATED)
    public StoreRoomMovementResponse record(
            @Valid @RequestBody CreateStoreRoomMovementRequest body,
            HttpServletRequest request
    ) {
        // Humans only: this writes stock and must be attributable to a person.
        TenantPrincipal actor = CurrentTenantUser.requireHuman(request);
        return storeRoomMovementService.record(
                TenantRequestIds.resolveBusinessId(request),
                actor.userId(),
                actor.roleId(),
                actor.branchId(),
                body);
    }

    /** Applies a pending movement's stock effect. */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasPermission(null, 'catalog.items.read')")
    public StoreRoomMovementResponse approve(
            @PathVariable String id,
            @Valid @RequestBody DecideStoreRoomMovementRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal actor = CurrentTenantUser.requireHuman(request);
        return storeRoomMovementService.decide(
                TenantRequestIds.resolveBusinessId(request),
                id,
                actor.userId(),
                actor.roleId(),
                actor.branchId(),
                true,
                body.note());
    }

    /** Turns a pending movement down. Stock never moves. */
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasPermission(null, 'catalog.items.read')")
    public StoreRoomMovementResponse reject(
            @PathVariable String id,
            @Valid @RequestBody DecideStoreRoomMovementRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal actor = CurrentTenantUser.requireHuman(request);
        return storeRoomMovementService.decide(
                TenantRequestIds.resolveBusinessId(request),
                id,
                actor.userId(),
                actor.roleId(),
                actor.branchId(),
                false,
                body.note());
    }
}
