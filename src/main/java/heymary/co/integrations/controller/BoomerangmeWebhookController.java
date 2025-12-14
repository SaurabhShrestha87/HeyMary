package heymary.co.integrations.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import heymary.co.integrations.model.IntegrationConfig;
import heymary.co.integrations.repository.IntegrationConfigRepository;
import heymary.co.integrations.service.CustomerSyncService;
import heymary.co.integrations.service.PointsSyncService;
import heymary.co.integrations.util.WebhookSecurityUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/webhooks/boomerangme")
@RequiredArgsConstructor
public class BoomerangmeWebhookController {

    private final CustomerSyncService customerSyncService;
    private final PointsSyncService pointsSyncService;
    private final IntegrationConfigRepository integrationConfigRepository;
    private final ObjectMapper objectMapper;

    /**
     * Handles webhooks from Boomerangme.
     * 
     * Expected webhook events (to be confirmed with Boomerangme documentation):
     * 
     * CUSTOMER/CARD EVENTS (require sync):
     * - CardInstalledEvent / card.installed: Customer installs the loyalty app
     * - CardCreatedEvent / card.created: New loyalty card created for customer
     * - CardUpdatedEvent / card.updated: Customer updates their card/profile
     * 
     * POINTS EVENTS (require sync):
     * - PointsUpdatedEvent / points.updated: Points balance changed
     * - CardPointsUpdatedEvent / card.points.updated: Card points updated
     * 
     * TEMPLATE/CONFIG EVENTS (no sync needed):
     * - UserTemplateUpdatedEvent: Reward card template/design updated (admin action)
     * 
     * WEBHOOK URL FORMAT:
     * https://your-domain.com/webhooks/boomerangme/card?config_id={CONFIG_ID}
     * 
     * SIGNATURE VALIDATION:
     * - Header: X-Boomerangme-Signature (or x-signature)
     * - Algorithm: HMAC-SHA256
     * - Configure webhook secret in IntegrationConfig.boomerangmeWebhookSecret
     */
    @PostMapping("/card")
    public ResponseEntity<String> handleCardWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "X-Boomerangme-Signature", required = false) String signature,
            @RequestHeader(value = "x-signature", required = false) String xSignature,
            @RequestHeader Map<String, String> headers,
            @RequestParam("config_id") Long configId) {
        
        String merchantId = "unknown";
        try {
            log.info("=== BOOMERANGME WEBHOOK RECEIVED ===");
            log.info("Config ID: {}", configId);
            
            // Use whichever signature header is provided
            String actualSignature = signature != null ? signature : xSignature;
            log.info("Signature Header: {}", actualSignature != null ? actualSignature : "NOT PROVIDED");
            
            // Log all headers
            log.info("--- All Headers ---");
            headers.forEach((key, value) -> {
                // Don't log sensitive data in full
                if (key.toLowerCase().contains("signature") && value != null && value.length() > 20) {
                    log.info("{}: {}...{} (truncated)", key, value.substring(0, 10), value.substring(value.length() - 10));
                } else {
                    log.info("{}: {}", key, value);
                }
            });
            
            // Log raw payload
            log.info("--- Raw Payload ---");
            log.info("Payload length: {} bytes", payload.length());
            log.info("Raw payload: {}", payload);
            
            // Get integration config by ID (more secure than using merchant_id)
            IntegrationConfig config = integrationConfigRepository
                    .findByIdAndEnabledTrue(configId)
                    .orElseThrow(() -> new RuntimeException("Integration config not found or disabled for ID: " + configId));
            
            merchantId = config.getMerchantId();
            log.info("Merchant ID: {}", merchantId);

            // Validate HMAC signature if webhook secret is configured
            if (config.getBoomerangmeWebhookSecret() != null && actualSignature != null) {
                boolean isValid = WebhookSecurityUtil.verifyHmacSignature(payload, actualSignature, config.getBoomerangmeWebhookSecret());
                log.info("HMAC Signature validation: {}", isValid ? "VALID" : "INVALID");
                if (!isValid) {
                    log.warn("Invalid HMAC signature for Boomerangme webhook from merchant: {}", merchantId);
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature");
                }
            } else {
                if (config.getBoomerangmeWebhookSecret() == null) {
                    log.warn("Webhook secret not configured in database - skipping validation");
                } else {
                    log.warn("Signature header not provided in request - skipping validation");
                }
            }

            // Parse JSON payload
            JsonNode cardData = objectMapper.readTree(payload);
            
            // Log prettified JSON
            log.info("--- Parsed JSON (prettified) ---");
            String prettyJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(cardData);
            log.info("\n{}", prettyJson);
            
            // Determine event type from payload
            // Boomerangme may send events like: card.created, card.installed, card.updated, etc.
            String eventType = cardData.has("event") ? cardData.get("event").asText() : "unknown";
            
            // Also check for type field as fallback
            if ("unknown".equals(eventType) && cardData.has("type")) {
                eventType = cardData.get("type").asText();
            }
            
            log.info("--- Event Processing ---");
            log.info("Event Type: {}", eventType);
            log.info("Merchant ID: {}", merchantId);
            
            // Log available fields in the payload
            StringBuilder fields = new StringBuilder();
            cardData.fieldNames().forEachRemaining(field -> {
                if (fields.length() > 0) fields.append(", ");
                fields.append(field);
            });
            log.info("Available fields in payload: [{}]", fields.toString());
            
            switch (eventType) {
                // Card Issued Event - Card created/issued but not yet installed
                case "CardIssuedEvent":
                case "card.issued":
                    log.info("ACTION: Processing CardIssuedEvent - Card created but not yet installed");
                    customerSyncService.processCardIssued(config, cardData);
                    log.info("Card issued event processed successfully");
                    break;
                
                // Card Installed Event - Customer installed the card on their device
                case "CardInstalledEvent":
                case "card.installed":
                    log.info("ACTION: Processing CardInstalledEvent - Customer installed card");
                    customerSyncService.processCardInstalled(config, cardData);
                    log.info("Card installed event processed successfully");
                    break;
                
                // Customer Card Events - These should trigger customer sync to POS
                case "card.created":
                case "card.updated":
                case "CardCreatedEvent":
                case "CardUpdatedEvent":
                    log.info("ACTION: Syncing customer from Boomerangme to POS");
                    customerSyncService.syncCustomerFromBoomerangme(merchantId, cardData);
                    log.info("Customer sync completed successfully");
                    break;
                
                // Points Events - These should trigger points sync
                case "points.updated":
                case "card.points.updated":
                case "PointsUpdatedEvent":
                case "CardPointsUpdatedEvent":
                    log.info("ACTION: Syncing points from Boomerangme");
                    if (cardData.has("card") && cardData.get("card").has("id")) {
                        String cardId = cardData.get("card").get("id").asText();
                        log.info("Card ID found in card.id: {}", cardId);
                        pointsSyncService.syncPointsFromBoomerangme(merchantId, cardId);
                    } else if (cardData.has("id")) {
                        // Card ID might be at root level
                        String cardId = cardData.get("id").asText();
                        log.info("Card ID found at root: {}", cardId);
                        pointsSyncService.syncPointsFromBoomerangme(merchantId, cardId);
                    } else {
                        log.warn("No card ID found in payload for points update event");
                    }
                    log.info("Points sync completed successfully");
                    break;
                
                // Card Balance Updated Event - Points were added/subtracted (manually or via API)
                case "CardBalanceUpdatedEvent":
                case "card.balance.updated":
                    log.info("ACTION: Processing CardBalanceUpdatedEvent - Card balance updated");
                    customerSyncService.processCardBalanceUpdated(config, cardData);
                    log.info("Card balance updated event processed successfully");
                    break;
                
                // Template/Configuration Events - These don't need customer sync
                case "UserTemplateUpdatedEvent":
                case "template.updated":
                case "template.created":
                    log.info("INFO: Template/configuration update event - no customer sync needed");
                    log.info("Template ID: {}, Name: {}", 
                        cardData.has("data") && cardData.get("data").has("id") ? cardData.get("data").get("id").asText() : "unknown",
                        cardData.has("data") && cardData.get("data").has("name") ? cardData.get("data").get("name").asText() : "unknown");
                    break;
                
                // Unknown Events - Log and attempt to process if structure matches
                default:
                    log.warn("Unhandled webhook event type: {} for merchant: {}", eventType, merchantId);
                    log.warn("IMPORTANT: Check Boomerangme documentation for this event type");
                    log.warn("Add this event type to the switch statement if it needs processing");
                    
                    // For unknown events, try to process as card event if it has card data
                    if (cardData.has("card") && cardData.get("card").has("id")) {
                        log.info("ACTION: Unknown event has card structure - attempting customer sync");
                        customerSyncService.syncCustomerFromBoomerangme(merchantId, cardData);
                        log.info("Unknown event processed as customer sync");
                    } else {
                        log.warn("Cannot process unknown event - no recognizable card data structure");
                        log.warn("Event may not require processing or needs custom handling");
                    }
            }

            log.info("=== WEBHOOK PROCESSING COMPLETED SUCCESSFULLY ===");
            return ResponseEntity.ok("Webhook received");
            
        } catch (Exception e) {
            log.error("Error processing Boomerangme webhook for merchant {}: {}", merchantId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error processing webhook: " + e.getMessage());
        }
    }
}

