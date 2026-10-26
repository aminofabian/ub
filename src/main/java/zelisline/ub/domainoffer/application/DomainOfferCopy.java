package zelisline.ub.domainoffer.application;

import java.util.List;

/**
 * The words for the three-touch "own your address" sequence.
 *
 * <p>Placeholders are substituted by {@link DomainOfferMessagingService}:
 * {@code {{name}}}, {@code {{businessName}}}, {@code {{currentHost}}},
 * {@code {{suggestedDomain}}}, {@code {{price}}}, {@code {{payUrl}}},
 * {@code {{reference}}}, {@code {{slaDue}}}, {@code {{themeDirection}}}.
 */
public final class DomainOfferCopy {

    public static final String CTA = "Start my 24-hour setup";
    public static final String PRICE = "KES 1,000";

    private DomainOfferCopy() {
    }

    public record Touch(
            int index,
            int dayOffset,
            String label,
            String subject,
            String preview,
            String bodyMarkdown,
            String cta,
            String inAppTitle,
            String inAppBody
    ) {
    }

    public static List<Touch> touches() {
        return List.of(
                new Touch(
                        1,
                        0,
                        "The address",
                        "{{suggestedDomain}} — your shop, at its own address",
                        "Your own .co.ke address and a theme that looks like your shop. KES 1,000. Live in 24 hours.",
                        """
                        Hi {{name}},

                        {{businessName}} already has a working shop at **{{currentHost}}**. It does the job. But the address belongs to us, and the theme still reads like a Kiosk template.

                        For **{{price}}**, we will:

                        • Connect **{{suggestedDomain}}** to the shop you already run
                        • Customise the storefront theme — colours, type, layout, and the small details customers notice
                        • Hand it over within **24 hours** of payment

                        Your catalog, stock, orders, and M-Pesa setup do not change. The shop simply gets an address people remember and a face that looks like the business they walk into.

                        **Already own a domain?** We connect it.
                        **Need one?** A new .co.ke registration is separate at KES 2,000/year. The {{price}} covers the setup and the theme.

                        This is the first of three short notes. If it is not for you, ignore the next two.
                        """,
                        CTA,
                        "Your shop, at its own address",
                        "We'll connect {{suggestedDomain}} and tune your theme. KES 1,000, live in 24 hours."),
                new Touch(
                        2,
                        3,
                        "The trust",
                        "What a real address changes for {{businessName}}",
                        "Repeat customers, receipts, and WhatsApp links that look like your shop — not a subdomain.",
                        """
                        Hi {{name}},

                        A domain is not decoration. It changes how the shop travels.

                        When a customer sends **{{suggestedDomain}}** to a friend, it reads as a business. When it prints on a receipt, it survives the bin. When it sits in a WhatsApp status, it is easy to type again.

                        **What we set up for {{price}}**

                        • Your own .co.ke address, connected to the same shop
                        • A storefront theme shaped around your shop — not a stock template
                        • A handover within 24 hours, with the old kiosk address still working

                        **Before:** {{currentHost}}
                        **After:** {{suggestedDomain}}

                        The shop keeps its catalog, stock, orders, and M-Pesa. Only the address and the look change.

                        If you already own the name, we connect it. If you need a new one, registration stays separate at KES 2,000/year.
                        """,
                        "See what we would build",
                        "A .co.ke that says your name",
                        "Same shop, same stock. A domain and a theme that look like {{businessName}}."),
                new Touch(
                        3,
                        6,
                        "The handover",
                        "24 hours from now, {{businessName}} could be on {{suggestedDomain}}",
                        "Last note in this series. KES 1,000, setup and theme done within a day.",
                        """
                        Hi {{name}},

                        This is the last note in the series, so here is the whole thing in one place.

                        **{{price}}.** One payment.
                        **24 hours.** From M-Pesa confirmation to handover.
                        **{{suggestedDomain}}.** Your shop, at its own address.

                        After you pay, the M-Pesa prompt arrives on your phone. Once it clears, we start: connect the domain, customise the theme, check the storefront on a phone, and send you the new address. The kiosk address keeps working, so nothing breaks while the switch settles.

                        If you need a new .co.ke registered, that is a separate KES 2,000/year and we confirm the name with you before ordering. If you already own it, there is nothing else to buy.

                        The link stays open if you want it later. We will not keep emailing about it.
                        """,
                        CTA,
                        "Ready when you are",
                        "KES 1,000. Domain connected, theme customised, handover in 24 hours."));
    }

    public static String paymentSubject() {
        return "Payment received — {{businessName}}'s 24-hour setup has started";
    }

    public static String paymentBody() {
        return """
                Hi {{name}},

                We have your **{{price}}** for {{businessName}}'s custom domain and theme. Reference **{{reference}}**.

                **The 24-hour clock started now.** We are due to hand over by **{{slaDue}}**.

                **What we are doing**
                • Connecting **{{suggestedDomain}}** to your shop
                • Customising the storefront theme: {{themeDirection}}
                • Checking the shop on a phone before handover

                %s

                You can follow the setup from the same link you paid through. If we need a decision from you, we will email or call the number on the order.

                — Kiosk
                """.formatted(registrationLine());
    }

    private static String registrationLine() {
        return "If this name needs registering, we will confirm availability and send the separate KES 2,000/year registration step. The theme work starts now either way.";
    }

    public static String deliveredSubject() {
        return "{{businessName}} is live at {{suggestedDomain}}";
    }

    public static String deliveredBody() {
        return """
                Hi {{name}},

                {{businessName}} now has its own address: **{{suggestedDomain}}**.

                The storefront has been customised ({{themeDirection}}) and checked on a phone. The old kiosk address still works, so existing links and WhatsApp messages do not break.

                **Open the new shop:** {{suggestedDomain}}

                Reply to this email if anything looks off. We will fix it.

                — Kiosk
                """;
    }

    public static String resendSubject() {
        return "Your 24-hour domain + theme link";
    }

    public static String resendBody() {
        return """
                Hi {{name}},

                Here is the link again for {{businessName}}: **{{price}}** for a custom domain and a customised storefront theme, handed over within 24 hours of payment.

                The link is single-purpose and safe to open on your phone.
                """;
    }

    public static String inAppPaymentTitle() {
        return "Payment received. We are on it";
    }

    public static String inAppPaymentBody() {
        return "{{businessName}}'s 24-hour domain and theme setup has started. Due by {{slaDue}}.";
    }

    public static String inAppDeliveredTitle() {
        return "Your shop has its own address";
    }

    public static String inAppDeliveredBody() {
        return "{{suggestedDomain}} is connected and the theme is live. The old kiosk address still works.";
    }

    public static String inAppResendTitle() {
        return "Your domain + theme link";
    }

    public static String inAppResendBody() {
        return "KES 1,000 for a custom domain and theme, live in 24 hours. Open the link to start.";
    }

    public static String registrationSubject() {
        return "Confirm {{suggestedDomain}} — the KES 2,000 registration step";
    }

    public static String registrationBody() {
        return """
                Hi {{name}},

                We have opened the registration for **{{suggestedDomain}}** for {{businessName}}. The KES 1,000 setup and theme work you already paid for is running separately.

                **The registration is KES 2,000/year**, paid to the registrar. Confirm the name and complete it from Business → Domains. If anything about the name looks wrong, reply before you pay and we will change it.

                Your 24-hour setup clock is not paused by this step: the theme work continues while the domain is confirmed.
                """;
    }

    public static String inAppRegistrationTitle() {
        return "Confirm your .co.ke registration";
    }

    public static String inAppRegistrationBody() {
        return "{{suggestedDomain}} is ready to register for KES 2,000/year. Your KES 1,000 setup is already running.";
    }
}
