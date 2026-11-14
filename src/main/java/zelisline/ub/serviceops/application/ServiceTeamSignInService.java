package zelisline.ub.serviceops.application;

import java.security.SecureRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.identity.application.IdentityService;
import zelisline.ub.identity.application.NotificationService;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.messaging.domain.SmsSendReason;
import zelisline.ub.messaging.infrastructure.SmsMessagingClient;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.payroll.application.StaffProfileService;
import zelisline.ub.payroll.domain.StaffProfile;
import zelisline.ub.payroll.repository.StaffProfileRepository;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.serviceops.api.dto.TeamDtos.GiveLoginRequest;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Makes a sign-in for a team member and sends it to them, so the owner does not have to invent a
 * PIN and pass it on. The PIN is made here, set on their account, emailed to their address and,
 * when they have a mobile number, texted to it (using the shop's SMS). It is returned once to
 * whoever asked, so they can read it out if neither message arrives. Asking again for someone who
 * can already sign in makes a new PIN and signs them out everywhere.
 */
@Service
@RequiredArgsConstructor
public class ServiceTeamSignInService {

    private static final Logger log = LoggerFactory.getLogger(ServiceTeamSignInService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ServiceTeamService serviceTeamService;
    private final StaffProfileService staffProfileService;
    private final StaffProfileRepository staffProfileRepository;
    private final UserRepository userRepository;
    private final IdentityService identityService;
    private final BusinessRepository businessRepository;
    private final NotificationService notificationService;
    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final SmsMessagingClient smsMessagingClient;

    public record SentSignIn(
            String staffProfileId,
            String displayName,
            String email,
            /** Shown once to the person who asked. Never stored in the clear or logged. */
            String pin,
            boolean emailSent,
            boolean smsSent,
            /** The number the text went to, masked; null when they have no usable number. */
            String phoneMasked
    ) {
    }

    /**
     * @param email    their address; needed the first time, ignored once they can sign in
     * @param phone    their mobile number; kept on their profile when given
     * @param signInAt where they sign in ("shop.kiosk.ke"), for the message; may be null
     */
    @Transactional
    public SentSignIn send(
            String businessId, String staffProfileId, String email, String phone, String branchId,
            String signInAt, TenantPrincipal caller) {
        // Locked, so two people sending at once do not leave the person with a PIN nobody was told.
        staffProfileService.lockForLedger(businessId, staffProfileId);
        StaffProfile profile = staffProfileRepository.findByIdAndBusinessId(staffProfileId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff member not found"));
        if (phone != null && !phone.isBlank()) {
            if (StkPhoneNormalizer.normalize(phone) == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid Kenyan mobile number");
            }
            profile.setPhone(phone.trim());
            staffProfileRepository.save(profile);
        }
        User user = profile.getUserId() == null ? null : userRepository.findById(profile.getUserId())
                .filter(u -> businessId.equals(u.getBusinessId()))
                .orElse(null);
        boolean canSignIn = user != null && !IdentityService.isNoSignInEmail(user.getEmail());
        String pin = newPin();
        String address;
        if (canSignIn) {
            identityService.setUserPin(businessId, user.getId(), pin);
            address = user.getEmail();
        } else {
            if (email == null || email.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Enter their email address: it is what they sign in with");
            }
            address = serviceTeamService
                    .giveLogin(businessId, staffProfileId, new GiveLoginRequest(email.trim(), pin, branchId), caller)
                    .email();
        }
        String name = profile.getDisplayName() == null || profile.getDisplayName().isBlank()
                ? "there" : profile.getDisplayName().trim().split("\\s+")[0];
        String shop = businessRepository.findById(businessId)
                .map(b -> b.getName() == null ? "" : b.getName().trim())
                .filter(n -> !n.isEmpty())
                .orElse("Your shop");
        String where = signInAt == null || signInAt.isBlank() ? null : signInAt + "/login/staff";

        boolean emailSent = sendEmail(address, shop, name, pin, where);
        String digits = StkPhoneNormalizer.normalize(profile.getPhone());
        boolean smsSent = digits != null && sendText(businessId, digits, shop, name, address, pin, where);
        return new SentSignIn(profile.getId(), profile.getDisplayName(), address, pin, emailSent, smsSent,
                digits == null ? null : mask(digits));
    }

    private boolean sendEmail(String address, String shop, String name, String pin, String where) {
        String text = "Hello " + name + ",\n\n"
                + shop + " has set you up to sign in and see your bookings and what you have earned.\n\n"
                + (where == null ? "" : "Sign in at: " + where + "\n")
                + "Email: " + address + "\n"
                + "PIN: " + pin + "\n\n"
                + "Keep your PIN to yourself. If you did not expect this, tell the shop.\n";
        try {
            notificationService.sendNotificationEmail(address, shop + ": your sign-in", text, null, shop);
            return true;
        } catch (RuntimeException ex) {
            // The reason only: the message itself carries the PIN.
            log.warn("Sign-in email could not be sent: {}", ex.getClass().getSimpleName());
            return false;
        }
    }

    private boolean sendText(
            String businessId, String digits, String shop, String name, String address, String pin, String where) {
        String message = shop + ": " + name + ", sign in" + (where == null ? "" : " at " + where)
                + " with " + address + " and PIN " + pin + ". Keep it to yourself.";
        try {
            TenantMessagingConfig config = messagingSettingsService.resolveForDispatch(businessId, SmsSendReason.NOTIFICATION);
            SmsMessagingClient.SendResult result = smsMessagingClient.sendText(config, "+" + digits, message);
            return result != null && (result.sent() || result.stub());
        } catch (RuntimeException ex) {
            log.warn("Sign-in text could not be sent: {}", ex.getClass().getSimpleName());
            return false;
        }
    }

    /** Four digits, never all the same and never a simple run up or down. */
    static String newPin() {
        while (true) {
            String pin = String.format("%04d", RANDOM.nextInt(10_000));
            if (!isEasy(pin)) {
                return pin;
            }
        }
    }

    static boolean isEasy(String pin) {
        boolean same = true;
        boolean up = true;
        boolean down = true;
        for (int i = 1; i < pin.length(); i++) {
            int step = pin.charAt(i) - pin.charAt(i - 1);
            same &= step == 0;
            up &= step == 1;
            down &= step == -1;
        }
        return same || up || down;
    }

    private static String mask(String digits) {
        return digits.length() <= 4 ? "****" : "*".repeat(digits.length() - 3) + digits.substring(digits.length() - 3);
    }
}
