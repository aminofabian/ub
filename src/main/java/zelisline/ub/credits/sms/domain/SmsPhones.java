package zelisline.ub.credits.sms.domain;

import java.util.List;

import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.payments.application.StkPhoneNormalizer;

/** Picks the number a customer can be texted on, if there is one. */
public final class SmsPhones {

    private SmsPhones() {
    }

    /** Primary phone, else the oldest; null when the customer has none. */
    public static CustomerPhone pick(List<CustomerPhone> phones) {
        if (phones == null || phones.isEmpty()) {
            return null;
        }
        return phones.stream().filter(CustomerPhone::isPrimary).findFirst().orElse(phones.get(0));
    }

    /** True when the only number on file is an M-Pesa mask such as 2547XXXXX123. */
    public static boolean masked(CustomerPhone phone) {
        if (phone == null) {
            return false;
        }
        String value = phone.getPhone();
        boolean hasMask = phone.getMaskedMsisdn() != null && !phone.getMaskedMsisdn().isBlank();
        if (value == null || value.isBlank()) {
            return hasMask;
        }
        return hasMask && (value.contains("x") || value.contains("X"));
    }

    /** Digits in international form (2547…), or null when the number cannot be texted. */
    public static String textableDigits(CustomerPhone phone) {
        if (phone == null || phone.getPhone() == null || phone.getPhone().isBlank() || masked(phone)) {
            return null;
        }
        return StkPhoneNormalizer.normalize(phone.getPhone());
    }
}
