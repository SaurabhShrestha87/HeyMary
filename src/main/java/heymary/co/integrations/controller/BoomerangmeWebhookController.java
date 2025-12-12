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

@Slf4j
@RestController
@RequestMapping("/webhooks/boomerangme")
@RequiredArgsConstructor
public class BoomerangmeWebhookController {

    private final CustomerSyncService customerSyncService;
    private final PointsSyncService pointsSyncService;
    private final IntegrationConfigRepository integrationConfigRepository;
    private final ObjectMapper objectMapper;

    @PostMapping("/card")
    public ResponseEntity<String> handleCardWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "X-Boomerangme-Signature", required = false) String signature,
            @RequestParam("config_id") Long configId) {
        
        String merchantId = "unknown";
        try {
            // Get integration config by ID (more secure than using merchant_id)
            IntegrationConfig config = integrationConfigRepository
                    .findByIdAndEnabledTrue(configId)
                    .orElseThrow(() -> new RuntimeException("Integration config not found or disabled for ID: " + configId));
            
            merchantId = config.getMerchantId();

            // Validate HMAC signature if webhook secret is configured
            if (config.getBoomerangmeWebhookSecret() != null && signature != null) {
                if (!WebhookSecurityUtil.verifyHmacSignature(payload, signature, config.getBoomerangmeWebhookSecret())) {
                    log.warn("Invalid HMAC signature for Boomerangme webhook from merchant: {}", merchantId);
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature");
                }
            }

            // Parse JSON payload
            JsonNode cardData = objectMapper.readTree(payload);
            
            // Determine event type from payload
            // Boomerangme may send events like: card.created, card.installed, card.updated, etc.
            String eventType = cardData.has("event") ? cardData.get("event").asText() : "unknown";
            
            // Also check for type field as fallback
            if ("unknown".equals(eventType) && cardData.has("type")) {
                eventType = cardData.get("type").asText();
            }
            
            log.info("Processing Boomerangme webhook event: {} for merchant: {}", eventType, merchantId);
            
            switch (eventType) {
                case "card.created":
                case "card.installed":  // App installation event
                case "card.updated":
                    // When a customer installs the app or creates a card, sync to Dutchie
                    customerSyncService.syncCustomerFromBoomerangme(merchantId, cardData);
                    break;
                case "points.updated":
                case "card.points.updated":
                    if (cardData.has("card") && cardData.get("card").has("id")) {
                        String cardId = cardData.get("card").get("id").asText();
                        pointsSyncService.syncPointsFromBoomerangme(merchantId, cardId);
                    } else if (cardData.has("id")) {
                        // Card ID might be at root level
                        String cardId = cardData.get("id").asText();
                        pointsSyncService.syncPointsFromBoomerangme(merchantId, cardId);
                    }
                    break;
                default:
                    log.debug("Unhandled webhook event type: {} for merchant: {}", eventType, merchantId);
                    // For unknown events, try to process as card event if it has card data
                    if (cardData.has("id") || cardData.has("card")) {
                        log.info("Attempting to process unknown event type as card event: {}", eventType);
                        customerSyncService.syncCustomerFromBoomerangme(merchantId, cardData);
                    }
            }

            return ResponseEntity.ok("Webhook received");
            
        } catch (Exception e) {
            log.error("Error processing Boomerangme webhook for merchant {}: {}", merchantId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error processing webhook: " + e.getMessage());
        }
    }
}

