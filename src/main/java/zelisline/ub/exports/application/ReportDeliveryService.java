package zelisline.ub.exports.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.exports.domain.ReportSubscription;
import zelisline.ub.exports.repository.ReportSubscriptionRepository;
import zelisline.ub.identity.application.NotificationService;
import zelisline.ub.opsalerts.application.TenantOpsAlertDispatcher;

/**
 * CRUD for report-delivery subscriptions and the shared delivery routine (summary over
 * WhatsApp/SMS/email). The scheduler and the "send now" action both call {@link #deliver}.
 */
@Service
@RequiredArgsConstructor
public class ReportDeliveryService {

    private static final Set<String> VALID_CHANNELS = Set.of("whatsapp", "sms", "email");

    private final ReportSubscriptionRepository repository;
    private final ExportService exportService;
    private final TenantOpsAlertDispatcher opsAlertDispatcher;
    private final NotificationService mailService;

    public record DeliveryOutcome(List<String> sent, List<String> failed) {
        public boolean anySent() {
            return sent != null && !sent.isEmpty();
        }
    }

    // ---- CRUD ------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ReportSubscription> list(String businessId) {
        return repository.findByBusinessIdOrderByCreatedAtDesc(businessId);
    }

    @Transactional
    public ReportSubscription create(String businessId, String userId, ReportSubscriptionInput in) {
        ReportSubscription sub = new ReportSubscription();
        sub.setBusinessId(businessId);
        sub.setCreatedBy(userId);
        apply(sub, in);
        return repository.save(sub);
    }

    @Transactional
    public ReportSubscription update(String businessId, String id, ReportSubscriptionInput in) {
        ReportSubscription sub = repository.findByIdAndBusinessId(id, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription not found"));
        apply(sub, in);
        return repository.save(sub);
    }

    @Transactional
    public void delete(String businessId, String id) {
        ReportSubscription sub = repository.findByIdAndBusinessId(id, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription not found"));
        repository.delete(sub);
    }

    @Transactional
    public boolean claimPeriod(String id, String period) {
        return repository.claimPeriod(id, period) > 0;
    }

    // ---- delivery --------------------------------------------------------

    /** Deliver the report summary for [from,to] over the subscription's channels. Never throws per channel. */
    public DeliveryOutcome deliver(ReportSubscription sub, LocalDate from, LocalDate to) {
        ReportTable table = exportService.buildReportTable(
                sub.getBusinessId(), sub.getReportKey(),
                new ExportJobParams(from, to, blankToNull(sub.getBranchId()),
                        blankToNull(sub.getCategoryId()), null));
        ReportSummary summary = ExportService.summarize(table);

        Set<String> channels = parseChannels(sub.getChannels());
        List<String> sent = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        String phoneChannel = phoneChannel(channels);
        if (phoneChannel != null) {
            try {
                opsAlertDispatcher.sendOwnerMessage(sub.getBusinessId(), phoneChannel, summary.text());
                sent.add(phoneChannel);
            } catch (RuntimeException ex) {
                failed.add("phone: " + safe(ex.getMessage()));
            }
        }
        if (channels.contains("email")) {
            String email = sub.getRecipientEmail();
            if (email == null || email.isBlank()) {
                failed.add("email: no recipient address");
            } else if (!mailService.canDeliverEmail()) {
                failed.add("email: no mail provider configured");
            } else {
                try {
                    mailService.sendNotificationEmail(
                            email.trim(), emailSubject(summary), summary.text(), emailHtml(summary));
                    sent.add("email");
                } catch (RuntimeException ex) {
                    failed.add("email: " + safe(ex.getMessage()));
                }
            }
        }
        return new DeliveryOutcome(sent, failed);
    }

    /** User-initiated immediate send; surfaces failures when nothing went out. */
    @Transactional(readOnly = true)
    public DeliveryOutcome sendNow(String businessId, String id) {
        ReportSubscription sub = repository.findByIdAndBusinessId(id, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription not found"));
        ReportScheduleMathWindow w = windowForNow(sub);
        DeliveryOutcome outcome = deliver(sub, w.from(), w.to());
        if (!outcome.anySent()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    outcome.failed().isEmpty() ? "Nothing was sent" : String.join("; ", outcome.failed()));
        }
        return outcome;
    }

    private record ReportScheduleMathWindow(LocalDate from, LocalDate to) {
    }

    private ReportScheduleMathWindow windowForNow(ReportSubscription sub) {
        var freq = parseFrequency(sub.getFrequency());
        LocalDate today = LocalDate.now();
        LocalDate[] w = zelisline.ub.exports.scheduler.ReportScheduleMath.window(freq, today);
        return new ReportScheduleMathWindow(w[0], w[1]);
    }

    public static zelisline.ub.exports.scheduler.ReportScheduleMath.Frequency parseFrequency(String f) {
        if (f == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "frequency is required");
        }
        return switch (f.trim().toLowerCase(Locale.ROOT)) {
            case "daily" -> zelisline.ub.exports.scheduler.ReportScheduleMath.Frequency.DAILY;
            case "weekly" -> zelisline.ub.exports.scheduler.ReportScheduleMath.Frequency.WEEKLY;
            case "monthly" -> zelisline.ub.exports.scheduler.ReportScheduleMath.Frequency.MONTHLY;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown frequency: " + f);
        };
    }

    // ---- mapping ---------------------------------------------------------

    /** Neutral create/update payload (controller maps its DTO to this). */
    public record ReportSubscriptionInput(
            String reportKey, String format, String frequency, String sendTime,
            Integer dayOfWeek, Integer dayOfMonth, List<String> channels,
            String recipientEmail, String branchId, String categoryId, Boolean active) {
    }

    private void apply(ReportSubscription sub, ReportSubscriptionInput in) {
        sub.setReportKey(requireKey(in.reportKey()));
        sub.setFormat(in.format() == null || in.format().isBlank() ? "pdf" : in.format().trim().toLowerCase(Locale.ROOT));
        parseFrequency(in.frequency());
        sub.setFrequency(in.frequency().trim().toLowerCase(Locale.ROOT));
        sub.setSendTime(normalizeTime(in.sendTime()));
        sub.setDayOfWeek(in.dayOfWeek());
        sub.setDayOfMonth(in.dayOfMonth());
        Set<String> channels = parseChannels(String.join(",", in.channels() == null ? List.of() : in.channels()));
        if (channels.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick at least one channel");
        }
        if (channels.contains("email") && (in.recipientEmail() == null || in.recipientEmail().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email channel needs a recipient address");
        }
        sub.setChannels(String.join(",", channels));
        sub.setRecipientEmail(blankToNull(in.recipientEmail()));
        sub.setBranchId(blankToNull(in.branchId()));
        sub.setCategoryId(blankToNull(in.categoryId()));
        sub.setActive(in.active() == null || in.active());
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reportKey is required");
        }
        return key.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeTime(String raw) {
        String t = raw == null || raw.isBlank() ? "07:00" : raw.trim();
        if (!t.matches("^([01]\\d|2[0-3]):[0-5]\\d$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sendTime must be HH:mm");
        }
        return t;
    }

    public static Set<String> parseChannels(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv == null) {
            return out;
        }
        for (String part : Arrays.asList(csv.split(","))) {
            String c = part.trim().toLowerCase(Locale.ROOT);
            if (VALID_CHANNELS.contains(c)) {
                out.add(c);
            }
        }
        return out;
    }

    private static String phoneChannel(Set<String> channels) {
        boolean wa = channels.contains("whatsapp");
        boolean sms = channels.contains("sms");
        if (wa && sms) {
            return "both";
        }
        if (wa) {
            return "whatsapp";
        }
        if (sms) {
            return "sms";
        }
        return null;
    }

    private static String emailSubject(ReportSummary summary) {
        String shop = summary.businessName() == null || summary.businessName().isBlank()
                ? "Your shop" : summary.businessName();
        return shop + " report: " + (summary.title() == null ? "" : summary.title());
    }

    private static String emailHtml(ReportSummary summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"font-family:system-ui,Segoe UI,Arial,sans-serif;max-width:560px\">");
        sb.append("<h2 style=\"margin:0 0 4px;color:#1E3B26\">")
                .append(escape(summary.businessName())).append("</h2>");
        sb.append("<div style=\"color:#4A5A4E;font-weight:600;margin-bottom:10px\">")
                .append(escape(summary.title())).append("</div>");
        if (summary.highlights() != null && !summary.highlights().isEmpty()) {
            sb.append("<div style=\"display:flex;flex-wrap:wrap;gap:8px;margin-bottom:12px\">");
            for (ReportSummary.Highlight h : summary.highlights()) {
                sb.append("<div style=\"border:1px solid #D5DCCE;border-radius:8px;padding:6px 10px\">")
                        .append("<div style=\"font-size:10px;text-transform:uppercase;color:#4A5A4E\">")
                        .append(escape(h.label())).append("</div>")
                        .append("<div style=\"font-size:16px;font-weight:700;color:#24312A\">")
                        .append(escape(h.value())).append("</div></div>");
            }
            sb.append("</div>");
        }
        sb.append("<pre style=\"white-space:pre-wrap;background:#F4F6F2;border-radius:8px;padding:12px;"
                + "font-family:inherit;font-size:13px;color:#24312A\">")
                .append(escape(summary.text())).append("</pre>");
        sb.append("<div style=\"color:#9aa39a;font-size:11px;margin-top:8px\">Sent by Kiosk.ke</div>");
        sb.append("</div>");
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String safe(String s) {
        return s == null ? "failed" : s;
    }
}
