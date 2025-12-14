package heymary.co.integrations.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import heymary.co.integrations.model.IntegrationConfig;
import heymary.co.integrations.model.IntegrationType;
import heymary.co.integrations.repository.IntegrationConfigRepository;
import heymary.co.integrations.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.dutchie.polling.enabled", havingValue = "true", matchIfMissing = true)
public class DutchieOrderPollingService {

    private final IntegrationConfigRepository integrationConfigRepository;
    private final OrderRepository orderRepository;
    private final DutchieApiClient dutchieApiClient;
    private final OrderSyncService orderSyncService;
    private final ObjectMapper objectMapper;
    
    // Log on bean initialization to verify service is created
    @PostConstruct
    public void init() {
        log.info("DutchieOrderPollingService initialized - polling will start in 5 seconds, then every 10 seconds");
    }
    
    /**
     * Poll Dutchie API for new orders every 10 seconds (configurable)
     * This can be configured via application.yml: app.dutchie.polling.interval
     * Set app.dutchie.polling.enabled=false to disable polling
     */
    @Scheduled(fixedDelayString = "${app.dutchie.polling.interval:10000}", initialDelay = 5000) // Default: 10 seconds, start after 5 seconds
    public void pollForNewOrders() {
        try {
            // Get all enabled integration configs
            List<IntegrationConfig> configs = integrationConfigRepository.findAllByIntegrationTypeAndEnabledTrue(IntegrationType.DUTCHIE);
            
            if (configs.isEmpty()) {
                return;
            }
            
            log.info("Found {} enabled integration(s), starting polling...", configs.size());
            
            for (IntegrationConfig config : configs) {
                pollOrdersForMerchant(config);
            }
            
        } catch (Exception e) {
            log.error("Error in scheduled order polling: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Poll transactions (orders) for a specific merchant
     * Uses /reporting/transactions endpoint which returns completed transactions
     */
    private void pollOrdersForMerchant(IntegrationConfig config) {
        String merchantId = config.getMerchantId();
        String authHeader = config.getDutchieAuthHeader();
        
        if (authHeader == null || authHeader.isEmpty()) {
            log.warn("No Dutchie auth header configured for merchant: {}", merchantId);
            return;
        }
        
        log.info("Polling pre-orders for merchant: {}", merchantId);
        log.debug("Note: Pre-orders endpoint returns orders from last 14 days only (no date filtering supported)");
        
        try {
            // Use pre-orders endpoint (requires PreOrder role, not Reporting role)
            // Returns orders from last 14 days with status: Submitted, Processing, Filled, Complete, Cancelled
            JsonNode preOrders = dutchieApiClient.listPreOrders(authHeader)
                    .block(); // Block since we're in a scheduled method
            
            // Handle both single object and array responses
            JsonNode ordersArray;
            if (preOrders == null) {
                log.warn("Null response from Dutchie API for merchant: {}", merchantId);
                return;
            } else if (preOrders.isArray()) {
                ordersArray = preOrders;
            } else {
                // Single PreOrderStatus object - wrap in array
                ordersArray = objectMapper.createArrayNode().add(preOrders);
            }
            
            if (ordersArray.size() == 0) {
                log.debug("No pre-orders found for merchant {}", merchantId);
                return;
            }
            
            log.info("Found {} pre-orders for merchant {}", ordersArray.size(), merchantId);
            
            int totalOrdersProcessed = 0;
            
            // Process each pre-order
            // Only process completed orders (status: Complete) to sync points
            for (JsonNode preOrderData : ordersArray) {
                try {
                    // Check order status - only sync completed orders
                    String status = preOrderData.has("Status") ? preOrderData.get("Status").asText() : "";
                    if (!"Complete".equalsIgnoreCase(status)) {
                        log.debug("Skipping pre-order {} with status: {}", 
                                preOrderData.has("OrderId") ? preOrderData.get("OrderId").asText() : "unknown",
                                status);
                        continue;
                    }
                    
                    // Convert PreOrderStatus to Transaction-like format for OrderSyncService
                    JsonNode transactionData = convertPreOrderToTransaction(preOrderData);
                    orderSyncService.syncOrderFromDutchie(merchantId, transactionData);
                    totalOrdersProcessed++;
                } catch (Exception e) {
                    log.error("Error syncing pre-order {} for merchant {}: {}", 
                            preOrderData.has("OrderId") ? preOrderData.get("OrderId").asText() : "unknown",
                            merchantId, e.getMessage(), e);
                }
            }
            
            log.info("Completed polling pre-orders for merchant: {} - processed {} completed orders", 
                    merchantId, totalOrdersProcessed);
                    
        } catch (Exception e) {
            String errorMsg = e.getMessage();
            if (errorMsg != null && errorMsg.contains("403")) {
                log.error("Access forbidden (403) for merchant {}: The API key does not have 'PreOrder' role authorization. " +
                        "Please contact Dutchie support to enable the 'PreOrder' role for your API key. " +
                        "Error: {}", merchantId, errorMsg);
            } else {
                log.error("Error fetching pre-orders for merchant {}: {}", 
                        merchantId, e.getMessage(), e);
            }
        }
    }
    
    /**
     * Convert PreOrderStatus to Transaction-like format for OrderSyncService
     * PreOrderStatus has: OrderId, TransactionId, CustomerId, Total, Status, CreatedDate, etc.
     * Transaction format expects: transactionId, customerId, total, transactionDate
     */
    private JsonNode convertPreOrderToTransaction(JsonNode preOrder) {
        ObjectNode transaction = objectMapper.createObjectNode();
        
        // Map TransactionId (use TransactionId if available, otherwise OrderId)
        if (preOrder.has("TransactionId") && !preOrder.get("TransactionId").isNull()) {
            transaction.put("transactionId", preOrder.get("TransactionId").asInt());
        } else if (preOrder.has("OrderId")) {
            transaction.put("transactionId", preOrder.get("OrderId").asInt());
        }
        
        // Map CustomerId
        if (preOrder.has("CustomerId") && !preOrder.get("CustomerId").isNull()) {
            transaction.put("customerId", preOrder.get("CustomerId").asInt());
        }
        
        // Map Total
        if (preOrder.has("Total")) {
            transaction.put("total", preOrder.get("Total").asDouble());
        } else if (preOrder.has("TotalAmount")) {
            transaction.put("total", preOrder.get("TotalAmount").asDouble());
        }
        
        // Map transactionDate (use CreatedDate, CompletedDate, or LastModifiedDate)
        String dateField = null;
        if (preOrder.has("CompletedDate") && !preOrder.get("CompletedDate").isNull()) {
            dateField = preOrder.get("CompletedDate").asText();
        } else if (preOrder.has("CreatedDate") && !preOrder.get("CreatedDate").isNull()) {
            dateField = preOrder.get("CreatedDate").asText();
        } else if (preOrder.has("LastModifiedDate") && !preOrder.get("LastModifiedDate").isNull()) {
            dateField = preOrder.get("LastModifiedDate").asText();
        }
        
        if (dateField != null) {
            transaction.put("transactionDate", dateField);
        }
        
        // Map isVoid (false for completed orders)
        transaction.put("isVoid", false);
        
        return transaction;
    }
}

