package zelisline.ub.clinic.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.clinic.api.dto.ClinicDtos.Appointment;
import zelisline.ub.clinic.api.dto.ClinicDtos.BoardResponse;
import zelisline.ub.clinic.api.dto.ClinicDtos.BoardVisit;
import zelisline.ub.clinic.api.dto.ClinicDtos.BookRequest;
import zelisline.ub.clinic.api.dto.ClinicDtos.CancelVisitRequest;
import zelisline.ub.clinic.api.dto.ClinicDtos.DepositRequest;
import zelisline.ub.clinic.api.dto.ClinicDtos.Hats;
import zelisline.ub.clinic.api.dto.ClinicDtos.LineRequest;
import zelisline.ub.clinic.api.dto.ClinicDtos.NewPatientRequest;
import zelisline.ub.clinic.api.dto.ClinicDtos.OpenVisitRequest;
import zelisline.ub.clinic.api.dto.ClinicDtos.PaidVisitResponse;
import zelisline.ub.clinic.api.dto.ClinicDtos.PayVisitRequest;
import zelisline.ub.clinic.api.dto.ClinicDtos.PrepareLine;
import zelisline.ub.clinic.api.dto.ClinicDtos.PrepareRequest;
import zelisline.ub.clinic.api.dto.ClinicDtos.PrescriptionDto;
import zelisline.ub.clinic.api.dto.ClinicDtos.PrescriptionLineDto;
import zelisline.ub.clinic.api.dto.ClinicDtos.PrescriptionLineInput;
import zelisline.ub.clinic.api.dto.ClinicDtos.ReceptionPatient;
import zelisline.ub.clinic.api.dto.ClinicDtos.SavePrescriptionRequest;
import zelisline.ub.clinic.api.dto.ClinicDtos.VisitClaimRequest;
import zelisline.ub.clinic.api.dto.ClinicDtos.VisitPatient;
import zelisline.ub.clinic.api.dto.ClinicDtos.VisitProduct;
import zelisline.ub.clinic.api.dto.ClinicDtos.VisitResponse;
import zelisline.ub.clinic.domain.ClinicSettings;
import zelisline.ub.clinic.domain.Prescription;
import zelisline.ub.clinic.domain.PrescriptionLine;
import zelisline.ub.clinic.repository.PrescriptionLineRepository;
import zelisline.ub.clinic.repository.PrescriptionRepository;
import zelisline.ub.credits.api.dto.CreateCustomerRequest;
import zelisline.ub.credits.api.dto.CustomerPhoneDraft;
import zelisline.ub.credits.application.CustomerDirectoryService;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.payroll.application.StaffProfileService;
import zelisline.ub.payroll.domain.StaffProfile;
import zelisline.ub.payroll.repository.StaffProfileRepository;
import zelisline.ub.pharmacy.api.dto.PharmacyNetworkDtos.ClaimResponse;
import zelisline.ub.pharmacy.api.dto.PharmacyStandardDtos.PatientRequest;
import zelisline.ub.pharmacy.application.DispensingGate;
import zelisline.ub.pharmacy.application.PharmacyPatientService;
import zelisline.ub.pharmacy.application.SchemeClaimService;
import zelisline.ub.pharmacy.domain.DispensingRecord;
import zelisline.ub.pharmacy.domain.PharmacyPatient;
import zelisline.ub.pharmacy.domain.PharmacyStaffCredential;
import zelisline.ub.pharmacy.repository.DispensingRecordRepository;
import zelisline.ub.pharmacy.repository.PharmacyPatientRepository;
import zelisline.ub.pharmacy.repository.PharmacyStaffCredentialRepository;
import zelisline.ub.posdraft.PosDraftConstants;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.CancelTicketRequest;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.CreateAppointmentRequest;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.CreateServiceTicketRequest;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.LineAssignmentRequest;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.PaidTicketResponse;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.PayTicketRequest;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.TakeDepositRequest;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.TicketLineInput;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.TicketLineResponse;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.TicketProductResponse;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.TicketResponse;
import zelisline.ub.posdraft.application.ServiceTicketService;
import zelisline.ub.posdraft.application.TicketRepricedException;
import zelisline.ub.posdraft.domain.PosDraft;
import zelisline.ub.posdraft.domain.PosDraftLine;
import zelisline.ub.posdraft.repository.PosDraftLineRepository;
import zelisline.ub.posdraft.repository.PosDraftRepository;
import zelisline.ub.sales.api.dto.PostSaleDispensingRequest;

/**
 * A clinic visit (ADR 0034): an ordinary held service ticket with a patient on it. Everything a
 * ticket already does (queue, rooms, who performed what, deposit, payment) is done by
 * {@link ServiceTicketService}; this adds the patient, the prescription, what a visit shows to
 * whom, and the dispensing details at payment.
 *
 * <p>The dispensing gate is not touched: a visit carrying a prescription-only medicine is paid
 * through the same sale path as the till, with the same pharmacist rules.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClinicVisitService {

    private static final ZoneId SHOP_ZONE = ZoneId.of("Africa/Nairobi");
    /** Queue priority for a visit marked urgent; ordinary visits are 0. */
    private static final int URGENT_PRIORITY = 5;
    static final String GIVEN_HERE_INSTRUCTIONS = "Given in the clinic";

    private final ClinicSettingsService settingsService;
    private final ServiceTicketService ticketService;
    private final PosDraftRepository draftRepository;
    private final PosDraftLineRepository draftLineRepository;
    private final PharmacyPatientRepository patientRepository;
    private final PharmacyPatientService patientService;
    private final PrescriptionRepository prescriptionRepository;
    private final PrescriptionLineRepository prescriptionLineRepository;
    private final DispensingGate dispensingGate;
    private final DispensingRecordRepository dispensingRecordRepository;
    private final PharmacyStaffCredentialRepository credentialRepository;
    private final UserRepository userRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final StaffProfileService staffProfileService;
    private final CustomerDirectoryService customerDirectoryService;
    private final SchemeClaimService schemeClaimService;
    private final zelisline.ub.catalog.repository.ItemRepository itemRepository;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;
    private final zelisline.ub.pharmacy.application.DispensingApprovalService dispensingApprovalService;
    private final zelisline.ub.sales.repository.SaleRepository saleRepository;

    /** A preparation older than this no longer stands in for the pharmacist at the cash desk. */
    private static final java.time.Duration PREPARATION_GOOD_FOR = java.time.Duration.ofHours(12);

    /**
     * Whether a pharmacist's preparation covers the bill (the owner's decision of 2026-11-21:
     * a prepared prescription counts as the pharmacist's approval).
     *
     * <p>It does only when: the prescription is PREPARED, recently, by a named person; where the
     * clinic asks for a second person, that person is not the prescriber; and every medicine on
     * the bill that needs a dispensing record is on the prescription in no greater quantity than
     * was prescribed. One medicine outside that (say, an injection given here that was never
     * prescribed) and the whole bill goes back to needing a pharmacist at payment.
     *
     * @return the medicines and quantities the preparation covers, or null when it does not
     */
    private Map<String, BigDecimal> coveredByPreparation(
            String businessId, ClinicSettings settings, Prescription rx, List<PrescriptionLine> rxLines,
            List<PosDraftLine> productLines) {
        if (rx == null || !Prescription.PREPARED.equals(rx.getStatus())
                || rx.getPreparedBy() == null || rx.getPreparedAt() == null
                || rx.getPreparedAt().isBefore(Instant.now().minus(PREPARATION_GOOD_FOR))) {
            return null;
        }
        if (settings.isSecondPersonForGated() && rx.getPreparedBy().equals(rx.getPrescriberUserId())) {
            return null;
        }
        Map<String, BigDecimal> onBill = new LinkedHashMap<>();
        for (PosDraftLine line : productLines) {
            onBill.merge(line.getItemId(), line.getQuantity(), BigDecimal::add);
        }
        Map<String, BigDecimal> prescribed = new LinkedHashMap<>();
        for (PrescriptionLine l : rxLines) {
            if (l.getItemId() != null) {
                prescribed.merge(l.getItemId(), l.getQuantity(), BigDecimal::add);
            }
        }
        Map<String, BigDecimal> covered = new LinkedHashMap<>();
        for (DispensingGate.ItemStanding s : dispensingGate.standings(businessId, onBill.keySet())) {
            if (!DispensingGate.ItemStanding.NEEDS_RECORD.equals(s.status())) {
                continue;
            }
            BigDecimal allowed = prescribed.get(s.itemId());
            BigDecimal sold = onBill.get(s.itemId());
            if (allowed == null || sold == null || sold.compareTo(allowed) > 0) {
                return null;
            }
            covered.put(s.itemId(), sold);
        }
        return covered.isEmpty() ? null : covered;
    }

    /** Who is asking, resolved once per request. */
    public record Caller(String userId, String roleId, Hats hats, boolean mayReadRecords) {
        boolean clinical() {
            return hats.consult() || hats.prescribe() || hats.perform() || hats.dispense();
        }

        boolean mayReadPrescription() {
            return hats.prescribe() || hats.dispense() || mayReadRecords;
        }
    }

    @Transactional(readOnly = true)
    public Caller caller(String businessId, String userId, String roleId) {
        Set<String> keys = settingsService.permissionKeys(businessId, userId, roleId);
        return new Caller(userId, roleId, settingsService.hats(businessId, userId, roleId),
                keys.contains(ClinicSettingsService.P_RECORDS));
    }

    /** The people who serve patients: whoever is on the service team. */
    @Transactional(readOnly = true)
    public List<zelisline.ub.clinic.api.dto.ClinicDtos.Clinician> clinicians(String businessId) {
        Set<String> ids = staffProfileService.floorStatuses(businessId).keySet();
        Map<String, String> names = staffProfileService.displayNamesByIds(businessId, ids);
        return ids.stream()
                .map(id -> new zelisline.ub.clinic.api.dto.ClinicDtos.Clinician(id, names.getOrDefault(id, "Staff")))
                .sorted(java.util.Comparator.comparing(zelisline.ub.clinic.api.dto.ClinicDtos.Clinician::name))
                .toList();
    }

    // ── Patients (reception) ──────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ReceptionPatient> searchPatients(String businessId, String q, String userId) {
        settingsService.require(businessId);
        return patientService.searchForReception(businessId, q, userId).stream()
                .map(m -> new ReceptionPatient(m.id(), m.patientNo(), m.name(), m.phone(), m.ageYears()))
                .toList();
    }

    /** Not transactional: the patient number is retried in its own transactions. */
    public ReceptionPatient addPatient(String businessId, NewPatientRequest req, String userId) {
        settingsService.require(businessId);
        var saved = patientService.create(
                businessId, new PatientRequest(req.name(), req.phone(), req.dateOfBirth(), null, null, false), userId);
        return new ReceptionPatient(saved.id(), saved.patientNo(), saved.name(), saved.phone(),
                PharmacyPatientService.ageYears(saved.dateOfBirth(), LocalDate.now(SHOP_ZONE)));
    }

    // ── Opening ───────────────────────────────────────────────────────

    @Transactional
    public VisitResponse open(String businessId, OpenVisitRequest req, Caller caller) {
        ClinicSettings settings = settingsService.require(businessId);
        PharmacyPatient patient = requirePatient(businessId, req.patientId());
        boolean review = Boolean.TRUE.equals(req.review());
        String reviewOfSaleId = null;
        List<String> serviceIds = new ArrayList<>();
        if (review) {
            reviewOfSaleId = reviewableSaleId(businessId, patient.getId(), settings);
            serviceIds.add(settings.getReviewServiceId());
        } else if (req.serviceIds() != null && !req.serviceIds().isEmpty()) {
            serviceIds.addAll(new LinkedHashSet<>(req.serviceIds()));
        } else {
            serviceIds.add(settings.getConsultationServiceId());
        }
        List<TicketLineInput> lines = serviceIds.stream().map(id -> new TicketLineInput(id, null)).toList();
        String payer = blankToNull(req.payerCustomerId()) != null ? req.payerCustomerId().strip() : patient.getCustomerId();
        TicketResponse ticket = ticketService.create(businessId, new CreateServiceTicketRequest(
                req.branchId(), req.clientVisitId(), payer, blankToNull(req.requestedStaffProfileId()), lines),
                caller.userId());
        PosDraft draft = draftRepository.findByIdAndBusinessIdAndDraftKind(
                        ticket.id(), businessId, PosDraftConstants.KIND_SERVICE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Could not open the visit; try again"));
        if (draft.getPatientId() != null && !draft.getPatientId().equals(patient.getId())) {
            // The same client id sent again for someone else.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That visit already belongs to another patient");
        }
        if (draft.getPatientId() == null) {
            draft.setPatientId(patient.getId());
            draft.setReviewOfSaleId(reviewOfSaleId);
            if (Boolean.TRUE.equals(req.urgent())) {
                draft.setQueuePriority(URGENT_PRIORITY);
            }
            draftRepository.saveAndFlush(draft);
            audit(businessId, caller.userId(), AuditEventTypes.CLINIC_VISIT_OPENED, "clinic_visit", draft.getId(), 1);
        }
        return view(businessId, draft.getId(), caller, false);
    }

    private String reviewableSaleId(String businessId, String patientId, ClinicSettings settings) {
        if (settings.getFreeReviewDays() <= 0 || settings.getReviewServiceId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Review visits are not switched on in Clinic, Settings");
        }
        Instant earliest = Instant.now().minus(settings.getFreeReviewDays(), ChronoUnit.DAYS);
        for (PosDraft earlier : draftRepository.findTop20ByBusinessIdAndPatientIdAndStatusOrderByCompletedAtDesc(
                businessId, patientId, PosDraftConstants.STATUS_COMPLETED)) {
            if (earlier.getSaleId() == null || earlier.getReviewOfSaleId() != null) {
                continue;
            }
            if (earlier.getCompletedAt() != null && !earlier.getCompletedAt().isBefore(earliest)) {
                return earlier.getSaleId();
            }
            break;
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This patient has no paid visit in the last " + settings.getFreeReviewDays()
                        + " days to review. Open an ordinary visit.");
    }

    // ── Reading ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public VisitResponse get(String businessId, String visitId, Caller caller) {
        return view(businessId, visitId, caller, true);
    }

    /** The Today list at a branch: a number and a first name per visit, and where each one is. */
    @Transactional(readOnly = true)
    public BoardResponse board(String businessId, String branchId, Caller caller) {
        ClinicSettings settings = settingsService.require(businessId);
        List<String> performIds = ClinicSettingsService.performIds(settings);
        List<TicketResponse> tickets = ticketService.board(businessId, branchId).tickets();
        Map<String, PosDraft> drafts = new LinkedHashMap<>();
        if (!tickets.isEmpty()) {
            for (PosDraft d : draftRepository.findByBusinessIdAndIdInAndDraftKind(
                    businessId, tickets.stream().map(TicketResponse::id).toList(), PosDraftConstants.KIND_SERVICE)) {
                if (d.getPatientId() != null) {
                    drafts.put(d.getId(), d);
                }
            }
        }
        Map<String, PharmacyPatient> patients = patientsById(businessId,
                drafts.values().stream().map(PosDraft::getPatientId).toList());
        Map<String, Prescription> prescriptions = new LinkedHashMap<>();
        if (!drafts.isEmpty()) {
            for (Prescription p : prescriptionRepository.findByBusinessIdAndVisitIdIn(businessId, drafts.keySet())) {
                Prescription seen = prescriptions.get(p.getVisitId());
                if (seen == null || p.getCreatedAt().isAfter(seen.getCreatedAt())) {
                    prescriptions.put(p.getVisitId(), p);
                }
            }
        }
        Set<String> stocked = stockedPrescriptionIds(businessId, prescriptions.values());
        List<BoardVisit> out = new ArrayList<>();
        for (TicketResponse t : tickets) {
            PosDraft d = drafts.get(t.id());
            if (d == null) {
                continue;
            }
            Prescription rx = prescriptions.get(t.id());
            String station = VisitStations.of(
                    t.status(), t.stage(), t.lines(), performIds,
                    rx == null ? null : rx.getStatus(), rx != null && stocked.contains(rx.getId()));
            TicketLineResponse active = t.lines().stream()
                    .filter(l -> PosDraftConstants.STAGE_IN_SERVICE.equals(l.stage()))
                    .findFirst().orElse(null);
            int done = (int) t.lines().stream().filter(l -> PosDraftConstants.LINE_DONE.equals(l.stage())).count();
            PharmacyPatient patient = patients.get(d.getPatientId());
            boolean pay = VisitStations.PAY.equals(station);
            out.add(new BoardVisit(
                    t.id(), t.ticketNumber(), VisitStations.firstName(patient == null ? null : patient.getName()),
                    station, t.priority(), t.createdAt(), VisitStations.since(station, t.createdAt(), t.lines()),
                    active != null ? active.performerStaffProfileId() : t.staffProfileId(),
                    active != null ? active.performerName() : t.staffName(),
                    active == null ? null : active.resourceName(),
                    done, t.lines().size(),
                    // What is owed is shown once the visit is ready to pay, and only to someone who takes money.
                    pay && caller.hats().cashier() ? t.total() : null,
                    t.depositAmount(), t.depositState(),
                    rx == null ? null : rx.getStatus(), d.getReviewOfSaleId() != null));
        }
        Instant startOfDay = LocalDate.now(SHOP_ZONE).atStartOfDay(SHOP_ZONE).toInstant();
        int finished = (int) draftRepository.countByBusinessIdAndBranchIdAndStatusAndPatientIdIsNotNullAndCompletedAtGreaterThanEqual(
                businessId, branchId.strip(), PosDraftConstants.STATUS_COMPLETED, startOfDay);
        audit(businessId, caller.userId(), AuditEventTypes.CLINIC_VISIT_VIEWED, null, null, out.size());
        return new BoardResponse(branchId.strip(), out, finished);
    }

    // ── Working a visit ───────────────────────────────────────────────

    @Transactional
    public VisitResponse addService(String businessId, String visitId, String serviceId, Caller caller) {
        PosDraft draft = requireOpenVisit(businessId, visitId);
        Set<String> before = lineIds(ticketService.get(businessId, draft.getId()));
        TicketResponse after = ticketService.addLine(businessId, draft.getId(), new TicketLineInput(serviceId.strip(), null));
        // Whoever adds a service while working the visit is, by default, the one doing it.
        String mine = caller.hats().staffProfileId();
        if (mine != null && (caller.hats().consult() || caller.hats().perform())) {
            for (TicketLineResponse line : after.lines()) {
                if (!before.contains(line.id()) && line.performerStaffProfileId() == null) {
                    try {
                        ticketService.assignLine(businessId, draft.getId(), line.id(), new LineAssignmentRequest(mine, null));
                    } catch (ResponseStatusException notTheirs) {
                        // They do not perform this service; someone else is chosen when it starts.
                    }
                }
            }
        }
        return view(businessId, draft.getId(), caller, false);
    }

    @Transactional
    public VisitResponse removeService(String businessId, String visitId, String lineId, Caller caller) {
        PosDraft draft = requireOpenVisit(businessId, visitId);
        ticketService.removeLine(businessId, draft.getId(), lineId);
        return view(businessId, draft.getId(), caller, false);
    }

    @Transactional
    public VisitResponse startLine(String businessId, String visitId, String lineId, LineRequest req, Caller caller) {
        PosDraft draft = requireOpenVisit(businessId, visitId);
        ticketService.startLine(businessId, draft.getId(), lineId, assignment(req, caller));
        return view(businessId, draft.getId(), caller, false);
    }

    /**
     * Marks one service done. A line nobody started is started and finished in one step, by the
     * person named or the caller: the one-person clinic does not press Start.
     */
    @Transactional
    public VisitResponse finishLine(String businessId, String visitId, String lineId, LineRequest req, Caller caller) {
        PosDraft draft = requireOpenVisit(businessId, visitId);
        TicketLineResponse line = ticketService.get(businessId, draft.getId()).lines().stream()
                .filter(l -> l.id().equals(lineId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found on this visit"));
        if (PosDraftConstants.STAGE_WAITING.equals(line.stage())) {
            ticketService.startLine(businessId, draft.getId(), lineId, assignment(req, caller));
        }
        ticketService.finishLine(businessId, draft.getId(), lineId);
        return view(businessId, draft.getId(), caller, false);
    }

    private static LineAssignmentRequest assignment(LineRequest req, Caller caller) {
        String performer = req != null && req.performerStaffProfileId() != null && !req.performerStaffProfileId().isBlank()
                ? req.performerStaffProfileId().strip()
                : caller.hats().staffProfileId();
        if (performer == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Choose who is doing this. You are not set up as a member of the service team.");
        }
        return new LineAssignmentRequest(performer, req == null ? null : req.resourceId());
    }

    @Transactional
    public VisitResponse addProduct(
            String businessId, String visitId, String itemId, BigDecimal quantity, boolean givenHere, Caller caller) {
        PosDraft draft = requireOpenVisit(businessId, visitId);
        String item = itemId.strip();
        ticketService.addProduct(businessId, draft.getId(), new ServiceTicketDtos.AddProductRequest(item, quantity));
        if (givenHere) {
            for (PosDraftLine line : draftLineRepository.findByDraftIdOrderByLineIndexAsc(draft.getId())) {
                if (!line.isDeleted() && item.equals(line.getItemId()) && !line.isGivenHere()) {
                    line.setGivenHere(true);
                    draftLineRepository.save(line);
                }
            }
        }
        return view(businessId, draft.getId(), caller, false);
    }

    @Transactional
    public VisitResponse removeProduct(String businessId, String visitId, String lineId, Caller caller) {
        PosDraft draft = requireOpenVisit(businessId, visitId);
        // The till may take an ordinary product off the bill. A medicine was put there by a
        // clinician or the dispensary, and only they take it off.
        if (!caller.clinical()) {
            String itemId = draftLineRepository.findByIdAndDraftId(lineId, draft.getId())
                    .map(PosDraftLine::getItemId).orElse(null);
            if (itemId != null && !dispensingGate.standings(businessId, List.of(itemId)).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "A medicine comes off the bill at the dispensary or by the clinician, not at the cash desk.");
            }
        }
        ticketService.removeProduct(businessId, draft.getId(), lineId);
        return view(businessId, draft.getId(), caller, false);
    }

    // ── The prescription ──────────────────────────────────────────────

    /** Replaces the prescription's lines. Only while it is still being written. */
    @Transactional
    public VisitResponse savePrescription(String businessId, String visitId, SavePrescriptionRequest req, Caller caller) {
        PosDraft draft = requireOpenVisit(businessId, visitId);
        Prescription rx = livePrescription(businessId, draft.getId());
        if (rx != null && !Prescription.OPEN.equals(rx.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This prescription has been prepared, so it can no longer be changed. Ask the dispensary to send it back.");
        }
        User prescriber = userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(caller.userId(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Sign in again"));
        if (rx == null) {
            rx = new Prescription();
            rx.setBusinessId(businessId);
            rx.setBranchId(draft.getBranchId());
            rx.setVisitId(draft.getId());
            rx.setPatientId(draft.getPatientId());
        }
        rx.setPrescriberUserId(prescriber.getId());
        rx.setPrescriberName(clip(prescriber.getName() == null || prescriber.getName().isBlank() ? "Clinician" : prescriber.getName().strip(), 191));
        rx.setPrescriberRegNo(credentialRepository.findByUserIdAndBusinessId(prescriber.getId(), businessId)
                .map(PharmacyStaffCredential::getRegistrationNo).orElse(null));
        rx.setPrescribedOn(LocalDate.now(SHOP_ZONE));
        rx = prescriptionRepository.saveAndFlush(rx);

        Set<String> itemIds = new LinkedHashSet<>();
        for (PrescriptionLineInput in : req.lines()) {
            if (blankToNull(in.itemId()) != null) {
                itemIds.add(in.itemId().strip());
            }
        }
        Set<String> own = new LinkedHashSet<>();
        if (!itemIds.isEmpty()) {
            itemRepository.findByIdInAndBusinessIdAndDeletedAtIsNull(itemIds, businessId).forEach(i -> own.add(i.getId()));
        }
        prescriptionLineRepository.deleteAll(
                prescriptionLineRepository.findByBusinessIdAndPrescriptionIdOrderByLineIndexAsc(businessId, rx.getId()));
        prescriptionLineRepository.flush();
        int index = 0;
        List<PrescriptionLine> lines = new ArrayList<>();
        for (PrescriptionLineInput in : req.lines()) {
            String itemId = blankToNull(in.itemId());
            if (itemId != null && !own.contains(itemId.strip())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Product not found: " + in.medicineName());
            }
            PrescriptionLine line = new PrescriptionLine();
            line.setBusinessId(businessId);
            line.setPrescriptionId(rx.getId());
            line.setLineIndex(index++);
            line.setItemId(itemId == null ? null : itemId.strip());
            line.setMedicineName(clip(in.medicineName().strip(), 255));
            line.setQuantity(in.quantity());
            line.setInstructions(blankToNull(in.instructions()) == null ? null : clip(in.instructions().strip(), 500));
            lines.add(line);
        }
        prescriptionLineRepository.saveAll(lines);
        audit(businessId, caller.userId(), AuditEventTypes.CLINIC_PRESCRIPTION_SAVED, "prescription", rx.getId(), lines.size());
        return view(businessId, draft.getId(), caller, false);
    }

    /**
     * The dispensary's step: someone who may dispense checks the prescription and puts what is
     * being supplied on the bill. Supplying less than was prescribed is allowed; the dispensing
     * record then shows the balance. Nothing leaves stock until payment.
     */
    @Transactional
    public VisitResponse prepare(String businessId, String visitId, PrepareRequest req, Caller caller) {
        PosDraft draft = requireOpenVisit(businessId, visitId);
        Prescription rx = requirePrescription(businessId, draft.getId());
        if (!Prescription.OPEN.equals(rx.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This prescription has already been dealt with");
        }
        Map<String, PrescriptionLine> byId = new LinkedHashMap<>();
        for (PrescriptionLine l : prescriptionLineRepository
                .findByBusinessIdAndPrescriptionIdOrderByLineIndexAsc(businessId, rx.getId())) {
            byId.put(l.getId(), l);
        }
        int supplied = 0;
        for (PrepareLine in : req.lines()) {
            PrescriptionLine line = byId.get(in.lineId());
            if (line == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That line is not on this prescription");
            }
            if (in.quantity().signum() <= 0) {
                continue;
            }
            if (line.getItemId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        line.getMedicineName() + " is not one of this shop's products, so it cannot go on the bill");
            }
            if (in.quantity().compareTo(line.getQuantity()) > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "More " + line.getMedicineName() + " than was prescribed");
            }
            ticketService.addProduct(businessId, draft.getId(),
                    new ServiceTicketDtos.AddProductRequest(line.getItemId(), in.quantity()));
            supplied++;
        }
        rx.setStatus(supplied == 0 ? Prescription.DECLINED : Prescription.PREPARED);
        rx.setPreparedBy(caller.userId());
        rx.setPreparedAt(Instant.now());
        prescriptionRepository.save(rx);
        audit(businessId, caller.userId(), AuditEventTypes.CLINIC_PRESCRIPTION_PREPARED, "prescription", rx.getId(), supplied);
        return view(businessId, draft.getId(), caller, false);
    }

    /** Back to the prescriber: the medicines it put on the bill come off, and it can be changed again. */
    @Transactional
    public VisitResponse sendBack(String businessId, String visitId, Caller caller) {
        PosDraft draft = requireOpenVisit(businessId, visitId);
        Prescription rx = requirePrescription(businessId, draft.getId());
        if (Prescription.DISPENSED.equals(rx.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This prescription has been dispensed");
        }
        if (Prescription.PREPARED.equals(rx.getStatus())) {
            Set<String> rxItems = new LinkedHashSet<>();
            for (PrescriptionLine l : prescriptionLineRepository
                    .findByBusinessIdAndPrescriptionIdOrderByLineIndexAsc(businessId, rx.getId())) {
                if (l.getItemId() != null) {
                    rxItems.add(l.getItemId());
                }
            }
            for (PosDraftLine line : draftLineRepository.findByDraftIdOrderByLineIndexAsc(draft.getId())) {
                if (!line.isDeleted() && line.getItemId() != null && line.getServiceId() == null
                        && rxItems.contains(line.getItemId()) && !line.isGivenHere()) {
                    ticketService.removeProduct(businessId, draft.getId(), line.getId());
                }
            }
        }
        rx.setStatus(Prescription.OPEN);
        rx.setPreparedBy(null);
        rx.setPreparedAt(null);
        prescriptionRepository.save(rx);
        return view(businessId, draft.getId(), caller, false);
    }

    /** The patient is not buying the medicines here. The prescription stays, to be printed. */
    @Transactional
    public VisitResponse declineMedicines(String businessId, String visitId, Caller caller) {
        PosDraft draft = requireOpenVisit(businessId, visitId);
        Prescription rx = requirePrescription(businessId, draft.getId());
        if (!Prescription.OPEN.equals(rx.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This prescription has already been prepared. Ask the dispensary to send it back first.");
        }
        rx.setStatus(Prescription.DECLINED);
        prescriptionRepository.save(rx);
        return view(businessId, draft.getId(), caller, false);
    }

    // ── Money ─────────────────────────────────────────────────────────

    /**
     * Fee first: money taken before the consultation, held in the payer's name (ADR 0034,
     * decisions 3 and 11). A visit with no payer gets one made from the patient's own name and
     * phone, because a deposit must belong to a customer.
     */
    @Transactional
    public VisitResponse takeDeposit(
            String businessId, String visitId, DepositRequest req, Caller caller, String tillDeviceKey) {
        PosDraft draft = requireOpenVisit(businessId, visitId);
        if (draft.getCustomerId() == null) {
            PharmacyPatient patient = requirePatient(businessId, draft.getPatientId());
            String payer = patient.getCustomerId();
            if (payer == null) {
                payer = payerFor(businessId, patient, caller.userId());
                patient.setCustomerId(payer);
                patientRepository.save(patient);
            }
            draft.setCustomerId(payer);
            draftRepository.saveAndFlush(draft);
        }
        ticketService.takeDeposit(businessId, draft.getId(),
                new TakeDepositRequest(req.amount(), req.method(), req.reference()), caller.userId(), tillDeviceKey);
        return view(businessId, draft.getId(), caller, false);
    }

    private String payerFor(String businessId, PharmacyPatient patient, String userId) {
        if (patient.getPhone() != null) {
            var match = customerDirectoryService.findByPhone(businessId, patient.getPhone());
            if (!match.isNone() && !match.ambiguous()) {
                return match.customerId();
            }
        }
        List<CustomerPhoneDraft> phones = patient.getPhone() == null
                ? List.of() : List.of(new CustomerPhoneDraft(patient.getPhone(), true));
        return customerDirectoryService
                .create(businessId, new CreateCustomerRequest(patient.getName(), null, null, null, phones, null), userId)
                .id();
    }

    @Transactional
    public VisitResponse cancel(
            String businessId, String visitId, CancelVisitRequest req, Caller caller, String tillDeviceKey) {
        PosDraft draft = requireVisit(businessId, visitId);
        ticketService.cancel(businessId, draft.getId(),
                new CancelTicketRequest(req == null ? null : req.reason(),
                        req != null && Boolean.TRUE.equals(req.leftWithoutBeingSeen()),
                        req == null ? null : req.depositBack()),
                caller.userId(), tillDeviceKey);
        return view(businessId, draft.getId(), caller, false);
    }

    /**
     * Takes payment for the whole visit as one sale. Pay, then collect (decision 7): the
     * dispensing record is written by the sale, under the unchanged pharmacy rules, from the
     * prescription written on the visit.
     */
    @Transactional(noRollbackFor = TicketRepricedException.class)
    public PaidVisitResponse pay(
            String businessId, String visitId, PayVisitRequest req, String idempotencyKey,
            Caller caller, String tillDeviceKey) {
        ClinicSettings settings = settingsService.require(businessId);
        PosDraft draft = requireVisit(businessId, visitId);
        Prescription rx = livePrescription(businessId, draft.getId());
        List<PrescriptionLine> rxLines = rx == null ? List.of()
                : prescriptionLineRepository.findByBusinessIdAndPrescriptionIdOrderByLineIndexAsc(businessId, rx.getId());
        TicketResponse before = ticketService.get(businessId, draft.getId());
        boolean stillOpen = PosDraftConstants.STATUS_PENDING.equals(before.status());
        if (stillOpen && rx != null && Prescription.OPEN.equals(rx.getStatus())
                && rxLines.stream().anyMatch(l -> l.getItemId() != null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The prescription has not been prepared. Prepare it at the dispensary, or mark that the patient is not buying the medicines.");
        }
        PharmacyPatient patient = requirePatient(businessId, draft.getPatientId());
        List<PosDraftLine> productLines = draftLineRepository.findByDraftIdOrderByLineIndexAsc(draft.getId()).stream()
                .filter(l -> !l.isDeleted() && l.getItemId() != null && l.getServiceId() == null)
                .toList();
        boolean gated = dispensingGate
                .standings(businessId, productLines.stream().map(PosDraftLine::getItemId).toList()).stream()
                .anyMatch(s -> DispensingGate.ItemStanding.NEEDS_RECORD.equals(s.status()));
        if (stillOpen && gated && settings.isSecondPersonForGated() && rx != null
                && caller.userId().equals(rx.getPrescriberUserId())
                && blankToNull(req.approvalId()) == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "You wrote this prescription. This clinic asks for a second person: someone else who may dispense must take this payment.");
        }
        String approvalId = blankToNull(req.approvalId());
        if (stillOpen && gated && approvalId == null && !caller.hats().dispense()) {
            // The cashier may not dispense. If a pharmacist prepared exactly this, that is the approval.
            Map<String, BigDecimal> covered = coveredByPreparation(businessId, settings, rx, rxLines, productLines);
            if (covered != null) {
                approvalId = dispensingApprovalService.grantFromPreparation(
                        businessId, rx.getPreparedBy(), caller.userId(), covered);
            }
        }
        PostSaleDispensingRequest dispensing = dispensingFor(businessId, before, rx, rxLines, productLines, patient, approvalId);
        PaidTicketResponse paid = ticketService.pay(businessId, draft.getId(),
                new PayTicketRequest(req.payments(), null, req.cashReceived(), req.clientSoldAt(), null, dispensing),
                idempotencyKey, caller.userId(), caller.roleId(), tillDeviceKey);
        String saleId = paid.sale() == null ? null : paid.sale().id();
        if (rx != null && saleId != null && Prescription.PREPARED.equals(rx.getStatus())) {
            rx.setStatus(Prescription.DISPENSED);
            rx.setSaleId(saleId);
            prescriptionRepository.save(rx);
            for (DispensingRecord record : dispensingRecordRepository.findByBusinessIdAndSaleId(businessId, saleId)) {
                if (record.getPrescriptionId() == null) {
                    record.setPrescriptionId(rx.getId());
                    dispensingRecordRepository.save(record);
                }
            }
        }
        return new PaidVisitResponse(view(businessId, draft.getId(), caller, false), paid.sale());
    }

    /**
     * Who prescribed, for whom, and the directions per medicine. Built for every visit with a
     * known prescriber; the gate ignores it when nothing on the bill needs a record.
     */
    private PostSaleDispensingRequest dispensingFor(
            String businessId, TicketResponse ticket, Prescription rx, List<PrescriptionLine> rxLines,
            List<PosDraftLine> productLines, PharmacyPatient patient, String approvalId) {
        String prescriberName;
        String prescriberRegNo;
        LocalDate prescribedOn;
        String ref;
        if (rx != null) {
            prescriberName = rx.getPrescriberName();
            prescriberRegNo = rx.getPrescriberRegNo();
            prescribedOn = rx.getPrescribedOn();
            ref = "Visit " + ticket.ticketNumber();
        } else {
            // No written prescription (a medicine given here): the clinician who saw the patient.
            String staffProfileId = ticket.lines().stream()
                    .map(TicketLineResponse::performerStaffProfileId)
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
            StaffProfile profile = staffProfileId == null ? null
                    : staffProfileRepository.findById(staffProfileId)
                            .filter(p -> businessId.equals(p.getBusinessId())).orElse(null);
            if (profile == null) {
                return null;
            }
            User user = profile.getUserId() == null ? null
                    : userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(profile.getUserId(), businessId).orElse(null);
            prescriberName = user != null && user.getName() != null && !user.getName().isBlank()
                    ? user.getName().strip()
                    : staffProfileService.displayNamesByIds(businessId, List.of(staffProfileId)).getOrDefault(staffProfileId, null);
            if (prescriberName == null || prescriberName.isBlank()) {
                return null;
            }
            prescriberRegNo = user == null ? null
                    : credentialRepository.findByUserIdAndBusinessId(user.getId(), businessId)
                            .map(PharmacyStaffCredential::getRegistrationNo).orElse(null);
            prescribedOn = LocalDate.now(SHOP_ZONE);
            ref = "Visit " + ticket.ticketNumber();
        }
        Map<String, BigDecimal> onBill = new LinkedHashMap<>();
        Set<String> givenHere = new LinkedHashSet<>();
        for (PosDraftLine line : productLines) {
            onBill.merge(line.getItemId(), line.getQuantity(), BigDecimal::add);
            if (line.isGivenHere()) {
                givenHere.add(line.getItemId());
            }
        }
        List<PostSaleDispensingRequest.Line> lines = new ArrayList<>();
        Set<String> described = new LinkedHashSet<>();
        for (PrescriptionLine l : rxLines) {
            BigDecimal supplied = l.getItemId() == null ? null : onBill.get(l.getItemId());
            if (supplied == null || !described.add(l.getItemId())) {
                continue;
            }
            lines.add(new PostSaleDispensingRequest.Line(
                    l.getItemId(), l.getInstructions(),
                    l.getQuantity().compareTo(supplied) > 0 ? l.getQuantity() : null, null, null));
        }
        for (String itemId : givenHere) {
            if (described.add(itemId)) {
                lines.add(new PostSaleDispensingRequest.Line(itemId, GIVEN_HERE_INSTRUCTIONS));
            }
        }
        return new PostSaleDispensingRequest(
                clip(prescriberName, 191), prescriberRegNo, ref, prescribedOn,
                clip(patient.getName(), 191), patient.getPhone(), blankToNull(approvalId),
                lines.isEmpty() ? null : lines, patient.getId());
    }

    // ── Appointments ──────────────────────────────────────────────────

    @Transactional
    public VisitResponse book(String businessId, BookRequest req, Caller caller) {
        ClinicSettings settings = settingsService.require(businessId);
        PharmacyPatient patient = requirePatient(businessId, req.patientId());
        List<String> serviceIds = req.serviceIds() == null || req.serviceIds().isEmpty()
                ? List.of(settings.getConsultationServiceId())
                : new ArrayList<>(new LinkedHashSet<>(req.serviceIds()));
        boolean remind = Boolean.TRUE.equals(req.sendReminder()) && patient.getPhone() != null && patient.isReminderConsent();
        TicketResponse ticket = ticketService.book(businessId, new CreateAppointmentRequest(
                req.branchId(), req.clientVisitId(), req.staffProfileId(), req.start(),
                serviceIds.stream().map(id -> new TicketLineInput(id, null)).toList(),
                patient.getCustomerId(), VisitStations.firstName(patient.getName()), patient.getPhone(), remind),
                caller.userId());
        PosDraft draft = draftRepository.findByIdAndBusinessIdAndDraftKind(
                        ticket.id(), businessId, PosDraftConstants.KIND_SERVICE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Could not book; try again"));
        if (draft.getPatientId() == null) {
            draft.setPatientId(patient.getId());
            draftRepository.saveAndFlush(draft);
        } else if (!draft.getPatientId().equals(patient.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That booking already belongs to another patient");
        }
        return view(businessId, draft.getId(), caller, false);
    }

    @Transactional(readOnly = true)
    public List<Appointment> appointments(String businessId, String branchId, Instant from, Instant to, Caller caller) {
        settingsService.require(businessId);
        List<TicketResponse> booked = ticketService.appointments(businessId, branchId, from, to);
        if (booked.isEmpty()) {
            return List.of();
        }
        Map<String, String> patientByVisit = new LinkedHashMap<>();
        for (PosDraft d : draftRepository.findByBusinessIdAndIdInAndDraftKind(
                businessId, booked.stream().map(TicketResponse::id).toList(), PosDraftConstants.KIND_SERVICE)) {
            if (d.getPatientId() != null) {
                patientByVisit.put(d.getId(), d.getPatientId());
            }
        }
        Map<String, PharmacyPatient> patients = patientsById(businessId, patientByVisit.values());
        List<Appointment> out = new ArrayList<>();
        for (TicketResponse t : booked) {
            PharmacyPatient p = patients.get(patientByVisit.get(t.id()));
            if (p == null || !PosDraftConstants.STAGE_BOOKED.equals(t.stage())) {
                continue;
            }
            out.add(new Appointment(t.id(), t.scheduledStart(), t.scheduledEnd(), p.getName(), p.getPatientNo(),
                    t.staffProfileId(), t.staffName(), t.sendReminder()));
        }
        audit(businessId, caller.userId(), AuditEventTypes.CLINIC_VISIT_VIEWED, null, null, out.size());
        return out;
    }

    @Transactional
    public VisitResponse arrive(String businessId, String visitId, Caller caller) {
        PosDraft draft = requireOpenVisit(businessId, visitId);
        ticketService.arrive(businessId, draft.getId());
        return view(businessId, draft.getId(), caller, false);
    }

    // ── Scheme claims ─────────────────────────────────────────────────

    @Transactional
    public ClaimResponse claim(String businessId, String visitId, VisitClaimRequest req, Caller caller) {
        PosDraft draft = requireVisit(businessId, visitId);
        if (draft.getSaleId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Take payment for the visit before claiming the scheme's share");
        }
        return schemeClaimService.createClaimForSale(
                businessId, req.schemeId(), draft.getSaleId(), draft.getBranchId(), req.memberNo(),
                req.authorisationCode(), req.amountClaimed(), caller.userId());
    }

    // ── Shared ────────────────────────────────────────────────────────

    private VisitResponse view(String businessId, String visitId, Caller caller, boolean auditRead) {
        PosDraft draft = requireVisit(businessId, visitId);
        ClinicSettings settings = settingsService.require(businessId);
        TicketResponse t = ticketService.get(businessId, draft.getId());
        PharmacyPatient patient = requirePatient(businessId, draft.getPatientId());
        Prescription rx = livePrescription(businessId, draft.getId());
        List<PrescriptionLine> rxLines = rx == null ? List.of()
                : prescriptionLineRepository.findByBusinessIdAndPrescriptionIdOrderByLineIndexAsc(businessId, rx.getId());
        boolean rxStocked = rxLines.stream().anyMatch(l -> l.getItemId() != null);
        String station = VisitStations.of(t.status(), t.stage(), t.lines(), ClinicSettingsService.performIds(settings),
                rx == null || rxLines.isEmpty() ? null : rx.getStatus(), rxStocked);

        Map<String, Boolean> givenHere = new LinkedHashMap<>();
        for (PosDraftLine line : draftLineRepository.findByDraftIdOrderByLineIndexAsc(draft.getId())) {
            givenHere.put(line.getId(), line.isGivenHere());
        }
        Set<String> itemIds = new LinkedHashSet<>();
        List<TicketProductResponse> ticketProducts = t.products() == null ? List.of() : t.products();
        ticketProducts.forEach(p -> itemIds.add(p.itemId()));
        rxLines.forEach(l -> {
            if (l.getItemId() != null) {
                itemIds.add(l.getItemId());
            }
        });
        Map<String, String> classes = new LinkedHashMap<>();
        if (!itemIds.isEmpty()) {
            for (DispensingGate.ItemStanding s : dispensingGate.standings(businessId, itemIds)) {
                classes.put(s.itemId(), s.dispensingClass() == null ? "UNCLASSIFIED" : s.dispensingClass().name());
            }
        }
        List<VisitProduct> products = new ArrayList<>();
        for (TicketProductResponse p : ticketProducts) {
            products.add(new VisitProduct(p.id(), p.itemId(), p.name(), p.quantity(), p.unitPrice(), p.lineTotal(),
                    Boolean.TRUE.equals(givenHere.get(p.id())), classes.get(p.itemId())));
        }
        PrescriptionDto rxDto = null;
        if (rx != null && !rxLines.isEmpty() && caller.mayReadPrescription()) {
            String preparedBy = rx.getPreparedBy() == null ? null
                    : userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(rx.getPreparedBy(), businessId)
                            .map(User::getName).orElse(null);
            rxDto = new PrescriptionDto(rx.getId(), rx.getStatus(), rx.getPrescriberName(), rx.getPrescriberRegNo(),
                    rx.getPrescribedOn(), preparedBy, rx.getPreparedAt(),
                    rxLines.stream().map(l -> new PrescriptionLineDto(
                            l.getId(), l.getItemId(), l.getMedicineName(), l.getQuantity(), l.getInstructions(),
                            l.getItemId() == null ? null : classes.get(l.getItemId()))).toList());
            if (auditRead) {
                audit(businessId, caller.userId(), AuditEventTypes.CLINIC_PRESCRIPTION_VIEWED, "prescription", rx.getId(), 1);
            }
        }
        if (auditRead) {
            audit(businessId, caller.userId(), AuditEventTypes.CLINIC_VISIT_VIEWED, "clinic_visit", draft.getId(), 1);
        }
        LocalDate today = LocalDate.now(SHOP_ZONE);
        return new VisitResponse(
                t.id(), t.branchId(), t.ticketNumber(), station, t.status(), t.priority(),
                new VisitPatient(patient.getId(), patient.getPatientNo(), patient.getName(), patient.getPhone(),
                        PharmacyPatientService.ageYears(patient.getDateOfBirth(), today),
                        caller.clinical() ? patient.getAllergies() : null),
                t.customerId(), draft.getRequestedStaffProfileId(), t.staffName(),
                t.lines(), products, t.total(), t.depositAmount(), t.depositState(),
                draft.getReviewOfSaleId() != null, rxDto,
                rx == null || rxLines.isEmpty() ? null : rx.getStatus(),
                t.createdAt(), t.scheduledStart(), t.saleId(), t.version(),
                coveredByPreparation(businessId, settings, rx, rxLines,
                        draftLineRepository.findByDraftIdOrderByLineIndexAsc(draft.getId()).stream()
                                .filter(l -> !l.isDeleted() && l.getItemId() != null && l.getServiceId() == null)
                                .toList()) != null,
                t.saleId() == null ? null
                        : saleRepository.findById(t.saleId())
                                .filter(s -> businessId.equals(s.getBusinessId()))
                                .map(zelisline.ub.sales.domain.Sale::getReceiptNo).orElse(null));
    }

    private Set<String> stockedPrescriptionIds(String businessId, Collection<Prescription> prescriptions) {
        Set<String> out = new LinkedHashSet<>();
        if (prescriptions.isEmpty()) {
            return out;
        }
        for (PrescriptionLine l : prescriptionLineRepository.findByBusinessIdAndPrescriptionIdInOrderByLineIndexAsc(
                businessId, prescriptions.stream().map(Prescription::getId).toList())) {
            if (l.getItemId() != null) {
                out.add(l.getPrescriptionId());
            }
        }
        return out;
    }

    private Prescription livePrescription(String businessId, String visitId) {
        return prescriptionRepository.findFirstByBusinessIdAndVisitIdOrderByCreatedAtDescIdDesc(businessId, visitId)
                .orElse(null);
    }

    private Prescription requirePrescription(String businessId, String visitId) {
        Prescription rx = livePrescription(businessId, visitId);
        if (rx == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This visit has no prescription");
        }
        return rx;
    }

    /** A ticket of this business that is a clinic visit: it has a patient. 404 otherwise. */
    private PosDraft requireVisit(String businessId, String visitId) {
        return draftRepository.findByIdAndBusinessIdAndDraftKind(visitId, businessId, PosDraftConstants.KIND_SERVICE)
                .filter(d -> d.getPatientId() != null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Visit not found"));
    }

    private PosDraft requireOpenVisit(String businessId, String visitId) {
        settingsService.require(businessId);
        PosDraft draft = requireVisit(businessId, visitId);
        if (!draft.isPending()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This visit is closed");
        }
        return draft;
    }

    private PharmacyPatient requirePatient(String businessId, String patientId) {
        return patientRepository.findByIdAndBusinessIdAndDeletedAtIsNull(patientId == null ? "" : patientId.strip(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found"));
    }

    private Map<String, PharmacyPatient> patientsById(String businessId, Collection<String> ids) {
        Map<String, PharmacyPatient> out = new LinkedHashMap<>();
        Set<String> wanted = new LinkedHashSet<>(ids);
        if (wanted.isEmpty()) {
            return out;
        }
        for (PharmacyPatient p : patientRepository.findByBusinessIdAndIdIn(businessId, wanted)) {
            out.put(p.getId(), p);
        }
        return out;
    }

    private static Set<String> lineIds(TicketResponse ticket) {
        Set<String> ids = new LinkedHashSet<>();
        ticket.lines().forEach(l -> ids.add(l.id()));
        return ids;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    /** Actor and a count; never a name, a phone, a service or a medicine. */
    private void audit(String businessId, String userId, String type, String targetType, String targetId, int count) {
        try {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("count", count);
            var builder = auditEventBuilder
                    .builder(AuditEventCategory.SECURITY, type, AuditEventSeverity.INFO)
                    .businessId(businessId)
                    .actor(userId, AuditEventActorType.USER)
                    .source("clinic")
                    .newState(state);
            if (targetId != null) {
                builder.target(targetType, targetId);
            }
            auditEventPublisher.publish(builder.build());
        } catch (Exception ex) {
            log.warn("Failed to audit clinic access business={}: {}", businessId, ex.toString());
        }
    }
}
