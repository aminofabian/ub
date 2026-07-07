package zelisline.ub.messaging.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;

import zelisline.ub.messaging.config.MessagingProperties;
import zelisline.ub.messaging.dto.WhatsAppSendRequest;

/**
 * Sends outbound WhatsApp messages via Meta Cloud API.
 *
 * <p>Gracefully degrades when Meta WhatsApp is not configured — logs a warning
 * and returns {@code false} rather than crashing.
 */
@Service
public class WhatsAppOutboundSender {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppOutboundSender.class);

    private final MessagingProperties messagingProperties;
    private final ObjectMapper objectMapper;

    public WhatsAppOutboundSender(MessagingProperties messagingProperties, ObjectMapper objectMapper) {
        this.messagingProperties = messagingProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * Sends a WhatsApp message via Meta Cloud API.
     *
     * @param request the message request
     * @return true if HTTP 200, false otherwise
     */
    public boolean sendMessage(WhatsAppSendRequest request) {
        if (!isConfigured()) {
            log.warn("WhatsApp outbound: Meta WhatsApp not configured — message to {} not sent", request.to());
            return false;
        }

        try {
            String url = buildUrl();
            String jsonBody = objectMapper.writeValueAsString(request);

            log.debug("WhatsApp outbound: POST {} body={}", url, jsonBody);

            HttpResponse<String> response = Unirest.post(url)
                .header("Authorization", "Bearer " + messagingProperties.metaWhatsApp().accessToken())
                .header("Content-Type", "application/json")
                .body(jsonBody)
                .asString();

            if (response.getStatus() == 200) {
                log.info("WhatsApp outbound: message sent to {} (status={})", request.to(), response.getStatus());
                return true;
            } else {
                log.error("WhatsApp outbound: failed to send message to {} (status={} body={})",
                    request.to(), response.getStatus(), response.getBody());
                return false;
            }
        } catch (Exception ex) {
            log.error("WhatsApp outbound: exception sending message to {}", request.to(), ex);
            return false;
        }
    }

    /**
     * Convenience method to send a plain text message.
     *
     * @param to   the recipient's WhatsApp number (international format, e.g. "254712345678")
     * @param body the message text
     * @return true if sent successfully
     */
    public boolean sendText(String to, String body) {
        return sendMessage(WhatsAppSendRequest.text(to, body));
    }

    /**
     * Convenience method to send a template message.
     *
     * @param to           the recipient's WhatsApp number
     * @param templateName the registered template name
     * @param languageCode the language code (e.g. "en", "en_US")
     * @return true if sent successfully
     */
    public boolean sendTemplate(String to, String templateName, String languageCode) {
        return sendMessage(WhatsAppSendRequest.template(to, templateName, languageCode));
    }

    private boolean isConfigured() {
        return messagingProperties != null
            && messagingProperties.metaWhatsApp() != null
            && messagingProperties.metaWhatsApp().configured();
    }

    private String buildUrl() {
        String graphVersion = messagingProperties.metaWhatsApp().graphVersion();
        String phoneNumberId = messagingProperties.metaWhatsApp().phoneNumberId();
        return "https://graph.facebook.com/" + graphVersion + "/" + phoneNumberId + "/messages";
    }
}
