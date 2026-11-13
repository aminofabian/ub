package zelisline.ub.credits.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerOrigins;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.payments.domain.spi.WebhookResult;
import zelisline.ub.sales.repository.SaleRepository;

@ExtendWith(MockitoExtension.class)
class MpesaPayerIdentityServiceTest {

    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private CustomerPhoneRepository customerPhoneRepository;
    @Mock
    private CreditAccountRepository creditAccountRepository;
    @Mock
    private SaleRepository saleRepository;

    private MpesaPayerIdentityService service;

    @BeforeEach
    void setUp() {
        service = new MpesaPayerIdentityService(
                customerRepository,
                customerPhoneRepository,
                creditAccountRepository,
                saleRepository);
    }

    @Test
    void createsInferredCustomerFromMaskedWebhook() {
        when(customerRepository.findByBusinessIdAndMpesaIdentityKeyAndDeletedAtIsNull(
                eq("biz"), eq("JOHN|DOE|2547|123")))
                .thenReturn(Optional.empty());
        when(customerPhoneRepository.findByBusinessIdAndMaskFingerprint("biz", "2547|123"))
                .thenReturn(List.of());
        when(customerRepository.nextCustomerNo("biz")).thenReturn(Optional.of(12L));
        when(customerRepository.save(any(Customer.class))).thenAnswer(inv -> {
            Customer c = inv.getArgument(0);
            if (c.getId() == null) {
                c.setId("cust-1");
            }
            return c;
        });
        when(customerPhoneRepository.findByCustomerIdOrderByCreatedAtAsc("cust-1"))
                .thenReturn(List.of());
        when(creditAccountRepository.findByCustomerIdAndBusinessId("cust-1", "biz"))
                .thenReturn(Optional.empty());

        WebhookResult parsed = new WebhookResult(
                null, "OAG81M7W3K", null, new BigDecimal("100.00"), "OAG81M7W3K",
                true, false, null, "evt-1", "buygoods_transaction_received", "{}",
                null, "JOHN", "DOE", "2547XXXXX123", true);

        Customer created = service.resolveFromWebhook("biz", parsed).orElseThrow();

        assertThat(created.getCustomerNo()).isEqualTo(13L);
        assertThat(created.getOrigin()).isEqualTo(CustomerOrigins.MPESA_INFERRED);
        assertThat(created.getMpesaIdentityKey()).isEqualTo("JOHN|DOE|2547|123");
        ArgumentCaptor<CustomerPhone> phoneCap = ArgumentCaptor.forClass(CustomerPhone.class);
        verify(customerPhoneRepository).save(phoneCap.capture());
        assertThat(phoneCap.getValue().getPhone()).isNull();
        assertThat(phoneCap.getValue().getMaskedMsisdn()).isEqualTo("2547XXXXX123");
        assertThat(phoneCap.getValue().getAssignedMsisdn()).isEqualTo("254700000123");
        ArgumentCaptor<CreditAccount> tabCap = ArgumentCaptor.forClass(CreditAccount.class);
        verify(creditAccountRepository).save(tabCap.capture());
        assertThat(tabCap.getValue().getCreditLimit()).isEqualByComparingTo("0");
    }

    @Test
    void reusesExistingIdentityKey() {
        Customer existing = new Customer();
        existing.setId("cust-9");
        existing.setBusinessId("biz");
        existing.setOrigin(CustomerOrigins.MPESA_INFERRED);
        existing.setFirstName("John");
        existing.setLastName("Doe");
        when(customerRepository.findByBusinessIdAndMpesaIdentityKeyAndDeletedAtIsNull(
                eq("biz"), eq("JOHN|DOE|2547|123")))
                .thenReturn(Optional.of(existing));
        when(customerPhoneRepository.findByCustomerIdOrderByCreatedAtAsc("cust-9"))
                .thenReturn(List.of());

        WebhookResult parsed = new WebhookResult(
                null, "OAG81M7W3K", null, new BigDecimal("100.00"), "OAG81M7W3K",
                true, false, null, "evt-1", "buygoods_transaction_received", "{}",
                null, "JOHN", "DOE", "2547XXXXX123", true);

        Customer resolved = service.resolveFromWebhook("biz", parsed).orElseThrow();
        assertThat(resolved.getId()).isEqualTo("cust-9");
        verify(customerRepository, org.mockito.Mockito.never()).nextCustomerNo(any());
    }

    @Test
    void createsInferredCustomerWhenLastNameIsMissing() {
        when(customerRepository.findByBusinessIdAndMpesaIdentityKeyAndDeletedAtIsNull(
                eq("biz"), eq("JOHN|_|2547|123")))
                .thenReturn(Optional.empty());
        when(customerPhoneRepository.findByBusinessIdAndMaskFingerprint("biz", "2547|123"))
                .thenReturn(List.of());
        when(customerRepository.nextCustomerNo("biz")).thenReturn(Optional.of(13L));
        when(customerRepository.save(any(Customer.class))).thenAnswer(inv -> {
            Customer c = inv.getArgument(0);
            if (c.getId() == null) {
                c.setId("cust-13");
            }
            return c;
        });
        when(customerPhoneRepository.findByCustomerIdOrderByCreatedAtAsc("cust-13"))
                .thenReturn(List.of());
        when(creditAccountRepository.findByCustomerIdAndBusinessId("cust-13", "biz"))
                .thenReturn(Optional.empty());

        WebhookResult parsed = new WebhookResult(
                null, "OAG81M7W3K", null, new BigDecimal("100.00"), "OAG81M7W3K",
                true, false, null, "evt-1", "buygoods_transaction_received", "{}",
                null, "JOHN", null, "2547XXXXX123", true);

        Customer created = service.resolveFromWebhook("biz", parsed).orElseThrow();
        assertThat(created.getMpesaIdentityKey()).isEqualTo("JOHN|_|2547|123");
        assertThat(created.getFirstName()).isEqualTo("John");
    }

    private Customer holderOfPhone(String name, String origin) {
        Customer customer = new Customer();
        customer.setId("cust-7");
        customer.setBusinessId("biz");
        customer.setName(name);
        String[] parts = zelisline.ub.credits.domain.PayerNameNormalizer.splitDisplayName(name);
        customer.setFirstName(parts[0]);
        customer.setLastName(parts[1].isEmpty() ? null : parts[1]);
        customer.setOrigin(origin);
        zelisline.ub.credits.domain.CustomerPhone phone = new zelisline.ub.credits.domain.CustomerPhone();
        phone.setBusinessId("biz");
        phone.setCustomerId("cust-7");
        phone.setPhone("0712345678");
        org.mockito.Mockito.lenient()
                .when(customerPhoneRepository.findFirstByBusinessIdAndPhone(eq("biz"), any()))
                .thenReturn(Optional.of(phone));
        org.mockito.Mockito.lenient()
                .when(customerRepository.findByIdAndBusinessIdAndDeletedAtIsNull("cust-7", "biz"))
                .thenReturn(Optional.of(customer));
        return customer;
    }

    @Test
    void payerNameReplacesAPhoneNumberUsedAsTheName() {
        Customer placeholder = holderOfPhone("254712345678", CustomerOrigins.STAFF);

        Customer named = service.namePhoneHolder("biz", "254712345678", "JOHN", "KAMAU").orElseThrow();

        assertThat(named.getId()).isEqualTo("cust-7");
        assertThat(placeholder.getName()).isEqualTo("John Kamau");
        assertThat(placeholder.getFirstName()).isEqualTo("John");
        assertThat(placeholder.getLastName()).isEqualTo("Kamau");
        verify(customerRepository).save(placeholder);
    }

    @Test
    void payerNameDoesNotOverwriteANameStaffTyped() {
        Customer typed = holderOfPhone("Mama Kamso", CustomerOrigins.STAFF);

        service.namePhoneHolder("biz", "0712345678", "JANE", "WANJIRU");

        assertThat(typed.getName()).isEqualTo("Mama Kamso");
        verify(customerRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void payerNameDoesNotOverwriteANameThatHasNoFirstAndLastParts() {
        // A desktop till syncs customers with a display name only.
        Customer typed = holderOfPhone("Mama Kamso", CustomerOrigins.STAFF);
        typed.setFirstName(null);
        typed.setLastName(null);

        service.namePhoneHolder("biz", "0712345678", "JANE", "WANJIRU");

        assertThat(typed.getName()).isEqualTo("Mama Kamso");
        verify(customerRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void kopokopoSubscriberEchoIsNotTreatedAsAName() {
        Customer placeholder = holderOfPhone("254712345678", CustomerOrigins.STAFF);

        service.namePhoneHolder("biz", "254712345678", "Customer", "");

        assertThat(placeholder.getName()).isEqualTo("254712345678");
        verify(customerRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void maskedNumberNamesNobody() {
        assertThat(service.namePhoneHolder("biz", "2547XXXXX678", "JOHN", "KAMAU")).isEmpty();
    }
}
