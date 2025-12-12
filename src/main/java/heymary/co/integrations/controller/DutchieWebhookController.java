package heymary.co.integrations.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import heymary.co.integrations.model.IntegrationConfig;
import heymary.co.integrations.repository.IntegrationConfigRepository;
import heymary.co.integrations.service.CustomerSyncService;
import heymary.co.integrations.service.OrderSyncService;
import heymary.co.integrations.util.WebhookSecurityUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/webhooks/dutchie")
@RequiredArgsConstructor
public class DutchieWebhookController {

    private final OrderSyncService orderSyncService;
    private final CustomerSyncService customerSyncService;
    private final IntegrationConfigRepository integrationConfigRepository;
    private final ObjectMapper objectMapper;

    @PostMapping("/order")
    public ResponseEntity<String> handleOrderWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "X-Dutchie-Signature", required = false) String signature,
            @RequestParam("config_id") Long configId) {
        
        try {
            // Get integration config by ID (more secure than using merchant_id)
            IntegrationConfig config = integrationConfigRepository
                    .findByIdAndEnabledTrue(configId)
                    .orElseThrow(() -> new RuntimeException("Integration config not found or disabled for ID: " + configId));
            
            String merchantId = config.getMerchantId();

            // Validate HMAC signature
            if (config.getDutchieWebhookSecret() != null && signature != null) {
                if (!WebhookSecurityUtil.verifyHmacSignature(payload, signature, config.getDutchieWebhookSecret())) {
                    log.warn("Invalid HMAC signature for order webhook from merchant: {}", merchantId);
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature");
                }
            }

            // Parse JSON payload
            JsonNode orderData = objectMapper.readTree(payload);
            
            // Process order asynchronously
            orderSyncService.syncOrderFromDutchie(merchantId, orderData);

            return ResponseEntity.ok("Webhook received");
            
        } catch (Exception e) {
            log.error("Error processing Dutchie order webhook: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error processing webhook: " + e.getMessage());
        }
    }

    @PostMapping("/customer")
    public ResponseEntity<String> handleCustomerWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "X-Dutchie-Signature", required = false) String signature,
            @RequestParam("config_id") Long configId) {
        
        try {
            // Get integration config by ID (more secure than using merchant_id)
            IntegrationConfig config = integrationConfigRepository
                    .findByIdAndEnabledTrue(configId)
                    .orElseThrow(() -> new RuntimeException("Integration config not found or disabled for ID: " + configId));
            
            String merchantId = config.getMerchantId();

            // Validate HMAC signature
            if (config.getDutchieWebhookSecret() != null && signature != null) {
                if (!WebhookSecurityUtil.verifyHmacSignature(payload, signature, config.getDutchieWebhookSecret())) {
                    log.warn("Invalid HMAC signature for customer webhook from merchant: {}", merchantId);
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature");
                }
            }

            // Parse JSON payload
            JsonNode customerData = objectMapper.readTree(payload);
            
            // Process customer asynchronously
            customerSyncService.syncCustomerFromDutchie(merchantId, customerData);

            return ResponseEntity.ok("Webhook received");
            
        } catch (Exception e) {
            log.error("Error processing Dutchie customer webhook: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error processing webhook: " + e.getMessage());
        }
    }
}

