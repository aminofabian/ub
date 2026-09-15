package zelisline.ub.storeroom.api;

import java.time.Instant;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
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
import zelisline.ub.storeroom.api.dto.StoreRoomActivityResponse;
import zelisline.ub.storeroom.api.dto.StoreRoomMovementResponse;
import zelisline.ub.storeroom.application.StoreRoomMovementService;
import zelisline.ub.tenancy.api.TenantRequestIds;

/**
 * Take-outs and put-ins, plus the activity trail behind "what left the store room?".
 *
 * <p>Both endpoints require only catalogue read, because the store room page itself
 * does. The write permission is decided per request inside the service: a movement
 * that changes real stock needs {@code inventory.write}, a local register adjustment
 * accepts {@code catalog.items.write} or {@code inventory.write}.
 */
@Validated
@RestController
@RequestMapping("/api/v1/store-room/movements")
@RequiredArgsConstructor
public class StoreRoomMovementsController {

    private final StoreRoomMovementService storeRoomMovementService;

    /**
     * @param from  window start (ISO-8601 instant); defaults to 24h before {@code to}
     * @param to    window end (ISO-8601 instant); defaults to now
     * @param limit page cap
     */
    @GetMapping
    @PreAuthorize("hasPermission(null, 'catalog.items.read')")
    public StoreRoomActivityResponse list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return storeRoomMovementService.activity(
                TenantRequestIds.resolveBusinessId(request), from, to, limit);
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
}
