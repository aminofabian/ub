package zelisline.ub.marketplace.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import zelisline.ub.credits.domain.KenyanPhoneForms;
import zelisline.ub.marketplace.application.SupplierSignInDoorService.SupplierDoor;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.domain.SupplierIdentityIndex;
import zelisline.ub.marketplace.domain.SupplierUser;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.marketplace.repository.SupplierIdentityIndexRepository;
import zelisline.ub.marketplace.repository.SupplierUserRepository;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierContact;
import zelisline.ub.suppliers.repository.SupplierContactRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;

class SupplierSignInDoorServiceTest {

    private SupplierUserRepository supplierUserRepository;
    private MarketplaceSupplierRepository marketplaceSupplierRepository;
    private SupplierIdentityIndexRepository identityIndexRepository;
    private SupplierContactRepository supplierContactRepository;
    private SupplierRepository supplierRepository;
    private SupplierSignInDoorService service;

    @BeforeEach
    void setUp() {
        supplierUserRepository = Mockito.mock(SupplierUserRepository.class);
        marketplaceSupplierRepository = Mockito.mock(MarketplaceSupplierRepository.class);
        identityIndexRepository = Mockito.mock(SupplierIdentityIndexRepository.class);
        supplierContactRepository = Mockito.mock(SupplierContactRepository.class);
        supplierRepository = Mockito.mock(SupplierRepository.class);
        service = new SupplierSignInDoorService(
                supplierUserRepository,
                marketplaceSupplierRepository,
                identityIndexRepository,
                supplierContactRepository,
                supplierRepository);
    }

    @Test
    void portalAccountOnTheEmailSignsIn() {
        SupplierUser user = supplierUser("mp-1", "Kimani Wholesalers", "254714282874");
        Mockito.when(supplierUserRepository.findByEmail("supply@example.com"))
                .thenReturn(Optional.of(user));

        SupplierDoor door = service.byEmail("Supply@Example.com").orElseThrow();

        assertThat(door.claimed()).isTrue();
        assertThat(door.name()).isEqualTo("Kimani Wholesalers");
    }

    /** Claim is phone-first: the shops' email finds the passport, not the row. */
    @Test
    void passportContactEmailReachesItsPortalAccount() {
        MarketplaceSupplier passport = passport("mp-1", "Kimani Wholesalers");
        Mockito.when(marketplaceSupplierRepository
                        .findFirstByContactEmailIgnoreCaseOrderByCreatedAtAsc("supply@example.com"))
                .thenReturn(Optional.of(passport));
        Mockito.when(marketplaceSupplierRepository.findById("mp-1")).thenReturn(Optional.of(passport));
        Mockito.when(supplierUserRepository.findByMarketplaceSupplierIdAndActiveTrue("mp-1"))
                .thenReturn(List.of(supplierUser("mp-1", "Kimani Wholesalers", "254714282874")));

        SupplierDoor door = service.byEmail("supply@example.com").orElseThrow();

        assertThat(door.claimed()).isTrue();
        assertThat(door.name()).isEqualTo("Kimani Wholesalers");
    }

    @Test
    void passportWithoutAnAccountOffersTheClaim() {
        MarketplaceSupplier passport = passport("mp-1", "Kimani Wholesalers");
        Mockito.when(marketplaceSupplierRepository
                        .findFirstByContactEmailIgnoreCaseOrderByCreatedAtAsc("supply@example.com"))
                .thenReturn(Optional.of(passport));
        Mockito.when(marketplaceSupplierRepository.findById("mp-1")).thenReturn(Optional.of(passport));

        SupplierDoor door = service.byEmail("supply@example.com").orElseThrow();

        assertThat(door.claimed()).isFalse();
        assertThat(door.name()).isEqualTo("Kimani Wholesalers");
        assertThat(door.hint()).isEqualTo(SupplierSignInDoorService.CLAIM_HINT);
    }

    /** A supplier a shop typed by hand — no passport, no account, still a door. */
    @Test
    void shopContactEmailOffersTheClaim() {
        SupplierContact contact = new SupplierContact();
        contact.setSupplierId("loc-1");
        Mockito.when(supplierContactRepository.findByEmailIgnoreCase("supply@example.com"))
                .thenReturn(List.of(contact));
        Mockito.when(supplierRepository.findByIdAndDeletedAtIsNull("loc-1"))
                .thenReturn(Optional.of(localSupplier("Kimani Wholesalers", null)));

        SupplierDoor door = service.byEmail("supply@example.com").orElseThrow();

        assertThat(door.claimed()).isFalse();
        assertThat(door.name()).isEqualTo("Kimani Wholesalers");
    }

    @Test
    void strangerGetsNoSupplierDoor() {
        assertThat(service.byEmail("nobody@example.com")).isEmpty();
    }

    /** Shops type 07…; the portal stores 2547… — either has to match. */
    @Test
    void verifiedPhoneMatchesTheAccountInEitherForm() {
        Mockito.when(supplierUserRepository.findByPhone("254714282874"))
                .thenReturn(Optional.of(supplierUser("mp-1", "Kimani Wholesalers", "254714282874")));

        SupplierDoor door = service
                .byVerifiedPhone(KenyanPhoneForms.lookupCandidates("0714282874"))
                .orElseThrow();

        assertThat(door.claimed()).isTrue();
    }

    @Test
    void verifiedPhoneOnAShopsSupplierIndexOffersTheClaim() {
        SupplierIdentityIndex row = new SupplierIdentityIndex();
        row.setSupplierId("loc-1");
        Mockito.when(identityIndexRepository.findTenantByPhoneVariants(
                        Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(List.of(row));
        Mockito.when(supplierRepository.findByIdAndDeletedAtIsNull("loc-1"))
                .thenReturn(Optional.of(localSupplier("Kimani Wholesalers", null)));

        SupplierDoor door = service
                .byVerifiedPhone(KenyanPhoneForms.lookupCandidates("0714282874"))
                .orElseThrow();

        assertThat(door.claimed()).isFalse();
        assertThat(door.name()).isEqualTo("Kimani Wholesalers");
    }

    @Test
    void loginResolvesTheAccountBehindAShopKnownEmail() {
        Supplier local = localSupplier("Kimani Wholesalers", "mp-1");
        SupplierContact contact = new SupplierContact();
        contact.setSupplierId("loc-1");
        Mockito.when(supplierContactRepository.findByEmailIgnoreCase("supply@example.com"))
                .thenReturn(List.of(contact));
        Mockito.when(supplierRepository.findByIdAndDeletedAtIsNull("loc-1")).thenReturn(Optional.of(local));
        SupplierUser user = supplierUser("mp-1", "Kimani Wholesalers", "254714282874");
        Mockito.when(supplierUserRepository.findByMarketplaceSupplierIdAndActiveTrue("mp-1"))
                .thenReturn(List.of(user));

        assertThat(service.resolveLoginUser("supply@example.com")).contains(user);
    }

    private static SupplierUser supplierUser(String passportId, String name, String phone) {
        SupplierUser user = new SupplierUser();
        user.setMarketplaceSupplierId(passportId);
        user.setName(name);
        user.setPhone(phone);
        user.setActive(true);
        return user;
    }

    private static MarketplaceSupplier passport(String id, String name) {
        MarketplaceSupplier passport = new MarketplaceSupplier();
        passport.setId(id);
        passport.setName(name);
        return passport;
    }

    private static Supplier localSupplier(String name, String passportId) {
        Supplier supplier = new Supplier();
        supplier.setId("loc-1");
        supplier.setName(name);
        supplier.setMarketplaceSupplierId(passportId);
        return supplier;
    }
}
