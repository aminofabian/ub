package zelisline.ub.marketplace.application;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.domain.KenyanPhoneForms;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.domain.MarketplaceSupplierStatuses;
import zelisline.ub.marketplace.domain.SupplierIdentityIndex;
import zelisline.ub.marketplace.domain.SupplierUser;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.marketplace.repository.SupplierIdentityIndexRepository;
import zelisline.ub.marketplace.repository.SupplierUserRepository;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierContact;
import zelisline.ub.suppliers.repository.SupplierContactRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;

/**
 * Is this email / phone a supplier, and which supplier door do they get?
 *
 * <p>A portal account ({@code supplier_users}) is only the last step of a
 * supplier's life on the platform: shops enter suppliers by hand, invites mint
 * marketplace passports, and the phone-OTP claim turns either into a login.
 * The apex pass sheet has to recognise all three, otherwise a supplier whose
 * shop knows them by email — but who never claimed, or who claimed with only a
 * phone — looks like a stranger.
 *
 * <p>Returns at most one door, and never discloses the identifier we matched on
 * (a claimed door says "PIN or password", not "…the number ending 2874").
 */
@Service
@RequiredArgsConstructor
public class SupplierSignInDoorService {

    /**
     * @param claimed portal account exists — sign in; otherwise claim by SMS
     * @param name    supplier display name for the pass card
     * @param hint    one line telling the person what happens next
     */
    public record SupplierDoor(boolean claimed, String name, String hint) {
    }

    static final String FALLBACK_NAME = "Supplier portal";
    static final String SIGN_IN_HINT = "PIN or password in this sheet";
    static final String CLAIM_HINT = "Not opened yet — verify your phone by SMS";

    private final SupplierUserRepository supplierUserRepository;
    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final SupplierIdentityIndexRepository identityIndexRepository;
    private final SupplierContactRepository supplierContactRepository;
    private final SupplierRepository supplierRepository;

    @Transactional(readOnly = true)
    public Optional<SupplierDoor> byEmail(String rawEmail) {
        if (rawEmail == null || rawEmail.isBlank()) {
            return Optional.empty();
        }
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);

        Optional<SupplierUser> account = supplierUserRepository.findByEmail(email)
                .filter(SupplierUser::isActive);
        if (account.isPresent()) {
            return Optional.of(signInDoor(account.get()));
        }

        String passportId = passportIdByEmail(email);
        if (passportId != null) {
            return doorForPassport(passportId);
        }

        return localSupplierByEmail(email).flatMap(this::doorForLocalSupplier);
    }

    /**
     * Doors for a phone the platform already verified. Same shape as
     * {@link #byEmail}, but phone matching tolerates {@code 07…} / {@code 2547…}
     * forms because shops type suppliers in either.
     */
    @Transactional(readOnly = true)
    public Optional<SupplierDoor> byVerifiedPhone(List<String> phoneCandidates) {
        if (phoneCandidates == null || phoneCandidates.isEmpty()) {
            return Optional.empty();
        }
        PhoneForms forms = PhoneForms.of(phoneCandidates);

        List<SupplierUser> accounts = supplierUserRepository.findActiveByPhoneVariants(
                forms.phone(), forms.altPhone(), forms.tail());
        if (!accounts.isEmpty()) {
            return Optional.of(signInDoor(accounts.get(0)));
        }

        String passportId = passportIdByPhone(phoneCandidates, forms);
        if (passportId != null) {
            return doorForPassport(passportId);
        }

        return localSupplierByPhone(forms).flatMap(this::doorForLocalSupplier);
    }

    /**
     * The portal account behind any supplier identity — the login form accepts
     * whatever a shop knows the supplier by, not only what they typed at claim.
     */
    @Transactional(readOnly = true)
    public Optional<SupplierUser> resolveLoginUser(String rawIdentifier) {
        if (rawIdentifier == null || rawIdentifier.isBlank()) {
            return Optional.empty();
        }
        String identifier = rawIdentifier.trim();
        String passportId;
        Optional<Supplier> local;
        if (identifier.contains("@")) {
            String email = identifier.toLowerCase(Locale.ROOT);
            passportId = passportIdByEmail(email);
            local = passportId == null ? localSupplierByEmail(email) : Optional.empty();
        } else {
            List<String> candidates = KenyanPhoneForms.lookupCandidates(identifier);
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
            PhoneForms forms = PhoneForms.of(candidates);
            passportId = passportIdByPhone(candidates, forms);
            local = passportId == null ? localSupplierByPhone(forms) : Optional.empty();
        }
        if (passportId == null) {
            passportId = local.map(Supplier::getMarketplaceSupplierId)
                    .filter(id -> !id.isBlank())
                    .orElse(null);
        }
        if (passportId == null) {
            return Optional.empty();
        }
        return activeUsers(passportId).stream().findFirst();
    }

    private Optional<SupplierDoor> doorForPassport(String passportId) {
        MarketplaceSupplier passport = marketplaceSupplierRepository.findById(passportId).orElse(null);
        if (passport == null
                || MarketplaceSupplierStatuses.SUSPENDED.equalsIgnoreCase(passport.getStatus())) {
            return Optional.empty();
        }
        List<SupplierUser> users = activeUsers(passportId);
        if (!users.isEmpty()) {
            return Optional.of(signInDoor(users.get(0)));
        }
        return Optional.of(new SupplierDoor(false, displayName(passport.getName()), CLAIM_HINT));
    }

    private Optional<SupplierDoor> doorForLocalSupplier(Supplier local) {
        if (local.getMarketplaceSupplierId() != null && !local.getMarketplaceSupplierId().isBlank()) {
            Optional<SupplierDoor> passportDoor = doorForPassport(local.getMarketplaceSupplierId());
            if (passportDoor.isPresent()) {
                return passportDoor;
            }
        }
        return Optional.of(new SupplierDoor(false, displayName(local.getName()), CLAIM_HINT));
    }

    private List<SupplierUser> activeUsers(String passportId) {
        return supplierUserRepository.findByMarketplaceSupplierIdAndActiveTrue(passportId);
    }

    private SupplierDoor signInDoor(SupplierUser user) {
        String name = displayName(user.getName());
        if (FALLBACK_NAME.equals(name)) {
            name = marketplaceSupplierRepository.findById(user.getMarketplaceSupplierId())
                    .map(MarketplaceSupplier::getName)
                    .map(SupplierSignInDoorService::displayName)
                    .orElse(FALLBACK_NAME);
        }
        return new SupplierDoor(true, name, SIGN_IN_HINT);
    }

    private String passportIdByEmail(String email) {
        Optional<MarketplaceSupplier> byContact =
                marketplaceSupplierRepository.findFirstByContactEmailIgnoreCaseOrderByCreatedAtAsc(email);
        if (byContact.isPresent()) {
            return byContact.get().getId();
        }
        return firstPassportId(identityIndexRepository.findMarketplaceByEmail(email));
    }

    private String passportIdByPhone(List<String> candidates, PhoneForms forms) {
        List<MarketplaceSupplier> byContact =
                marketplaceSupplierRepository.findByContactPhoneVariants(
                        forms.phone(), forms.altPhone(), forms.tail());
        if (!byContact.isEmpty()) {
            return byContact.get(0).getId();
        }
        for (String candidate : candidates) {
            Optional<MarketplaceSupplier> legacy =
                    marketplaceSupplierRepository.findFirstByContactPhoneOrderByCreatedAtAsc(candidate);
            if (legacy.isPresent()) {
                return legacy.get().getId();
            }
        }
        return firstPassportId(identityIndexRepository.findMarketplaceByPhoneVariants(
                forms.phone(), forms.altPhone(), forms.tail()));
    }

    private Optional<Supplier> localSupplierByEmail(String email) {
        Optional<Supplier> fromIndex = firstLocalSupplier(identityIndexRepository.findTenantByEmail(email));
        if (fromIndex.isPresent()) {
            return fromIndex;
        }
        return supplierContactRepository.findByEmailIgnoreCase(email).stream()
                .map(SupplierContact::getSupplierId)
                .flatMap(id -> liveSupplier(id).stream())
                .findFirst();
    }

    private Optional<Supplier> localSupplierByPhone(PhoneForms forms) {
        Optional<Supplier> fromIndex = firstLocalSupplier(identityIndexRepository.findTenantByPhoneVariants(
                forms.phone(), forms.altPhone(), forms.tail()));
        if (fromIndex.isPresent()) {
            return fromIndex;
        }
        Optional<Supplier> fromContact = supplierContactRepository
                .findByPhoneVariants(forms.phone(), forms.altPhone(), forms.tail()).stream()
                .map(SupplierContact::getSupplierId)
                .flatMap(id -> liveSupplier(id).stream())
                .findFirst();
        if (fromContact.isPresent()) {
            return fromContact;
        }
        return supplierRepository
                .findActiveByPayoutPhoneVariants(forms.phone(), forms.altPhone(), forms.tail())
                .stream()
                .findFirst();
    }

    private Optional<Supplier> firstLocalSupplier(List<SupplierIdentityIndex> rows) {
        return rows.stream()
                .map(SupplierIdentityIndex::getSupplierId)
                .flatMap(id -> liveSupplier(id).stream())
                .findFirst();
    }

    private Optional<Supplier> liveSupplier(String supplierId) {
        if (supplierId == null || supplierId.isBlank()) {
            return Optional.empty();
        }
        return supplierRepository.findByIdAndDeletedAtIsNull(supplierId);
    }

    private static String firstPassportId(List<SupplierIdentityIndex> rows) {
        return rows.stream()
                .map(SupplierIdentityIndex::getMarketplaceSupplierId)
                .filter(id -> id != null && !id.isBlank())
                .findFirst()
                .orElse(null);
    }

    private static String displayName(String raw) {
        if (raw == null || raw.isBlank()) {
            return FALLBACK_NAME;
        }
        return raw.trim();
    }

    /** The three forms the supplier tables are queried by. */
    private record PhoneForms(String phone, String altPhone, String tail) {

        static PhoneForms of(List<String> candidates) {
            String msisdn = candidates.stream()
                    .filter(value -> value.startsWith("254"))
                    .findFirst()
                    .orElse(candidates.get(0));
            String local = candidates.stream()
                    .filter(value -> value.startsWith("0"))
                    .findFirst()
                    .orElse(msisdn);
            String tail = msisdn.length() >= 9 ? msisdn.substring(msisdn.length() - 9) : msisdn;
            return new PhoneForms(msisdn, local, tail);
        }
    }
}
