package heymary.co.integrations.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import heymary.co.integrations.model.Customer;
import heymary.co.integrations.model.IntegrationConfig;
import heymary.co.integrations.model.Order;
import heymary.co.integrations.model.SyncLog;
import heymary.co.integrations.repository.CustomerRepository;
import heymary.co.integrations.repository.IntegrationConfigRepository;
import heymary.co.integrations.repository.OrderRepository;
import heymary.co.integrations.repository.SyncLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderSyncService {

    private final BoomerangmeApiClient boomerangmeApiClient;
    private final CustomerRepository customerRepository;
    private final OrderRepository orderRepository;
    private final IntegrationConfigRepository integrationConfigRepository;
    private final SyncLogRepository syncLogRepository;
    private final ObjectMapper objectMapper;
    private final DeadLetterQueueService deadLetterQueueService;

    // Points calculation: 1 point per dollar spent (configurable)
    private static final double POINTS_PER_DOLLAR = 1.0;

    @Async("syncTaskExecutor")
    @Transactional
    public void syncOrderFromDutchie(String merchantId, JsonNode transactionData) {
        log.info("Syncing transaction from Dutchie for merchant: {}", merchantId);
        
        try {
            IntegrationConfig config = integrationConfigRepository
                    .findByMerchantIdAndEnabledTrue(merchantId)
                    .orElseThrow(() -> new RuntimeException("Integration config not found or disabled for merchant: " + merchantId));

            // Transaction uses transactionId (integer) not id
            String dutchieOrderId = transactionData.has("transactionId") 
                    ? String.valueOf(transactionData.get("transactionId").asInt())
                    : (transactionData.has("id") ? transactionData.get("id").asText() : null);
            
            if (dutchieOrderId == null) {
                log.warn("Transaction missing transactionId, skipping sync");
                createSyncLog(merchantId, SyncLog.SyncType.ORDER, "unknown", 
                        SyncLog.SyncStatus.FAILED, "Transaction missing transactionId", transactionData.toString());
                return;
            }
            
            // Check if order already synced (idempotency)
            Optional<Order> existingOrder = orderRepository.findByDutchieOrderId(dutchieOrderId);
            if (existingOrder.isPresent() && existingOrder.get().getPointsSynced()) {
                log.info("Transaction {} already synced, skipping", dutchieOrderId);
                return;
            }

            // Skip voided transactions
            if (transactionData.has("isVoid") && transactionData.get("isVoid").asBoolean()) {
                log.info("Transaction {} is voided, skipping sync", dutchieOrderId);
                return;
            }

            // Extract transaction details - Transaction uses "total" field
            BigDecimal orderTotal = transactionData.has("total") 
                    ? new BigDecimal(transactionData.get("total").asText())
                    : BigDecimal.ZERO;
            
            // CustomerId is an integer in Transaction schema
            String customerId = transactionData.has("customerId") && !transactionData.get("customerId").isNull()
                    ? String.valueOf(transactionData.get("customerId").asInt())
                    : null;
            
            // Parse transaction date - Transaction uses "transactionDate" field (ISO date-time format)
            LocalDateTime orderDate;
            try {
                String dateStr = transactionData.has("transactionDate") 
                        ? transactionData.get("transactionDate").asText()
                        : (transactionData.has("lastModifiedDateUTC") 
                                ? transactionData.get("lastModifiedDateUTC").asText() 
                                : null);
                
                if (dateStr != null && !dateStr.isEmpty()) {
                    // Parse ISO 8601 date-time format (e.g., "2024-01-15T10:30:00Z" or "2024-01-15T10:30:00+00:00")
                    if (dateStr.endsWith("Z")) {
                        orderDate = java.time.OffsetDateTime.parse(dateStr, 
                                java.time.format.DateTimeFormatter.ISO_DATE_TIME)
                                .toLocalDateTime();
                    } else if (dateStr.contains("+") || dateStr.contains("-") && dateStr.length() > 19) {
                        orderDate = java.time.OffsetDateTime.parse(dateStr)
                                .toLocalDateTime();
                    } else {
                        orderDate = LocalDateTime.parse(dateStr);
                    }
                } else {
                    throw new IllegalArgumentException("No date field found");
                }
            } catch (Exception e) {
                log.warn("Failed to parse transaction date, using current time: {}", e.getMessage());
                orderDate = LocalDateTime.now();
            }

            // Calculate points (1 point per dollar, rounded down)
            int pointsEarned = calculatePoints(orderTotal);

            if (pointsEarned <= 0) {
                log.info("Order {} has no points to earn, skipping sync", dutchieOrderId);
                return;
            }

            // Find or create customer
            Customer customer = null;
            if (customerId != null) {
                customer = customerRepository
                        .findByMerchantIdAndDutchieCustomerId(merchantId, customerId)
                        .orElse(null);
                
                if (customer == null || customer.getBoomerangmeCardId() == null) {
                    log.warn("Customer {} not found or not linked to Boomerangme, skipping order sync", customerId);
                    createSyncLog(merchantId, SyncLog.SyncType.ORDER, dutchieOrderId, 
                            SyncLog.SyncStatus.FAILED, "Customer not found or not linked", transactionData.toString());
                    return;
                }
            } else {
                log.warn("Order {} has no customer, skipping sync", dutchieOrderId);
                createSyncLog(merchantId, SyncLog.SyncType.ORDER, dutchieOrderId, 
                        SyncLog.SyncStatus.FAILED, "Order has no customer", transactionData.toString());
                    return;
            }

            // Save order to database
            Order order = existingOrder.orElse(Order.builder()
                    .merchantId(merchantId)
                    .dutchieOrderId(dutchieOrderId)
                    .orderTotal(orderTotal)
                    .orderDate(orderDate)
                    .pointsEarned(pointsEarned)
                    .pointsSynced(false)
                    .build());
            
            order.setCustomer(customer);
            order = orderRepository.save(order);

            // Sync points to Boomerangme
            String reason = String.format("Order #%s - $%.2f", dutchieOrderId, orderTotal);
            
            boomerangmeApiClient.addPoints(
                    config.getBoomerangmeApiKey(),
                    customer.getBoomerangmeCardId(),
                    pointsEarned,
                    reason
            ).block(); // Block since we're in async method

            // Update order as synced
            order.setPointsSynced(true);
            order.setSyncedAt(LocalDateTime.now());
            orderRepository.save(order);

            // Update customer total points
            customer.setTotalPoints(customer.getTotalPoints() + pointsEarned);
            customer.setSyncedAt(LocalDateTime.now());
            customerRepository.save(customer);

            // Create success sync log
            createSyncLog(merchantId, SyncLog.SyncType.ORDER, dutchieOrderId, 
                    SyncLog.SyncStatus.SUCCESS, null, transactionData.toString(), 
                    String.format("{\"points_added\": %d}", pointsEarned));

            log.info("Successfully synced transaction {} with {} points", dutchieOrderId, pointsEarned);

        } catch (Exception e) {
            log.error("Error syncing transaction for merchant {}: {}", merchantId, e.getMessage(), e);
            String transactionId = transactionData.has("transactionId") 
                    ? String.valueOf(transactionData.get("transactionId").asInt())
                    : (transactionData.has("id") ? transactionData.get("id").asText() : "unknown");
            
            createSyncLog(merchantId, SyncLog.SyncType.ORDER, transactionId, 
                    SyncLog.SyncStatus.FAILED, e.getMessage(), transactionData.toString());
            
            deadLetterQueueService.addToDeadLetterQueue(
                    merchantId,
                    DeadLetterQueueService.SyncType.ORDER,
                    DeadLetterQueueService.EntityType.ORDER,
                    transactionId,
                    e.getMessage(),
                    transactionData.toString()
            );
        }
    }

    private int calculatePoints(BigDecimal orderTotal) {
        return (int) Math.floor(orderTotal.doubleValue() * POINTS_PER_DOLLAR);
    }

    private void createSyncLog(String merchantId, SyncLog.SyncType syncType, String entityId,
                               SyncLog.SyncStatus status, String errorMessage, String requestPayload) {
        createSyncLog(merchantId, syncType, entityId, status, errorMessage, requestPayload, null);
    }

    private void createSyncLog(String merchantId, SyncLog.SyncType syncType, String entityId,
                               SyncLog.SyncStatus status, String errorMessage, String requestPayload, 
                               String responsePayload) {
        SyncLog syncLog = SyncLog.builder()
                .merchantId(merchantId)
                .syncType(syncType)
                .entityType(SyncLog.EntityType.ORDER)
                .entityId(entityId)
                .sourceSystem(SyncLog.SystemType.DUTCHIE)
                .targetSystem(SyncLog.SystemType.BOOMERANGME)
                .status(status)
                .errorMessage(errorMessage)
                .requestPayload(requestPayload)
                .responsePayload(responsePayload)
                .build();

        if (status == SyncLog.SyncStatus.SUCCESS) {
            syncLog.setCompletedAt(LocalDateTime.now());
        }

        syncLogRepository.save(syncLog);
    }
}

