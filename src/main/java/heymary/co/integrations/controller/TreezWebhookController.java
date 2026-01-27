package heymary.co.integrations.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import heymary.co.integrations.model.IntegrationConfig;
import heymary.co.integrations.repository.IntegrationConfigRepository;
import heymary.co.integrations.service.TreezWebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Webhook controller for Treez POS integration.
 * 
 * Treez webhook events:
 * - CUSTOMER: Customer created, updated, or deleted
 * - PRODUCT: Product/inventory changes
 * - TICKET: Order/transaction events
 * 
 * Webhook URL format:
 * https://your-domain.com/webhooks/treez?config_id={CONFIG_ID}
 */
@Slf4j
@RestController
@RequestMapping("/webhooks/treez")
@RequiredArgsConstructor
public class TreezWebhookController {

    private final TreezWebhookService treezWebhookService;
    private final IntegrationConfigRepository integrationConfigRepository;
    private final ObjectMapper objectMapper;

    /**
     * Main webhook endpoint for Treez events
     */
    @PostMapping
    public ResponseEntity<String> handleTreezWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Treez-Signature", required = false) String signature,
            @RequestHeader(value = "x-signature", required = false) String xSignature,
            @RequestHeader Map<String, String> headers,
            @RequestParam("config_id") Long configId) {
        
        String merchantId = "unknown";
        String eventType = "unknown";
        
        try {
            log.info("=== TREEZ WEBHOOK RECEIVED ===");
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
            
            // Get integration config
            IntegrationConfig config = integrationConfigRepository
                    .findByIdAndEnabledTrue(configId)
                    .orElseThrow(() -> new RuntimeException("Integration config not found or disabled for ID: " + configId));
            
            merchantId = config.getMerchantId();
            log.info("Merchant ID: {}", merchantId);
            
            // Enforce Bearer token verification - treez_webhook_secret is required
            if (config.getTreezWebhookSecret() == null || config.getTreezWebhookSecret().isEmpty()) {
                log.error("Treez webhook secret not configured for merchant: {}", merchantId);
                log.error("SECURITY ERROR: treez_webhook_secret must be configured in integration_configs");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("Webhook authentication not configured in Integrations. Please configure treez webhooksecret.");
            }
            
            // Authorization header is required
            if (authorization == null || authorization.isEmpty()) {
                log.warn("No Authorization header provided for Treez webhook from merchant: {}", merchantId);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Authorization required");
            }
            
            // Extract token from "Bearer TOKEN" format
            String providedToken = authorization;
            if (authorization.startsWith("Bearer ")) {
                providedToken = authorization.substring(7).trim();
            }
            
            // Compare tokens
            boolean isValid = config.getTreezWebhookSecret().equals(providedToken);
            log.info("Bearer token validation: {}", isValid ? "VALID" : "INVALID");
            
            if (!isValid) {
                log.warn("Invalid Bearer token for Treez webhook from merchant: {}", merchantId);
                log.warn("Expected token starts with: {}...", 
                        config.getTreezWebhookSecret().length() > 10 
                            ? config.getTreezWebhookSecret().substring(0, 10) 
                            : config.getTreezWebhookSecret());
                log.warn("Provided token starts with: {}...", 
                        providedToken.length() > 10 
                            ? providedToken.substring(0, 10) 
                            : providedToken);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid authorization token");
            }
            
            log.info("✓ Bearer token validated successfully");
            
            // Parse JSON payload
            JsonNode webhookData = objectMapper.readTree(payload);
            
            // Log prettified JSON
            log.info("--- Parsed JSON (prettified) ---");
            String prettyJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(webhookData);
            log.info("\n{}", prettyJson);
            
            // Extract event type from Treez webhook structure
            // Treez format: { "root": { "event_type": "CUSTOMER", "data": {...} } }
            JsonNode rootNode = webhookData.has("root") ? webhookData.get("root") : webhookData;
            
            // Log available fields in the payload
            StringBuilder fields = new StringBuilder();
            rootNode.fieldNames().forEachRemaining(field -> {
                if (fields.length() > 0) fields.append(", ");
                fields.append(field);
            });
            log.info("Available fields in root: [{}]", fields.toString());
            
            // Handle test webhooks gracefully
            if (rootNode.has("test")) {
                log.info("--- Test Webhook Detected ---");
                String dispensaryName = rootNode.has("dispensary_short_name") 
                    ? rootNode.get("dispensary_short_name").asText() 
                    : "unknown";
                log.info("Test webhook received for dispensary: {}", dispensaryName);
                log.info("=== TREEZ TEST WEBHOOK PROCESSED SUCCESSFULLY ===");
                return ResponseEntity.ok("OK, webhook received for testing");
            }
            
            if (rootNode.has("event_type")) {
                eventType = rootNode.get("event_type").asText();
            } else if (webhookData.has("event_type")) {
                eventType = webhookData.get("event_type").asText();
            }
            
            log.info("--- Event Processing ---");
            log.info("Event Type: {}", eventType);
            log.info("Merchant ID: {}", merchantId);
            
            // Process event based on type
            switch (eventType) {
                case "CUSTOMER":
                    log.info("ACTION: Processing CUSTOMER event from Treez");
                    treezWebhookService.processCustomerEvent(config, rootNode);
                    log.info("Customer event processed successfully");
                    break;
                    
                case "PRODUCT":
                    log.info("ACTION: Processing PRODUCT event from Treez");
                    treezWebhookService.processProductEvent(config, rootNode);
                    log.info("Product event processed successfully");
                    break;
                    
                case "TICKET":
                    log.info("Skipping {} event, we only process TICKET_BY_STATUS and TICKET_STATUS events", eventType);
                    break;

                case "TICKET_BY_STATUS":
                    // TICKET_BY_STATUS is sent when order status changes (e.g., COMPLETED)
                    // Contains same transaction data as TICKET events
                    log.info("ACTION: Processing {} (order/transaction) event from Treez", eventType);
                    treezWebhookService.processTicketEvent(config, rootNode);
                    log.info("{} event processed successfully", eventType);
                    break;
                    
                case "TICKET_STATUS":
                    // TICKET_STATUS is a minimal status update event with only ticket_id and order_status
                    // It doesn't contain full order data, so we check if order exists and skip if already processed
                    log.info("ACTION: Processing {} (status update) event from Treez", eventType);
                    treezWebhookService.processTicketStatusEvent(config, rootNode);
                    log.info("{} event processed successfully", eventType);
                    break;
                    
                default:
                    log.warn("Unhandled Treez webhook event type: {} for merchant: {}", eventType, merchantId);
                    log.warn("IMPORTANT: Check Treez documentation for this event type");
                    log.warn("Event data: {}", prettyJson);
                    break;
            }
            
            log.info("=== TREEZ WEBHOOK PROCESSING COMPLETED SUCCESSFULLY ===");
            return ResponseEntity.ok("Webhook received");
            
        } catch (Exception e) {
            log.error("Error processing Treez webhook for merchant {}, event {}: {}", 
                    merchantId, eventType, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error processing webhook: " + e.getMessage());
        }
    }
    
    /**
     * Health check endpoint for Treez webhook
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "healthy",
                "service", "treez-webhook",
                "timestamp", String.valueOf(System.currentTimeMillis())
        ));
    }
}

