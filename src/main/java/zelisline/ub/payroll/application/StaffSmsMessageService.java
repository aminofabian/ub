package zelisline.ub.payroll.application;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.credits.domain.KenyanPhoneForms;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.messaging.domain.SmsSendReason;
import zelisline.ub.messaging.infrastructure.SmsMessagingClient;
import zelisline.ub.payroll.api.dto.StaffSmsBulkSendRequest;
import zelisline.ub.payroll.api.dto.StaffSmsBulkSendResponse;
import zelisline.ub.payroll.api.dto.StaffSmsPreviewResponse;
import zelisline.ub.payroll.api.dto.StaffSmsSendRequest;
import zelisline.ub.payroll.api.dto.StaffSmsSendResponse;
import zelisline.ub.payroll.api.dto.StaffSmsTemplateResponse;
import zelisline.ub.payroll.domain.AdvanceStatus;
import zelisline.ub.payroll.domain.StaffProfile;
import zelisline.ub.payroll.repository.SalaryAdvanceRepository;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class StaffSmsMessageService {

    private record TemplateDef(
            String key,
            String label,
            String description,
            String defaultBody,
            List<String> placeholders
    ) {
        StaffSmsTemplateResponse toResponse() {
            return new StaffSmsTemplateResponse(key, label, description, defaultBody, placeholders);
        }
    }

    private static final List<TemplateDef> TEMPLATES = List.of(
            new TemplateDef(
                    "complete_profile",
                    "Complete profile",
                    "Ask staff to fill in missing HR details",
                    "Hi {name}, please complete your staff profile at {shop}. Items still needed: {missing}. Thank you.",
                    List.of("{name}", "{shop}", "{missing}")
            ),
            new TemplateDef(
                    "upload_id",
                    "National ID",
                    "Request a photo or copy of National ID",
                    "Hi {name}, please share a clear photo of your National ID with HR for our records. {shop}",
                    List.of("{name}", "{shop}")
            ),
            new TemplateDef(
                    "upload_photo",
                    "Profile photo",
                    "Ask staff to add a profile photo",
                    "Hi {name}, please add a profile photo to your staff record at {shop}. Thank you.",
                    List.of("{name}", "{shop}")
            ),
            new TemplateDef(
                    "bank_details",
                    "Payment details",
                    "Confirm M-Pesa or bank details for salary",
                    "Hi {name}, please confirm your salary payment details (M-Pesa or bank) with HR at {shop}.",
                    List.of("{name}", "{shop}")
            ),
            new TemplateDef(
                    "payslip_ready",
                    "Payslip ready",
                    "Notify that this month's payslip is available",
                    "Hi {name}, your {period} payslip from {shop} is ready. View anytime: {payLink}",
                    List.of("{name}", "{shop}", "{period}", "{payLink}")
            ),
            new TemplateDef(
                    "pay_portal",
                    "Pay portal link",
                    "Share the self-service salary portal",
                    "Hi {name}, view your salary history and payslips here: {payLink} — {shop}",
                    List.of("{name}", "{shop}", "{payLink}")
            ),
            new TemplateDef(
                    "advance_balance",
                    "Advance balance",
                    "Remind about outstanding salary advance",
                    "Hi {name}, your outstanding salary advance at {shop} is {balance}. It will be deducted from upcoming pay.",
                    List.of("{name}", "{shop}", "{balance}")
            ),
            new TemplateDef(
                    "custom",
                    "Custom message",
                    "Write your own — still supports {name}, {shop}, etc.",
                    "Hi {name}, ",
                    List.of("{name}", "{shop}", "{period}", "{payLink}", "{balance}", "{missing}")
            )
    );

    private final StaffProfileService staffProfileService;
    private final UserRepository userRepository;
    private final BusinessRepository businessRepository;
    private final SalaryAdvanceRepository salaryAdvanceRepository;
    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final SmsMessagingClient smsMessagingClient;

    @Value("${app.public.frontend-base-url:http://localhost:3000}")
    private String frontendBaseUrl;

    @Value("${app.payroll.automation.zone:Africa/Nairobi}")
    private String businessZone;

    @Transactional(readOnly = true)
    public List<StaffSmsTemplateResponse> listTemplates() {
        return TEMPLATES.stream().map(TemplateDef::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public StaffSmsPreviewResponse preview(
            String businessId,
            String userId,
            String templateKey,
            String bodyOverride
    ) {
        RenderContext ctx = buildContext(businessId, userId);
        String body = render(templateKey, bodyOverride, ctx);
        return new StaffSmsPreviewResponse(
                templateKey,
                body,
                maskPhone(ctx.phoneLocal()),
                ctx.phoneE164() != null,
                ctx.staffName()
        );
    }

    @Transactional
    public StaffSmsSendResponse send(
            String businessId,
            String userId,
            StaffSmsSendRequest request
    ) {
        RenderContext ctx = buildContext(businessId, userId);
        if (ctx.phoneE164() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Staff has no phone number on file");
        }
        String body = render(request.templateKey(), request.bodyOverride(), ctx);
        TenantMessagingConfig cfg = messagingSettingsService.resolveForDispatch(
                businessId, SmsSendReason.PAYROLL);
        SmsMessagingClient.SendResult result = smsMessagingClient.sendText(cfg, ctx.phoneE164(), body);
        return new StaffSmsSendResponse(
                result.sent() || result.stub(),
                maskPhone(ctx.phoneLocal()),
                body,
                result.detail(),
                ctx.staffName()
        );
    }

    @Transactional
    public StaffSmsBulkSendResponse sendBulk(String businessId, StaffSmsBulkSendRequest request) {
        int sent = 0;
        int skipped = 0;
        List<StaffSmsBulkSendResponse.StaffSmsBulkFailure> failures = new ArrayList<>();

        for (int i = 0; i < request.userIds().size(); i++) {
            String userId = request.userIds().get(i);
            try {
                StaffSmsSendResponse one = send(
                        businessId,
                        userId,
                        new StaffSmsSendRequest(request.templateKey(), request.bodyOverride())
                );
                if (one.sent()) {
                    sent++;
                } else {
                    skipped++;
                    failures.add(new StaffSmsBulkSendResponse.StaffSmsBulkFailure(
                            userId,
                            one.staffName(),
                            "SMS provider did not confirm delivery (" + one.providerStatus() + ")"
                    ));
                }
            } catch (ResponseStatusException ex) {
                skipped++;
                failures.add(new StaffSmsBulkSendResponse.StaffSmsBulkFailure(
                        userId,
                        displayNameSafe(businessId, userId),
                        ex.getReason() != null ? ex.getReason() : ex.getMessage()
                ));
            } catch (zelisline.ub.messaging.application.SmsCreditsDepletedException ex) {
                // Depleted mid-bulk — the remaining recipients cannot be sent; stop.
                int remaining = request.userIds().size() - i - 1;
                skipped += 1 + remaining;
                failures.add(new StaffSmsBulkSendResponse.StaffSmsBulkFailure(
                        userId,
                        displayNameSafe(businessId, userId),
                        ex.getMessage()
                ));
                break;
            }
        }

        return new StaffSmsBulkSendResponse(sent, skipped, failures);
    }

    private RenderContext buildContext(String businessId, String userId) {
        StaffProfile profile = staffProfileService.ensureProfile(businessId, userId);
        User user = userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(userId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        Business business = businessRepository.findById(businessId).orElse(null);
        String shopName = business != null && business.getName() != null
                ? business.getName().trim()
                : "Your employer";

        String staffName = profile.getDisplayName() != null && !profile.getDisplayName().isBlank()
                ? profile.getDisplayName().trim()
                : user.getName();

        String phoneRaw = profile.getPhone();
        if (phoneRaw == null || phoneRaw.isBlank()) {
            phoneRaw = user.getPhone();
        }
        String phoneLocal = phoneRaw != null ? KenyanPhoneForms.toLocal07(phoneRaw) : null;
        String phoneE164 = phoneRaw != null ? toE164(phoneRaw) : null;

        YearMonth period = YearMonth.now(ZoneId.of(businessZone));
        String periodLabel = period.getMonth().getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH)
                + " " + period.getYear();

        String payLink = phoneLocal != null
                ? frontendBaseUrl.replaceAll("/$", "") + "/pay/" + phoneLocal
                : frontendBaseUrl.replaceAll("/$", "") + "/my-pay";

        BigDecimal advanceBalance = salaryAdvanceRepository
                .findByBusinessIdAndStaffProfileIdAndStatusOrderByAdvancedOnAscCreatedAtAsc(
                        businessId, profile.getId(), AdvanceStatus.OUTSTANDING
                )
                .stream()
                .map(a -> a.getAmount().subtract(
                        a.getAmountRepaid() != null ? a.getAmountRepaid() : BigDecimal.ZERO
                ).max(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String missing = describeMissing(profile);

        return new RenderContext(staffName, shopName, periodLabel, payLink, advanceBalance, missing, phoneLocal, phoneE164);
    }

    private static String describeMissing(StaffProfile profile) {
        List<String> gaps = new ArrayList<>();
        if (isBlank(profile.getPhone())) {
            gaps.add("phone");
        }
        if (isBlank(profile.getNationalId())) {
            gaps.add("National ID");
        }
        if (isBlank(profile.getPhotoUrl())) {
            gaps.add("photo");
        }
        if (isBlank(profile.getAddress())) {
            gaps.add("address");
        }
        if (isBlank(profile.getEmergencyContactName()) || isBlank(profile.getEmergencyContactPhone())) {
            gaps.add("emergency contact");
        }
        if (profile.getBankDetails() == null || profile.getBankDetails().isBlank()) {
            gaps.add("payment details");
        }
        if (gaps.isEmpty()) {
            return "nothing — profile looks complete";
        }
        return String.join(", ", gaps);
    }

    private String render(String templateKey, String bodyOverride, RenderContext ctx) {
        TemplateDef template = findTemplate(templateKey);
        String raw = bodyOverride != null && !bodyOverride.isBlank()
                ? bodyOverride.trim()
                : template.defaultBody();
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("{name}", ctx.staffName());
        vars.put("{shop}", ctx.shopName());
        vars.put("{period}", ctx.periodLabel());
        vars.put("{payLink}", ctx.payLink());
        vars.put("{balance}", formatMoney(ctx.advanceBalance()));
        vars.put("{missing}", ctx.missing());
        String rendered = raw;
        for (var entry : vars.entrySet()) {
            rendered = rendered.replace(entry.getKey(), entry.getValue());
        }
        if (rendered.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message body is empty");
        }
        if (rendered.length() > 480) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message too long for SMS (max 480 characters)");
        }
        return rendered;
    }

    private TemplateDef findTemplate(String key) {
        return TEMPLATES.stream()
                .filter(t -> t.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown template: " + key));
    }

    private String displayNameSafe(String businessId, String userId) {
        try {
            return buildContext(businessId, userId).staffName();
        } catch (Exception ex) {
            return "Staff";
        }
    }

    private static String formatMoney(BigDecimal amount) {
        return amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String toE164(String raw) {
        String normalized = StkPhoneNormalizer.normalize(raw);
        return normalized != null ? "+" + normalized : null;
    }

    private static String maskPhone(String local) {
        if (local == null || local.length() < 4) {
            return "—";
        }
        return local.substring(0, Math.min(4, local.length())) + "…";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record RenderContext(
            String staffName,
            String shopName,
            String periodLabel,
            String payLink,
            BigDecimal advanceBalance,
            String missing,
            String phoneLocal,
            String phoneE164
    ) {
    }
}
