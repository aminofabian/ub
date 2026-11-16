package zelisline.ub.restaurant.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.realtime.RealtimeBridge;
import zelisline.ub.posdraft.application.OrderTicketService;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.KitchenLineResponse;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.KitchenPrintJobResponse;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.KitchenTicketResponse;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.UnprintedTicketResponse;
import zelisline.ub.restaurant.domain.KitchenPrintJob;
import zelisline.ub.restaurant.domain.KitchenStation;
import zelisline.ub.restaurant.domain.KitchenTicket;
import zelisline.ub.restaurant.domain.KitchenTicketLine;
import zelisline.ub.restaurant.repository.KitchenPrintJobRepository;
import zelisline.ub.restaurant.repository.KitchenStationRepository;
import zelisline.ub.restaurant.repository.KitchenTicketLineRepository;
import zelisline.ub.restaurant.repository.KitchenTicketRepository;

/**
 * The kitchen print queue. A kitchen ticket becomes a print job addressed to its branch and
 * station; any signed-in device at the branch claims it (once, by a compare-and-set), prints it on
 * the station's printer or its own, and marks it printed. A job still unprinted after a short while
 * is shown on the counter, and the counter can reprint a ticket. See {@link KitchenPrintJob}.
 */
@Service
@RequiredArgsConstructor
public class KitchenPrintService {

    /** A device only sees jobs this fresh; an older one has been dealt with on paper or given up on. */
    static final Duration PENDING_TTL = Duration.ofHours(6);
    /** A job younger than this is still expected to print; past it the counter warns. */
    static final Duration WARN_AFTER = Duration.ofSeconds(90);
    /** A claimed job that never confirmed printing is counted as unprinted again after this. */
    static final Duration CLAIM_GRACE = Duration.ofMinutes(2);

    private final KitchenPrintJobRepository jobRepository;
    private final KitchenTicketRepository ticketRepository;
    private final KitchenTicketLineRepository ticketLineRepository;
    private final KitchenStationRepository stationRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    /**
     * Enqueue a print job for a ticket that has just been written. Called inside the sale/send
     * transaction, so a job only exists if the ticket does. One original job per ticket: each sale
     * writes a fresh ticket with its own id and enqueues it once, and a retried sale rolls back the
     * first ticket, so the same ticket id is never enqueued twice on the normal path. The exists
     * check guards the few paths that could re-enqueue the same ticket id (it is best-effort, not a
     * DB constraint, because a reprint deliberately makes another job for the same ticket).
     */
    @Transactional
    public void enqueue(KitchenTicket ticket, List<KitchenTicketLine> lines) {
        if (jobRepository.existsByBusinessIdAndTicketId(ticket.getBusinessId(), ticket.getId())) {
            return;
        }
        KitchenPrintJob job = new KitchenPrintJob();
        job.setBusinessId(ticket.getBusinessId());
        job.setBranchId(ticket.getBranchId());
        job.setStationId(ticket.getStationId());
        job.setTicketId(ticket.getId());
        job.setOrderNumber(ticket.getOrderNumber());
        job.setTicketType(ticket.getTicketType());
        job.setPayloadJson(encode(snapshot(ticket, lines)));
        jobRepository.save(job);
    }

    /** The jobs a device should print now: its station's (and the implicit kitchen's), oldest first. */
    @Transactional(readOnly = true)
    public List<KitchenPrintJobResponse> pending(String businessId, String branchId, String stationId) {
        String station = stationId == null || stationId.isBlank() ? null : stationId;
        return jobRepository.findPending(businessId, branchId, station, Instant.now().minus(PENDING_TTL)).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Claim one job for this device. Exactly one caller wins; the rest get 404 and leave it alone.
     * The won job is returned with the ticket to print and the station's printer, if it named one.
     */
    @Transactional
    public KitchenPrintJobResponse claim(String businessId, String userId, String jobId) {
        int won = jobRepository.claimIfOpen(jobId, businessId, userId, Instant.now(), Instant.now().minus(PENDING_TTL));
        if (won != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That ticket was already taken");
        }
        return toResponse(require(businessId, jobId));
    }

    /** A device confirms a job's ticket printed. */
    @Transactional
    public void markPrinted(String businessId, String jobId) {
        KitchenPrintJob job = require(businessId, jobId);
        if (job.getPrintedAt() == null) {
            job.setPrintedAt(Instant.now());
            jobRepository.save(job);
        }
    }

    /** Reprint a ticket from the counter: a fresh job for the same ticket, marked a reprint. */
    @Transactional
    public KitchenPrintJobResponse reprint(String businessId, String ticketId) {
        KitchenTicket ticket = ticketRepository.findByIdAndBusinessId(ticketId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kitchen ticket"));
        List<KitchenTicketLine> lines =
                ticketLineRepository.findByBusinessIdAndTicketIdInOrderByLineIndexAsc(businessId, List.of(ticket.getId()));
        KitchenPrintJob job = new KitchenPrintJob();
        job.setBusinessId(ticket.getBusinessId());
        job.setBranchId(ticket.getBranchId());
        job.setStationId(ticket.getStationId());
        job.setTicketId(ticket.getId());
        job.setOrderNumber(ticket.getOrderNumber());
        job.setTicketType(ticket.getTicketType());
        job.setReprintOf(ticket.getId());
        job.setPayloadJson(encode(snapshot(ticket, lines)));
        job = jobRepository.save(job);
        eventPublisher.publishEvent(new RealtimeBridge.KitchenUpdatedEvent(businessId, ticket.getBranchId()));
        return toResponse(job);
    }

    /** Tickets the counter should warn have not printed: old enough, not printed, not freshly claimed. */
    @Transactional(readOnly = true)
    public List<UnprintedTicketResponse> unprinted(String businessId, String branchId) {
        Instant now = Instant.now();
        return jobRepository.findUnprinted(businessId, branchId,
                now.minus(PENDING_TTL), now.minus(WARN_AFTER), now.minus(CLAIM_GRACE)).stream()
                .map(j -> new UnprintedTicketResponse(j.getId(), j.getTicketId(), j.getOrderNumber(),
                        j.getStationId(), j.getTicketType(), j.getCreatedAt()))
                .toList();
    }

    // ------------------------------------------------------------------ internals

    private KitchenTicketResponse snapshot(KitchenTicket ticket, List<KitchenTicketLine> lines) {
        boolean isVoid = RestaurantSaleSupport.TICKET_VOID.equals(ticket.getTicketType());
        String stage = isVoid ? OrderTicketService.STAGE_VOIDED : OrderTicketService.STAGE_NEW;
        List<KitchenLineResponse> out = lines.stream()
                .map(line -> new KitchenLineResponse(line.getId(), line.getOrderLineId(), line.getName(),
                        line.getQuantity(), ModifierSnapshots.decode(line.getModifiers()), line.getNote(), stage, null))
                .toList();
        return new KitchenTicketResponse(ticket.getId(), ticket.getOrderId(), ticket.getOrderNumber(),
                ticket.getStationId(), ticket.getTicketType(), ticket.getStatus(), ticket.getRoundNo(),
                ticket.getSentAt(), ticket.getReadyAt(), out);
    }

    private KitchenPrintJobResponse toResponse(KitchenPrintJob job) {
        KitchenStation station = job.getStationId() == null ? null
                : stationRepository.findByIdAndBusinessId(job.getStationId(), job.getBusinessId()).orElse(null);
        return new KitchenPrintJobResponse(
                job.getId(), job.getTicketId(), job.getOrderNumber(), job.getStationId(),
                station == null ? null : station.getName(),
                station == null ? null : station.getPrinterName(),
                station == null ? null : station.getPrinterHost(),
                station == null ? null : station.getPrinterPort(),
                job.getReprintOf() != null, decode(job.getPayloadJson()));
    }

    private KitchenPrintJob require(String businessId, String jobId) {
        return jobRepository.findByIdAndBusinessId(jobId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Print job"));
    }

    private String encode(KitchenTicketResponse ticket) {
        try {
            return objectMapper.writeValueAsString(ticket);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not build the kitchen ticket");
        }
    }

    private KitchenTicketResponse decode(String json) {
        try {
            return objectMapper.readValue(json, KitchenTicketResponse.class);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not read the kitchen ticket");
        }
    }
}
