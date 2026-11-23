package zelisline.ub.desktop.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.desktop.api.dto.TradeActionSyncSnapshot;
import zelisline.ub.desktop.domain.DesktopTradeAction;
import zelisline.ub.desktop.repository.DesktopTradeActionRepository;

/**
 * Desktop till only. Pushes queued supplier-trade actions (answer, dispatch, cancel) to the online
 * shop, which applies each through its own service and returns an outcome. A refused action is kept
 * with its reason for the till to show; after a push the authoritative orders are re-pulled so the
 * optimistic status is replaced by the truth. See DESKTOP_SUPPLIER_TRADE_SYNC_SCOPE.md §S2.
 */
@Service
@Profile("desktop")
@RequiredArgsConstructor
public class DesktopTradeActionPushService {

    private static final Logger log = LoggerFactory.getLogger(DesktopTradeActionPushService.class);
    private static final int BATCH_MAX = 100;

    private final DesktopTradeActionRepository actionRepository;
    private final CloudSyncSession cloudSyncSession;
    private final DesktopSyncPullService syncPullService;
    private final RestClient.Builder restClientBuilder;

    @Value("${app.desktop.business-id:}")
    private String desktopBusinessId;

    /** @return number of actions the cloud confirmed (applied or refused) this run. */
    public int pushTradeActions() {
        String localId = desktopBusinessId == null ? "" : desktopBusinessId.trim();
        if (localId.isEmpty()) {
            return 0;
        }
        List<DesktopTradeAction> pending = actionRepository
            .findByPushedAtIsNullOrderByMadeAtAsc(PageRequest.of(0, BATCH_MAX));
        if (pending.isEmpty()) {
            return 0;
        }
        CloudSyncSession.Session mapping = cloudSyncSession.load().orElse(null);
        if (mapping == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This PC is not connected to an online shop yet");
        }
        RestClient client = restClientBuilder.baseUrl(mapping.origin()).build();

        List<TradeActionSyncSnapshot.Action> batch = pending.stream()
            .map(a -> new TradeActionSyncSnapshot.Action(
                a.getId(), a.getKind(), a.getTargetId(), a.getPayload(), a.getActorUserId()))
            .toList();
        TradeActionSyncSnapshot.Ack ack = postActions(client, mapping, new TradeActionSyncSnapshot(batch));

        Map<String, TradeActionSyncSnapshot.Outcome> byId = ack.outcomes() == null
            ? Map.of()
            : ack.outcomes().stream().collect(
                java.util.stream.Collectors.toMap(TradeActionSyncSnapshot.Outcome::id, o -> o, (a, b) -> a));

        int confirmed = 0;
        for (DesktopTradeAction action : pending) {
            TradeActionSyncSnapshot.Outcome outcome = byId.get(action.getId());
            action.setAttempts(action.getAttempts() + 1);
            if (outcome == null) {
                // The cloud did not report this one: leave it pending to retry next flush.
                actionRepository.save(action);
                continue;
            }
            action.setPushedAt(Instant.now());
            action.setOutcome(outcome.outcome());
            action.setOutcomeDetail(outcome.detail());
            actionRepository.save(action);
            confirmed++;
        }

        // Pull the authoritative orders so optimistic status is replaced by what the cloud actually did.
        try {
            syncPullService.pullTradeOrders();
        } catch (Exception e) {
            log.warn("[DesktopTrade] re-pull after action push failed: {}", e.getMessage());
        }
        log.info("[DesktopTrade] pushed {} trade action(s), {} confirmed", pending.size(), confirmed);
        return confirmed;
    }

    private TradeActionSyncSnapshot.Ack postActions(
            RestClient client, CloudSyncSession.Session session, TradeActionSyncSnapshot batch) {
        try {
            return doPostActions(client, session, batch);
        } catch (Exception e) {
            if (!isUnauthorized(e)) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Could not send your changes to the online shop (" + e.getMessage() + ")");
            }
            CloudSyncSession.Session refreshed = cloudSyncSession.refresh(client, session).orElse(null);
            if (refreshed == null) {
                throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Your online-shop session has expired -- open Settings -> Sync to reconnect");
            }
            try {
                return doPostActions(client, refreshed, batch);
            } catch (Exception e2) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Could not send your changes to the online shop (" + e2.getMessage() + ")");
            }
        }
    }

    private TradeActionSyncSnapshot.Ack doPostActions(
            RestClient client, CloudSyncSession.Session session, TradeActionSyncSnapshot batch) {
        TradeActionSyncSnapshot.Ack ack = client
            .post()
            .uri("/api/v1/desktop/sync/trade-actions")
            .header("Authorization", "Bearer " + session.accessToken())
            .header("X-Tenant-Id", session.cloudBusinessId())
            .contentType(MediaType.APPLICATION_JSON)
            .body(batch)
            .retrieve()
            .body(TradeActionSyncSnapshot.Ack.class);
        if (ack == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The online shop returned no outcome for your changes");
        }
        return ack;
    }

    private static boolean isUnauthorized(Exception e) {
        return e.getMessage() != null
            && (e.getMessage().contains("401") || e.getMessage().contains("Unauthorized"));
    }
}
