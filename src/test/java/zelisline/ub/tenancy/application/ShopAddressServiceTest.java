package zelisline.ub.tenancy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.identity.application.NotificationService;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.opsalerts.api.dto.OpsAlertTestSendResponse;
import zelisline.ub.opsalerts.application.TenantOpsAlertDispatcher;
import zelisline.ub.tenancy.api.dto.ShopAddressChangeResponse;
import zelisline.ub.tenancy.api.dto.ShopAddressCheckResponse;
import zelisline.ub.tenancy.api.dto.ShopAddressStatusResponse;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.domain.DomainMapping;
import zelisline.ub.tenancy.domain.DomainSource;
import zelisline.ub.tenancy.domain.DomainStatus;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

class ShopAddressServiceTest {

    private static final String BUSINESS = "biz-1";
    private static final String OWNER = "user-1";
    private static final Instant NOW = Instant.parse("2026-11-13T09:00:00Z");

    private final BusinessRepository businesses = mock(BusinessRepository.class);
    private final DomainMappingRepository domains = mock(DomainMappingRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final NotificationService mail = mock(NotificationService.class);
    private final TenantOpsAlertDispatcher ownerMessages = mock(TenantOpsAlertDispatcher.class);
    private final AuditEventPublisher auditPublisher = mock(AuditEventPublisher.class);

    private final List<DomainMapping> held = new ArrayList<>();
    private ShopAddressService service;

    @BeforeEach
    void setUp() {
        service = new ShopAddressService(
                businesses,
                domains,
                new ReservedHostnameGuard(List.of("kiosk.ke"), "kiosk.ke"),
                users,
                mail,
                ownerMessages,
                auditPublisher,
                new AuditEventBuilder(new ObjectMapper()),
                mock(PlatformTransactionManager.class),
                "kiosk.ke") {
            @Override
            Instant now() {
                return NOW;
            }
        };

        Business business = new Business();
        business.setId(BUSINESS);
        business.setName("King Kong Shop");
        business.setSlug("king-kong");
        when(businesses.findByIdAndDeletedAtIsNull(BUSINESS)).thenReturn(Optional.of(business));
        when(businesses.findByIdAndDeletedAtIsNullForUpdate(BUSINESS)).thenReturn(Optional.of(business));
        when(businesses.findBySlugAndDeletedAtIsNull(anyString())).thenReturn(Optional.empty());

        held.add(mapping("d-original", "king-kong.kiosk.ke", true, NOW.minus(Duration.ofDays(200))));
        when(domains.findByBusinessId(BUSINESS)).thenAnswer(inv -> new ArrayList<>(held));
        when(domains.findByDomainAndDeletedAtIsNull(anyString())).thenAnswer(inv -> held.stream()
                .filter(d -> d.getDeletedAt() == null && d.getDomain().equals(inv.getArgument(0)))
                .findFirst());
        when(domains.saveAndFlush(any(DomainMapping.class))).thenAnswer(inv -> {
            DomainMapping d = inv.getArgument(0);
            if (d.getId() == null) {
                d.setId("d-new");
                d.setCreatedAt(NOW);
                held.add(d);
            }
            return d;
        });
        when(domains.findById(anyString())).thenAnswer(inv -> held.stream()
                .filter(d -> d.getId().equals(inv.getArgument(0)))
                .findFirst());
        when(domains.markDemotedPlatformPrimaryAsRedirect(eq(BUSINESS), anyString(), any())).thenAnswer(inv -> {
            String keep = inv.getArgument(1);
            int n = 0;
            for (DomainMapping d : held) {
                if (!d.getId().equals(keep)
                        && d.isPrimary()
                        && d.getSource() == DomainSource.PLATFORM_SUBDOMAIN
                        && d.getDeletedAt() == null) {
                    d.setRedirectToPrimary(true);
                    n++;
                }
            }
            return n;
        });
        when(domains.clearPrimaryForBusinessExcept(eq(BUSINESS), anyString(), any())).thenAnswer(inv -> {
            String keep = inv.getArgument(1);
            held.stream().filter(d -> !d.getId().equals(keep)).forEach(d -> d.setPrimary(false));
            return 1;
        });

        when(mail.canDeliverEmail()).thenReturn(true);
        User owner = new User();
        owner.setEmail("owner@example.com");
        when(users.findOwnersByBusinessIdIn(List.of(BUSINESS))).thenReturn(List.of(owner));
        when(ownerMessages.sendOwnerMessage(eq(BUSINESS), eq("auto"), anyString()))
                .thenReturn(new OpsAlertTestSendResponse("sms", "sent", "ok", "07••••••12"));
    }

    private static DomainMapping mapping(String id, String host, boolean primary, Instant createdAt) {
        DomainMapping d = new DomainMapping();
        d.setId(id);
        d.setBusinessId(BUSINESS);
        d.setDomain(host);
        d.setPrimary(primary);
        d.setActive(true);
        d.setStatus(DomainStatus.ACTIVE);
        d.setSource(DomainSource.PLATFORM_SUBDOMAIN);
        d.setCreatedAt(createdAt);
        return d;
    }

    @Test
    void changingAddsTheNewAddressAsPrimaryAndKeepsTheOldOneWorking() {
        ShopAddressChangeResponse result = service.change(BUSINESS, "KingKong", OWNER);

        assertThat(result.host()).isEqualTo("kingkong.kiosk.ke");
        assertThat(result.previousHost()).isEqualTo("king-kong.kiosk.ke");
        assertThat(result.url()).isEqualTo("https://kingkong.kiosk.ke");

        DomainMapping added = held.stream().filter(d -> d.getId().equals("d-new")).findFirst().orElseThrow();
        assertThat(added.isPrimary()).isTrue();
        assertThat(added.isActive()).isTrue();
        assertThat(added.getSource()).isEqualTo(DomainSource.PLATFORM_SUBDOMAIN);

        DomainMapping original = held.get(0);
        assertThat(original.isPrimary()).isFalse();
        assertThat(original.isActive()).isTrue();
        assertThat(original.getDeletedAt()).isNull();
        assertThat(original.isRedirectToPrimary()).isTrue();
        assertThat(added.isRedirectToPrimary()).isFalse();
    }

    @Test
    void theOwnerIsToldByEmailAndOnTheAlertPhone() {
        ShopAddressChangeResponse result = service.change(BUSINESS, "kingkong", OWNER);

        assertThat(result.notified()).containsExactly("email", "sms");
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(mail).sendNotificationEmail(
                eq("owner@example.com"), anyString(), text.capture(), anyString(), anyString());
        assertThat(text.getValue())
                .contains("https://kingkong.kiosk.ke")
                .contains("king-kong.kiosk.ke, sends people to the new one");
    }

    @Test
    void aFailedNoticeDoesNotUndoTheChange() {
        when(ownerMessages.sendOwnerMessage(eq(BUSINESS), eq("auto"), anyString()))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Verify an alert phone number first"));
        when(mail.canDeliverEmail()).thenReturn(false);

        ShopAddressChangeResponse result = service.change(BUSINESS, "kingkong", OWNER);

        assertThat(result.host()).isEqualTo("kingkong.kiosk.ke");
        assertThat(result.notified()).isEmpty();
    }

    @Test
    void aPastedFullAddressIsReadAsItsName() {
        assertThat(service.normalizeLabel(" https://KingKong.kiosk.ke/products ")).isEqualTo("kingkong");
        assertThat(service.normalizeLabel("kingkong")).isEqualTo("kingkong");
    }

    @Test
    void badNamesAreExplainedNotSaved() {
        assertUnavailable("ab", "at least 3");
        assertUnavailable("king_kong", "letters and numbers");
        assertUnavailable("-kingkong", "letters and numbers");
        assertUnavailable("king--kong", "letters and numbers");
        assertUnavailable("admin", "reserved");
        assertUnavailable("king-kong", "already your address");
        verify(domains, never()).saveAndFlush(any(DomainMapping.class));
    }

    @Test
    void aSingleHyphenIsStillAllowed() {
        assertThat(service.check(BUSINESS, "kong-shop").available()).isTrue();
    }

    @Test
    void anotherShopsAddressOrSlugIsTaken() {
        DomainMapping theirs = mapping("d-theirs", "mama.kiosk.ke", true, NOW);
        theirs.setBusinessId("biz-2");
        when(domains.findByDomainAndDeletedAtIsNull("mama.kiosk.ke")).thenReturn(Optional.of(theirs));
        assertUnavailable("mama", "taken");

        Business other = new Business();
        other.setId("biz-3");
        when(businesses.findBySlugAndDeletedAtIsNull("duka")).thenReturn(Optional.of(other));
        assertUnavailable("duka", "taken");
    }

    @Test
    void aSecondNewNameHasToWaitThirtyDays() {
        service.change(BUSINESS, "kingkong", OWNER);

        assertUnavailable("kk", "at least 3");
        assertUnavailable("kkshop", "recently");
        assertThatThrownBy(() -> service.change(BUSINESS, "kkshop", OWNER))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("recently");

        ShopAddressStatusResponse status = service.status(BUSINESS);
        assertThat(status.nextNewNameAllowedAt()).isEqualTo(NOW.plus(ShopAddressService.CHANGE_INTERVAL));
        assertThat(status.currentName()).isEqualTo("kingkong");
        assertThat(status.otherAddresses()).containsExactly("king-kong.kiosk.ke");
    }

    @Test
    void goingBackToAnAddressTheShopAlreadyHoldsIsAlwaysAllowed() {
        service.change(BUSINESS, "kingkong", OWNER);

        ShopAddressChangeResponse back = service.change(BUSINESS, "king-kong", OWNER);

        assertThat(back.host()).isEqualTo("king-kong.kiosk.ke");
        assertThat(held).hasSize(2);
        assertThat(held.get(0).isPrimary()).isTrue();
        assertThat(held.get(0).isRedirectToPrimary()).isFalse();
        assertThat(held.get(1).isPrimary()).isFalse();
        assertThat(held.get(1).isRedirectToPrimary()).isTrue();
    }

    @Test
    void removingAnAddressDoesNotResetTheWait() {
        DomainMapping removed = mapping("d-removed", "kk.kiosk.ke-d-removed", false, NOW.minus(Duration.ofDays(3)));
        removed.setDeletedAt(NOW.minus(Duration.ofDays(1)));
        held.add(removed);

        assertUnavailable("kkshop", "recently");
    }

    @Test
    void aShopCannotHoardAddresses() {
        for (int i = 1; i < ShopAddressService.MAX_ADDRESSES; i++) {
            held.add(mapping("d-" + i, "old" + i + ".kiosk.ke", false, NOW.minus(Duration.ofDays(100 + i))));
        }

        assertUnavailable("kkshop", "Remove one");
    }

    private void assertUnavailable(String name, String reasonPart) {
        ShopAddressCheckResponse check = service.check(BUSINESS, name);
        assertThat(check.available()).as(name).isFalse();
        assertThat(check.reason()).as(name).containsIgnoringCase(reasonPart);
    }
}
