package heymary.co.integrations.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import heymary.co.integrations.model.Card;
import heymary.co.integrations.model.Customer;
import heymary.co.integrations.model.IntegrationConfig;
import heymary.co.integrations.model.IntegrationType;
import heymary.co.integrations.model.Order;
import heymary.co.integrations.model.RewardTier;
import heymary.co.integrations.model.SyncLog;
import heymary.co.integrations.util.RewardTierMatcher;
import heymary.co.integrations.repository.CardRepository;
import heymary.co.integrations.repository.CustomerRepository;
import heymary.co.integrations.repository.IntegrationConfigRepository;
import heymary.co.integrations.repository.OrderRepository;
import heymary.co.integrations.repository.RewardTierRepository;
import heymary.co.integrations.repository.SyncLogRepository;
import heymary.co.integrations.repository.TemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service for processing Treez webhook events
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TreezWebhookService {

    private final SyncLogRepository syncLogRepository;
    private final CustomerRepository customerRepository;
    private final CardRepository cardRepository;
    private final OrderRepository orderRepository;
    private final IntegrationConfigRepository integrationConfigRepository;
    private final BoomerangmeApiClient boomerangmeApiClient;
    private final TemplateService templateService;
    private final TemplateRepository templateRepository;
    private final RewardTierRepository rewardTierRepository;
    private final ObjectMapper objectMapper;
    private final DeadLetterQueueService deadLetterQueueService;

    // Points calculation: 1 point per dollar spent (1:1 ratio)
    private static final double POINTS_PER_DOLLAR = 1.0;

    /**
     * Process CUSTOMER event from Treez
     * Flow: Treez customer created/updated → Create/update Boomerangme card
     */
    @Async("syncTaskExecutor")
    @Transactional
    public void processCustomerEvent(IntegrationConfig config, JsonNode eventData) {
        String merchantId = config.getMerchantId();
        log.info("Processing Treez CUSTOMER event for merchant: {}", merchantId);
        
        try {
            // Extract customer data from the event
            JsonNode data = eventData.has("data") ? eventData.get("data") : eventData;
            
            log.info("--- Customer Event Data ---");
            String prettyData = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
            log.info("Customer data:\n{}", prettyData);
            
            // Extract customer information
            String treezCustomerId = extractCustomerId(data);
            String email = extractField(data, "email", "customer_email", "emailAddress");
            String phone = extractField(data, "phone", "phone_number", "phoneNumber");
            // Default to US country code (1) when Treez sends phone without country code
            phone = normalizePhoneForBoomerangme(phone);
            String firstName = extractField(data, "first_name", "firstName", "fname");
            String lastName = extractField(data, "last_name", "lastName", "lname");
            
            log.info("Extracted: customerId={}, email={}, phone={}, name={} {}", 
                    treezCustomerId, email, phone, firstName, lastName);
            
            // Validation: Need at least email or phone
            if ((email == null || email.isEmpty()) && (phone == null || phone.isEmpty())) {
                log.error("Customer has no email or phone - cannot create Boomerangme card");
                throw new RuntimeException("Customer must have email or phone number");
            }
            
            // Step 1: Check if customer already exists in our database
            Optional<Customer> existingCustomer = findCustomerByTreezId(merchantId, treezCustomerId);
            
            if (existingCustomer.isEmpty()) {
                // Customer doesn't exist by Treez ID, check by email/phone (deduplication)
                existingCustomer = findCustomerByEmailOrPhone(merchantId, email, phone);
                
                if (existingCustomer.isPresent()) {
                    log.info("Found existing customer by email/phone match - linking to Treez customer {}", treezCustomerId);
                    Customer customer = existingCustomer.get();
                    customer.setExternalCustomerId(treezCustomerId);
                    customer.setSyncedAt(LocalDateTime.now());
                    customerRepository.save(customer);
                    log.info("Linked existing customer {} to Treez customer {}", customer.getId(), treezCustomerId);
                }
            }
            
            if (existingCustomer.isPresent()) {
                // Customer exists - update information
                log.info("Updating existing customer");
                updateCustomerFromTreezData(existingCustomer.get(), data, treezCustomerId, email, phone, firstName, lastName);
            } else {
                // New customer - create Boomerangme card and link
                log.info("Creating new customer and Boomerangme card");
                createCustomerWithBoomerangmeCard(config, treezCustomerId, email, phone, firstName, lastName, data);
            }
            
            createSyncLog(merchantId, SyncLog.SyncType.CUSTOMER, treezCustomerId,
                    SyncLog.SyncStatus.SUCCESS, null, toJsonString(eventData),
                    "{\"status\":\"success\",\"message\":\"Customer synchronized successfully\"}");
            
        } catch (Exception e) {
            log.error("Error processing Treez CUSTOMER event for merchant {}: {}", 
                    merchantId, e.getMessage(), e);
            
            String customerId = "unknown";
            try {
                customerId = extractCustomerId(eventData.has("data") ? eventData.get("data") : eventData);
            } catch (Exception ignored) {}
            
            createSyncLog(merchantId, SyncLog.SyncType.CUSTOMER, customerId,
                    SyncLog.SyncStatus.FAILED, e.getMessage(), toJsonString(eventData), null);
            
            deadLetterQueueService.addToDeadLetterQueue(
                    merchantId,
                    DeadLetterQueueService.SyncType.CUSTOMER,
                    DeadLetterQueueService.EntityType.CUSTOMER,
                    customerId,
                    e.getMessage(),
                    toJsonString(eventData)
            );
        }
    }

    /**
     * Process PRODUCT event from Treez
     * This is triggered when product/inventory changes occur
     */
    @Async("syncTaskExecutor")
    @Transactional
    public void processProductEvent(IntegrationConfig config, JsonNode eventData) {
        String merchantId = config.getMerchantId();
        log.info("Processing Treez PRODUCT event for merchant: {}", merchantId);
        
        try {
            JsonNode data = eventData.has("data") ? eventData.get("data") : eventData;
            
            log.info("--- Product Event Data ---");
            String prettyData = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
            log.info("Product data:\n{}", prettyData);
            
            // Log all available fields
            StringBuilder fields = new StringBuilder();
            data.fieldNames().forEachRemaining(field -> {
                if (fields.length() > 0) fields.append(", ");
                fields.append(field);
            });
            log.info("Available product fields: [{}]", fields.toString());
            
            String productId = extractProductId(data);
            log.info("Extracted product ID: {}", productId);
            
            // TODO: Implement product sync logic if needed
            log.info("INFO: Product events are currently logged but not processed");
            log.info("TODO: Implement product sync if needed for your use case");
            
            createSyncLog(merchantId, SyncLog.SyncType.POINTS, productId,
                    SyncLog.SyncStatus.SUCCESS, null, toJsonString(eventData),
                    "{\"status\":\"success\",\"message\":\"Product event received and logged\"}");
            
        } catch (Exception e) {
            log.error("Error processing Treez PRODUCT event for merchant {}: {}", 
                    merchantId, e.getMessage(), e);
        }
    }

    /**
     * Process TICKET event from Treez
     * This is triggered when orders/transactions occur (ticket = transaction in Treez)
     * Flow: Treez transaction → Calculate points (1:1 with order amount) → Send to Boomerangme → Update customer points
     */
    @Async("syncTaskExecutor")
    @Transactional
    public void processTicketEvent(IntegrationConfig config, JsonNode eventData) {
        String merchantId = config.getMerchantId();
        log.info("Processing Treez TICKET (transaction) event for merchant: {}", merchantId);
        
        try {
            JsonNode data = eventData.has("data") ? eventData.get("data") : eventData;
            
            log.info("--- Ticket/Transaction Event Data ---");
            String prettyData = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
            log.info("Ticket data:\n{}", prettyData);
            
            // Extract ticket ID FIRST - needed for idempotency check
            String ticketId = extractTicketId(data);
            if (ticketId == null || "unknown".equals(ticketId)) {
                log.error("Ticket ID not found in event data");
                throw new RuntimeException("Ticket ID is required");
            }
            log.info("Extracted ticket ID: {}", ticketId);
            
            // Validate order status - only process COMPLETED and PAID orders
            // TICKET_BY_STATUS events can be sent for various status changes
            String orderStatus = data.has("order_status") ? data.get("order_status").asText() : null;
            String paymentStatus = data.has("payment_status") ? data.get("payment_status").asText() : null;
            
            if (orderStatus != null && !orderStatus.equals("COMPLETED")) {
                log.info("Order {} has status '{}', not COMPLETED - skipping sync", ticketId, orderStatus);
                createSyncLog(merchantId, SyncLog.SyncType.ORDER, ticketId,
                        SyncLog.SyncStatus.SUCCESS, null, toJsonString(eventData),
                        String.format("{\"status\":\"skipped\",\"message\":\"Order status is %s, not COMPLETED\"}", orderStatus));
                return;
            }
            
            if (paymentStatus != null && paymentStatus.equals("REFUNDED")) {
                // REFUND: Remove points from Boomerangme for orders that EARNED points.
                // For orders that REDEEMED points: we do NOT give points back to the customer.
                processRefundEvent(config, data, ticketId, merchantId, eventData);
                return;
            }
            
            if (paymentStatus != null && !paymentStatus.equals("PAID")) {
                log.info("Order {} has payment status '{}', not PAID - skipping sync", ticketId, paymentStatus);
                createSyncLog(merchantId, SyncLog.SyncType.ORDER, ticketId,
                        SyncLog.SyncStatus.SUCCESS, null, toJsonString(eventData),
                        String.format("{\"status\":\"skipped\",\"message\":\"Payment status is %s, not PAID\"}", paymentStatus));
                return;
            }
            
            log.info("Order {} validated: status={}, payment_status={}", ticketId, orderStatus, paymentStatus);
            
            // Check if order already synced (idempotency)
            Optional<Order> existingOrder = orderRepository.findByMerchantIdAndExternalOrderIdAndIntegrationType(
                    merchantId, ticketId, IntegrationType.TREEZ);
            if (existingOrder.isPresent() && existingOrder.get().getPointsSynced()) {
                log.warn("Transaction {} already synced, skipping", ticketId);
                return;
            }
            
            // Extract transaction details
            BigDecimal orderTotal = extractOrderTotal(data);
            if (orderTotal == null || orderTotal.compareTo(BigDecimal.ZERO) <= 0) {
                log.info("Order {} has zero or invalid total, skipping sync", ticketId);
                createSyncLog(merchantId, SyncLog.SyncType.ORDER, ticketId,
                        SyncLog.SyncStatus.SUCCESS, null, toJsonString(eventData),
                        "{\"status\":\"skipped\",\"message\":\"Order has zero total\"}");
                return;
            }
            
            // Extract customer ID
            String customerId = extractCustomerId(data);
            if (customerId == null || "unknown".equals(customerId)) {
                log.warn("Order {} has no customer, skipping sync", ticketId);
                createSyncLog(merchantId, SyncLog.SyncType.ORDER, ticketId,
                        SyncLog.SyncStatus.FAILED, "Order has no customer", toJsonString(eventData), null);
                return;
            }
            
            // Parse order date
            LocalDateTime orderDate = extractOrderDate(data);
            
            // Check if this is a redemption order (HM prefix discount)
            // We'll check this after we have the customer and card to match against reward tiers
            boolean isRedemptionOrder = false;
            int redemptionPoints = 0;
            
            int pointsToSync; // Can be positive (earn) or negative (redeem)
            String pointsAction; // "earned" or "redeemed"
            
            if (isRedemptionOrder) {
                // Redemption order: subtract points (don't earn any)
                pointsToSync = -redemptionPoints; // Negative value
                pointsAction = "redeemed";
            } else {
                // Normal order: earn points
                pointsToSync = calculatePoints(orderTotal);
                pointsAction = "earned";
                
                if (pointsToSync <= 0) {
                    log.info("Order {} has no points to earn, skipping sync", ticketId);
                    createSyncLog(merchantId, SyncLog.SyncType.ORDER, ticketId,
                            SyncLog.SyncStatus.SUCCESS, null, toJsonString(eventData),
                            "{\"status\":\"skipped\",\"message\":\"No points to earn\"}");
                    return;
                }
            }
            
            // Find customer
            Optional<Customer> customerOpt = customerRepository
                    .findByMerchantIdAndExternalCustomerIdAndIntegrationType(
                            merchantId, customerId, IntegrationType.TREEZ);
            
            if (customerOpt.isEmpty()) {
                log.warn("Customer {} not found for Treez order {}, skipping sync", customerId, ticketId);
                createSyncLog(merchantId, SyncLog.SyncType.ORDER, ticketId,
                        SyncLog.SyncStatus.FAILED, "Customer not found", toJsonString(eventData), null);
                return;
            }
            
            Customer customer = customerOpt.get();
            
            // Check if customer has a Boomerangme card
            if (customer.getCard() == null) {
                log.info("Customer {} has no Boomerangme card linked, attempting to fetch from Boomerangme", customerId);
                
                // Try to fetch card from Boomerangme based on match type
                Card fetchedCard = fetchBoomerangmeCardForCustomer(config, customer);
                
                if (fetchedCard != null) {
                    String cardIdentifier = fetchedCard.getSerialNumber() != null 
                            ? fetchedCard.getSerialNumber() 
                            : fetchedCard.getCardholderId();
                    log.info("Found and linked Boomerangme card {} to customer {}", cardIdentifier, customerId);
                    customer.setCard(fetchedCard);
                    customerRepository.save(customer);
                } else {
                    log.warn("Customer {} has no Boomerangme card linked and none found in Boomerangme, skipping order sync", customerId);
                    createSyncLog(merchantId, SyncLog.SyncType.ORDER, ticketId,
                            SyncLog.SyncStatus.FAILED, "Customer not linked to Boomerangme card", toJsonString(eventData), null);
                    return;
                }
            }
            
            // Use cardholderId as primary identifier (serialNumber is optional)
            String cardIdentifier = customer.getCard().getSerialNumber() != null 
                    ? customer.getCard().getSerialNumber() 
                    : customer.getCard().getCardholderId();
            log.info("Found customer {} with Boomerangme card {}", customerId, cardIdentifier);
            
            // Check if this is a redemption order (HM prefix discount matching reward tiers)
            Card card = customer.getCard();
            if (card.getTemplateId() != null) {
                redemptionPoints = extractHMRedeemDiscountPoints(data, card.getTemplateId());
                isRedemptionOrder = redemptionPoints > 0;
                if (isRedemptionOrder) {
                    log.info("Order {} is a HM redemption order - deducting {} points (matched reward tier)", 
                             ticketId, redemptionPoints);
                }
            } else {
                log.warn("Card {} has no template ID, cannot check for HM redemption discounts", cardIdentifier);
            }
            
            // Save order to database
            // Note: We already checked for existing order above, so this should be a new order
            // However, wrap in try-catch to handle race conditions where multiple webhooks arrive simultaneously
            Order order;
            try {
                order = Order.builder()
                        .merchantId(merchantId)
                        .integrationType(IntegrationType.TREEZ)
                        .externalOrderId(ticketId)
                        .orderTotal(orderTotal)
                        .orderDate(orderDate)
                        .pointsEarned(pointsToSync)  // Can be positive (earned) or negative (redeemed)
                        .pointsSynced(false)
                        .customer(customer)
                        .build();
                
                order = orderRepository.save(order);
                log.info("Saved order {} to database with {} points ({})", ticketId, Math.abs(pointsToSync), pointsAction);
            } catch (DataIntegrityViolationException e) {
                // Handle race condition: another webhook already created this order
                log.warn("Order {} already exists (duplicate webhook detected) - checking existing order", ticketId);
                Optional<Order> duplicateOrder = orderRepository.findByMerchantIdAndExternalOrderIdAndIntegrationType(
                        merchantId, ticketId, IntegrationType.TREEZ);
                if (duplicateOrder.isPresent()) {
                    log.info("Found existing order {} - skipping duplicate processing", ticketId);
                    createSyncLog(merchantId, SyncLog.SyncType.ORDER, ticketId,
                            SyncLog.SyncStatus.SUCCESS, null, toJsonString(eventData),
                            String.format("{\"status\":\"skipped\",\"message\":\"Order already processed (race condition detected)\",\"order_id\":%d}", duplicateOrder.get().getId()));
                    return;
                } else {
                    // Unexpected error - rethrow
                    throw e;
                }
            }
            
            // Sync points to Boomerangme (send transaction first)
            // Use serialNumber if available, otherwise use cardholderId
            String cardIdForApi = customer.getCard().getSerialNumber() != null 
                    ? customer.getCard().getSerialNumber() 
                    : customer.getCard().getCardholderId();
            
            JsonNode boomerangmeResponse;
            
            if (isRedemptionOrder) {
                // Subtract points for redemption
                String redemptionReason = buildRedemptionReasonMessage(ticketId, orderTotal, redemptionPoints, orderDate);
                log.info("Subtracting {} points from Boomerangme card {} for order {}", 
                         redemptionPoints, cardIdForApi, ticketId);
                
                boomerangmeResponse = boomerangmeApiClient.subtractScoresFromCard(
                        config.getBoomerangmeApiKey(),
                        cardIdForApi,
                        redemptionPoints,  // Positive number
                        redemptionReason
                ).block();
            } else {
                // Add points normally
                String earnReason = buildPointsReasonMessage(ticketId, orderTotal, pointsToSync, orderDate);
                log.info("Adding {} points to Boomerangme card {} for order {}", 
                         pointsToSync, cardIdForApi, ticketId);
                
                boomerangmeResponse = boomerangmeApiClient.addScoresToCard(
                        config.getBoomerangmeApiKey(),
                        cardIdForApi,
                        pointsToSync,
                        earnReason,
                        orderTotal
                ).block();
            }
            
            if (boomerangmeResponse == null) {
                throw new RuntimeException("Null response from Boomerangme API");
            }
            
            if (isRedemptionOrder) {
                log.info("Successfully redeemed {} points from Boomerangme for order {}. Waiting for webhook to update balance.", 
                        redemptionPoints, ticketId);
            } else {
                log.info("Successfully sent {} points to Boomerangme for order {}. Waiting for webhook to update balance.", 
                        pointsToSync, ticketId);
            }
            
            // Update order as synced (points sent to Boomerangme)
            // Note: We don't update customer/card points here - wait for CardBalanceUpdatedEvent webhook
            order.setPointsSynced(false);
            order.setSyncedAt(LocalDateTime.now());
            orderRepository.save(order);
            
            // Create success sync log
            if (isRedemptionOrder) {
                createSyncLog(merchantId, SyncLog.SyncType.ORDER, ticketId,
                        SyncLog.SyncStatus.SUCCESS, null, toJsonString(eventData),
                        String.format("{\"points_redeemed\": %d, \"order_total\": %.2f, \"redemption\": true}", 
                                redemptionPoints, orderTotal));
                
                log.info("Successfully synced Treez redemption transaction {} - {} points redeemed", 
                         ticketId, redemptionPoints);
            } else {
                createSyncLog(merchantId, SyncLog.SyncType.ORDER, ticketId,
                        SyncLog.SyncStatus.SUCCESS, null, toJsonString(eventData),
                        String.format("{\"points_earned\": %d, \"order_total\": %.2f}", pointsToSync, orderTotal));
                
                log.info("Successfully synced Treez transaction {} - {} points earned (1:1 ratio)", 
                         ticketId, pointsToSync);
            }
            
        } catch (Exception e) {
            log.error("Error processing Treez TICKET event for merchant {}: {}", 
                    merchantId, e.getMessage(), e);
            
            String ticketId = "unknown";
            try {
                ticketId = extractTicketId(eventData.has("data") ? eventData.get("data") : eventData);
            } catch (Exception ignored) {}
            
            createSyncLog(merchantId, SyncLog.SyncType.ORDER, ticketId,
                    SyncLog.SyncStatus.FAILED, e.getMessage(), toJsonString(eventData), null);
            
            deadLetterQueueService.addToDeadLetterQueue(
                    merchantId,
                    DeadLetterQueueService.SyncType.ORDER,
                    DeadLetterQueueService.EntityType.ORDER,
                    ticketId,
                    e.getMessage(),
                    toJsonString(eventData)
            );
        }
    }

    /**
     * Process TICKET_STATUS event from Treez
     * This is a minimal status update event that only contains ticket_id and order_status.
     * Since it doesn't contain full order data (items, totals, customer, etc.), we can't process points.
     * We check if the order already exists (processed by TICKET or TICKET_BY_STATUS) and skip if so.
     */
    @Async("syncTaskExecutor")
    @Transactional
    public void processTicketStatusEvent(IntegrationConfig config, JsonNode eventData) {
        String merchantId = config.getMerchantId();
        log.info("Processing Treez TICKET_STATUS (status update) event for merchant: {}", merchantId);
        
        try {
            JsonNode data = eventData.has("data") ? eventData.get("data") : eventData;
            
            log.info("--- Ticket Status Event Data ---");
            String prettyData = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
            log.info("Ticket status data:\n{}", prettyData);
            
            // Extract ticket ID
            String ticketId = extractTicketId(data);
            if (ticketId == null || "unknown".equals(ticketId)) {
                log.error("Ticket ID not found in TICKET_STATUS event data");
                throw new RuntimeException("Ticket ID is required");
            }
            log.info("Extracted ticket ID: {}", ticketId);
            
            // Extract order status
            String orderStatus = data.has("order_status") ? data.get("order_status").asText() : null;
            log.info("Order {} status: {}", ticketId, orderStatus);
            
            // Check if order already exists (processed by TICKET or TICKET_BY_STATUS event)
            Optional<Order> existingOrder = orderRepository.findByMerchantIdAndExternalOrderIdAndIntegrationType(
                    merchantId, ticketId, IntegrationType.TREEZ);
            
            if (existingOrder.isPresent()) {
                log.info("Order {} already exists in database (processed by TICKET/TICKET_BY_STATUS event) - skipping TICKET_STATUS event", ticketId);
                createSyncLog(merchantId, SyncLog.SyncType.ORDER, ticketId,
                        SyncLog.SyncStatus.SUCCESS, null, toJsonString(eventData),
                        String.format("{\"status\":\"skipped\",\"message\":\"Order already processed by TICKET/TICKET_BY_STATUS event\",\"order_id\":%d}", existingOrder.get().getId()));
                return;
            }
            
            // Order doesn't exist - TICKET_STATUS events don't have enough data to process points
            // We need full order data (items, totals, customer, discounts) which comes in TICKET/TICKET_BY_STATUS events
            log.info("Order {} does not exist yet - TICKET_STATUS event has insufficient data to process points. " +
                    "Waiting for TICKET or TICKET_BY_STATUS event with full order data.", ticketId);
            createSyncLog(merchantId, SyncLog.SyncType.ORDER, ticketId,
                    SyncLog.SyncStatus.SUCCESS, null, toJsonString(eventData),
                    String.format("{\"status\":\"skipped\",\"message\":\"TICKET_STATUS event has insufficient data - waiting for TICKET/TICKET_BY_STATUS event\",\"order_status\":\"%s\"}", orderStatus));
            
        } catch (Exception e) {
            log.error("Error processing Treez TICKET_STATUS event for merchant {}: {}", 
                    merchantId, e.getMessage(), e);
            
            String ticketId = "unknown";
            try {
                ticketId = extractTicketId(eventData.has("data") ? eventData.get("data") : eventData);
            } catch (Exception ignored) {}
            
            createSyncLog(merchantId, SyncLog.SyncType.ORDER, ticketId,
                    SyncLog.SyncStatus.FAILED, e.getMessage(), toJsonString(eventData), null);
        }
    }

    /**
     * Process REFUND event: Remove points from Boomerangme for orders that EARNED points.
     * For orders that REDEEMED points: we do NOT give points back to the customer.
     * 
     * Treez sends refund as a separate ticket with:
     * - payment_status: REFUNDED
     * - original_ticket_id: the original order being refunded
     * - total: negative amount
     */
    private void processRefundEvent(IntegrationConfig config, JsonNode data, String refundTicketId,
            String merchantId, JsonNode eventData) {
        log.info("Processing Treez REFUND event for merchant: {}, refund ticket: {}", merchantId, refundTicketId);
        
        try {
            // Idempotency: don't process same refund twice
            String refundEntityId = "refund:" + refundTicketId;
            if (syncLogRepository.existsByMerchantIdAndEntityId(merchantId, refundEntityId)) {
                log.info("Refund {} already processed, skipping", refundTicketId);
                createSyncLog(merchantId, SyncLog.SyncType.ORDER, refundEntityId,
                        SyncLog.SyncStatus.SUCCESS, null, toJsonString(eventData),
                        "{\"status\":\"skipped\",\"message\":\"Refund already processed\"}");
                return;
            }
            
            // Extract original order ID (the order being refunded)
            String originalTicketId = extractField(data, "original_ticket_id", "originalTicketId");
            if (originalTicketId == null || originalTicketId.isEmpty()) {
                log.warn("Refund {} has no original_ticket_id, cannot process", refundTicketId);
                createSyncLog(merchantId, SyncLog.SyncType.ORDER, refundEntityId,
                        SyncLog.SyncStatus.SUCCESS, null, toJsonString(eventData),
                        "{\"status\":\"skipped\",\"message\":\"Refund has no original_ticket_id\"}");
                return;
            }
            
            // Look up the original order
            Optional<Order> originalOrderOpt = orderRepository.findByMerchantIdAndExternalOrderIdAndIntegrationType(
                    merchantId, originalTicketId, IntegrationType.TREEZ);
            
            if (originalOrderOpt.isEmpty()) {
                log.info("Original order {} not found for refund {} - may not have been synced, skipping", 
                        originalTicketId, refundTicketId);
                createSyncLog(merchantId, SyncLog.SyncType.ORDER, refundEntityId,
                        SyncLog.SyncStatus.SUCCESS, null, toJsonString(eventData),
                        String.format("{\"status\":\"skipped\",\"message\":\"Original order %s not found\"}", originalTicketId));
                return;
            }
            
            Order originalOrder = originalOrderOpt.get();
            int pointsEarned = originalOrder.getPointsEarned() != null ? originalOrder.getPointsEarned() : 0;
            
            // REDEEMED orders (pointsEarned < 0): we do NOT give points back to the customer
            if (pointsEarned <= 0) {
                log.info("Refund {} for original order {} - order had redeemed points ({}), not giving points back", 
                        refundTicketId, originalTicketId, pointsEarned);
                createSyncLog(merchantId, SyncLog.SyncType.ORDER, refundEntityId,
                        SyncLog.SyncStatus.SUCCESS, null, toJsonString(eventData),
                        String.format("{\"status\":\"skipped\",\"message\":\"Redeemed order - points not restored\",\"original_order\":\"%s\",\"points_earned\":%d}", 
                                originalTicketId, pointsEarned));
                return;
            }
            
            // EARNED order: remove points from Boomerangme
            Customer customer = originalOrder.getCustomer();
            if (customer == null || customer.getCard() == null) {
                log.warn("Refund {} - customer or card not found for original order {}, skipping", 
                        refundTicketId, originalTicketId);
                createSyncLog(merchantId, SyncLog.SyncType.ORDER, refundEntityId,
                        SyncLog.SyncStatus.FAILED, "Customer or card not found", toJsonString(eventData), null);
                return;
            }
            
            String cardIdForApi = customer.getCard().getSerialNumber() != null 
                    ? customer.getCard().getSerialNumber() 
                    : customer.getCard().getCardholderId();
            
            String refundReason = String.format("Refund for order #%s - %d points removed", 
                    originalTicketId.length() > 8 ? originalTicketId.substring(0, 8) : originalTicketId, 
                    pointsEarned);
            
            log.info("Removing {} points from Boomerangme card {} for refund {} (original order {})", 
                    pointsEarned, cardIdForApi, refundTicketId, originalTicketId);
            
            JsonNode boomerangmeResponse = boomerangmeApiClient.subtractScoresFromCard(
                    config.getBoomerangmeApiKey(),
                    cardIdForApi,
                    pointsEarned,
                    refundReason
            ).block();
            
            if (boomerangmeResponse == null) {
                throw new RuntimeException("Null response from Boomerangme API");
            }
            
            log.info("Successfully removed {} points from Boomerangme for refund {} (original order {})", 
                    pointsEarned, refundTicketId, originalTicketId);
            
            createSyncLog(merchantId, SyncLog.SyncType.ORDER, refundEntityId,
                    SyncLog.SyncStatus.SUCCESS, null, toJsonString(eventData),
                    String.format("{\"status\":\"success\",\"points_removed\":%d,\"original_order\":\"%s\"}", 
                            pointsEarned, originalTicketId));
            
        } catch (Exception e) {
            log.error("Error processing Treez REFUND event for merchant: {}, refund: {}: {}", 
                    merchantId, refundTicketId, e.getMessage(), e);
            
            String refundEntityId = "refund:" + refundTicketId;
            createSyncLog(merchantId, SyncLog.SyncType.ORDER, refundEntityId,
                    SyncLog.SyncStatus.FAILED, e.getMessage(), toJsonString(eventData), null);
            
            deadLetterQueueService.addToDeadLetterQueue(
                    merchantId,
                    DeadLetterQueueService.SyncType.ORDER,
                    DeadLetterQueueService.EntityType.ORDER,
                    refundTicketId,
                    "Refund processing failed: " + e.getMessage(),
                    toJsonString(eventData)
            );
        }
    }

    /**
     * Extract customer ID from event data
     * Tries multiple possible field names
     */
    private String extractCustomerId(JsonNode data) {
        if (data.has("customer_id")) {
            return data.get("customer_id").asText();
        } else if (data.has("customerId")) {
            return data.get("customerId").asText();
        } else if (data.has("id")) {
            return data.get("id").asText();
        }
        return "unknown";
    }

    /**
     * Extract product ID from event data
     */
    private String extractProductId(JsonNode data) {
        if (data.has("product_id")) {
            return data.get("product_id").asText();
        } else if (data.has("productId")) {
            return data.get("productId").asText();
        } else if (data.has("id")) {
            return data.get("id").asText();
        }
        return "unknown";
    }

    /**
     * Extract ticket/transaction ID from event data
     */
    private String extractTicketId(JsonNode data) {
        if (data.has("ticket_id")) {
            return data.get("ticket_id").asText();
        } else if (data.has("ticketId")) {
            return data.get("ticketId").asText();
        } else if (data.has("transaction_id")) {
            return data.get("transaction_id").asText();
        } else if (data.has("order_id")) {
            return data.get("order_id").asText();
        } else if (data.has("id")) {
            return data.get("id").asText();
        }
        return "unknown";
    }

    /**
     * Create sync log entry
     */
    private void createSyncLog(String merchantId, SyncLog.SyncType syncType, String entityId,
                               SyncLog.SyncStatus status, String errorMessage, 
                               String requestPayload, String responsePayload) {
        try {
            SyncLog syncLog = SyncLog.builder()
                    .merchantId(merchantId)
                    .syncType(syncType)
                    .entityType(getEntityType(syncType))
                    .entityId(entityId)
                    .sourceSystem(SyncLog.SystemType.TREEZ)
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
        } catch (Exception e) {
            log.error("Failed to create sync log: {}", e.getMessage(), e);
        }
    }

    /**
     * Map sync type to entity type
     */
    private SyncLog.EntityType getEntityType(SyncLog.SyncType syncType) {
        switch (syncType) {
            case CUSTOMER:
                return SyncLog.EntityType.CUSTOMER;
            case ORDER:
                return SyncLog.EntityType.ORDER;
            case POINTS:
                return SyncLog.EntityType.POINTS;
            default:
                return SyncLog.EntityType.CUSTOMER;
        }
    }
    
    /**
     * Convert JsonNode to JSON string safely
     */
    private String toJsonString(JsonNode jsonNode) {
        try {
            String result = objectMapper.writeValueAsString(jsonNode);
            log.debug("Successfully converted JsonNode to JSON string (length: {})", result.length());
            return result;
        } catch (Exception e) {
            log.error("Failed to convert JsonNode to JSON string: {}", e.getMessage(), e);
            return "{}";
        }
    }

    /**
     * Find customer by Treez customer ID
     */
    private Optional<Customer> findCustomerByTreezId(String merchantId, String treezCustomerId) {
        return customerRepository.findByMerchantIdAndExternalCustomerIdAndIntegrationType(
                merchantId, treezCustomerId, IntegrationType.TREEZ);
    }

    /**
     * Find customer by Treez email or phone (for deduplication)
     * Uses Treez-specific fields
     */
    private Optional<Customer> findCustomerByEmailOrPhone(String merchantId, String email, String phone) {
        // Try email first
        if (email != null && !email.isEmpty()) {
            Optional<Customer> byEmail = customerRepository.findByMerchantIdAndTreezEmailAndIntegrationType(
                    merchantId, email, IntegrationType.TREEZ);
            if (byEmail.isPresent()) {
                log.info("Found Treez customer by email: {}", email);
                return byEmail;
            }
        }
        
        // Try phone if email didn't match (try normalized first, then without leading 1 for backward compatibility)
        if (phone != null && !phone.isEmpty()) {
            Optional<Customer> byPhone = customerRepository.findByMerchantIdAndTreezPhoneAndIntegrationType(
                    merchantId, phone, IntegrationType.TREEZ);
            if (byPhone.isPresent()) {
                log.info("Found Treez customer by phone: {}", phone);
                return byPhone;
            }
            // Existing customers may have been stored without US country code (e.g. 6143752923)
            String digitsOnly = phone.replaceAll("[^0-9]", "");
            if (digitsOnly.length() == 11 && digitsOnly.startsWith("1")) {
                Optional<Customer> byPhoneWithoutCountryCode = customerRepository.findByMerchantIdAndTreezPhoneAndIntegrationType(
                        merchantId, digitsOnly.substring(1), IntegrationType.TREEZ);
                if (byPhoneWithoutCountryCode.isPresent()) {
                    log.info("Found Treez customer by phone (without country code): {}", digitsOnly.substring(1));
                    return byPhoneWithoutCountryCode;
                }
            }
        }
        
        return Optional.empty();
    }

    /**
     * Update existing customer from Treez data
     * Uses Treez-specific fields
     */
    private void updateCustomerFromTreezData(Customer customer, JsonNode data, 
            String treezCustomerId, String email, String phone, String firstName, String lastName) {
        
        log.info("Updating customer {}", customer.getId());
        
        // Get integration config for match type
        IntegrationConfig config = integrationConfigRepository
                .findByMerchantIdAndEnabledTrue(customer.getMerchantId())
                .orElse(null);
        
        // Update Treez customer ID if not set
        if (customer.getExternalCustomerId() == null || !customer.getExternalCustomerId().equals(treezCustomerId)) {
            customer.setExternalCustomerId(treezCustomerId);
            log.info("Updated externalCustomerId to {}", treezCustomerId);
        }
        
        // Update Treez customer information (stored separately)
        if (email != null && !email.isEmpty()) {
            customer.setTreezEmail(email);
        }
        if (phone != null && !phone.isEmpty()) {
            customer.setTreezPhone(phone);
        }
        if (firstName != null && !firstName.isEmpty()) {
            customer.setTreezFirstName(firstName);
        }
        if (lastName != null && !lastName.isEmpty()) {
            customer.setTreezLastName(lastName);
        }
        
        // Extract and update birth date if available
        String birthDateStr = extractField(data, "birth_date", "birthDate", "date_of_birth");
        if (birthDateStr != null) {
            try {
                customer.setTreezBirthDate(LocalDate.parse(birthDateStr));
            } catch (Exception e) {
                log.warn("Could not parse birth date: {}", e.getMessage());
            }
        }
        
        // If customer doesn't have a card, try to fetch from Boomerangme based on match type
        if (customer.getCard() == null && config != null) {
            log.info("Customer {} has no card, attempting to fetch from Boomerangme", customer.getId());
            Card fetchedCard = fetchBoomerangmeCardForCustomer(config, customer);
                if (fetchedCard != null) {
                    customer.setCard(fetchedCard);
                    String cardId = fetchedCard.getSerialNumber() != null 
                            ? fetchedCard.getSerialNumber() 
                            : fetchedCard.getCardholderId();
                    log.info("Fetched and linked Boomerangme card {} to customer {}", cardId, customer.getId());
                }
        }
        
        customer.setSyncedAt(LocalDateTime.now());
        customerRepository.save(customer);
        
        log.info("Customer {} updated successfully", customer.getId());
        
        // Note: We don't update Boomerangme card data from Treez data
        // Card data comes from Boomerangme webhooks and is stored in the Card entity
    }

    /**
     * Create new customer in Boomerangme and store locally
     * Cards are NOT created automatically - customers install cards manually via links/QR codes
     */
    private void createCustomerWithBoomerangmeCard(IntegrationConfig config, String treezCustomerId,
            String email, String phone, String firstName, String lastName, JsonNode treezData) {
        
        String merchantId = config.getMerchantId();
        log.info("Creating new customer for Treez ID: {}", treezCustomerId);
        
        // Step 1: Check if Boomerangme customer already exists (by phone)
        // We can check by looking for existing cards with same phone
        // Also fetch from Boomerangme API if not found locally
        Card existingCard = findBoomerangmeCardByPhone(config, phone);
        
        if (existingCard != null) {
            String cardId = existingCard.getSerialNumber() != null 
                    ? existingCard.getSerialNumber() 
                    : existingCard.getCardholderId();
            log.info("Found existing Boomerangme card: {}, linking to Treez customer", cardId);
            
            // Card exists - create customer and link to existing card
            // Store Treez customer data separately
            Customer customer = Customer.builder()
                    .merchantId(merchantId)
                    .integrationType(IntegrationType.TREEZ)
                    .externalCustomerId(treezCustomerId)
                    .card(existingCard)
                    .treezEmail(email)
                    .treezPhone(phone)
                    .treezFirstName(firstName)
                    .treezLastName(lastName)
                    .totalPoints(existingCard.getBonusBalance() != null ? existingCard.getBonusBalance() : 0)
                    .syncedAt(LocalDateTime.now())
                    .build();
            
            // Extract and set birth date if available
            String birthDateStr = extractField(treezData, "birth_date", "birthDate", "date_of_birth");
            if (birthDateStr != null) {
                try {
                    customer.setTreezBirthDate(LocalDate.parse(birthDateStr));
                } catch (Exception e) {
                    log.warn("Could not parse birth date: {}", e.getMessage());
                }
            }
            
            customerRepository.save(customer);
            log.info("Created customer {} and linked to existing card {}", customer.getId(), cardId);
            
        } else {
            // No existing card - create customer in Boomerangme (not card)
            log.info("No existing Boomerangme customer found - creating customer in Boomerangme");
            
            try {
                // Prepare customer creation request according to Boomerangme API v2 format
                Map<String, Object> customerData = new HashMap<>();
                
                // Required: firstName
                if (firstName == null || firstName.isEmpty()) {
                    throw new RuntimeException("First name is required to create Boomerangme customer");
                }
                customerData.put("firstName", firstName);
                
                // Required: phone OR email (at least one)
                if (phone != null && !phone.isEmpty()) {
                    customerData.put("phone", phone);
                }
                if (email != null && !email.isEmpty()) {
                    customerData.put("email", email);
                }
                
                // Optional fields
                if (lastName != null && !lastName.isEmpty()) {
                    customerData.put("surname", lastName);
                }
                
                // Extract and parse birth date if available
                String birthDateStr = extractField(treezData, "birth_date", "birthDate", "date_of_birth");
                if (birthDateStr != null) {
                    // Ensure format is YYYY-MM-DD
                    customerData.put("dateOfBirth", birthDateStr);
                }
                
                // Extract gender if available (0=unknown, 1=male, 2=female)
                String genderStr = extractField(treezData, "gender", "Gender");
                if (genderStr != null) {
                    try {
                        // Try to parse as integer
                        int gender = Integer.parseInt(genderStr);
                        if (gender >= 0 && gender <= 2) {
                            customerData.put("gender", gender);
                        }
                    } catch (NumberFormatException e) {
                        // Try to parse as string
                        String genderLower = genderStr.toLowerCase();
                        if (genderLower.equals("male") || genderLower.equals("m")) {
                            customerData.put("gender", 1);
                        } else if (genderLower.equals("female") || genderLower.equals("f")) {
                            customerData.put("gender", 2);
                        } else {
                            customerData.put("gender", 0); // unknown
                        }
                    }
                } else {
                    customerData.put("gender", 0); // Default to unknown
                }
                
                // Set externalUserId to Treez customer ID for integration tracking
                customerData.put("externalUserId", treezCustomerId);
                
                log.info("Calling Boomerangme API to create customer for: {} (Treez ID: {})", email, treezCustomerId);
                
                // Call Boomerangme API to create customer (NOT card)
                JsonNode customerResponse = boomerangmeApiClient.createCustomer(
                        config.getBoomerangmeApiKey(),
                        customerData
                ).block();  // Block to wait for response in sync flow
                
                if (customerResponse != null) {
                    // Extract customer ID from response
                    // Boomerangme API wraps response in a "data" field: { "code": 201, "data": { "id": "...", ... } }
                    String boomerangmeCustomerId = null;
                    if (customerResponse.has("data") && customerResponse.get("data").has("id")) {
                        boomerangmeCustomerId = customerResponse.get("data").get("id").asText();
                    } else if (customerResponse.has("id")) {
                        // Fallback: check root level (for backwards compatibility)
                        boomerangmeCustomerId = customerResponse.get("id").asText();
                    }
                    
                    if (boomerangmeCustomerId != null) {
                        log.info("Successfully created Boomerangme customer: {} (Treez ID: {})", boomerangmeCustomerId, treezCustomerId);
                        
                        // Parse birth date for local storage
                        LocalDate birthDate = null;
                        if (birthDateStr != null) {
                            try {
                                birthDate = LocalDate.parse(birthDateStr);
                            } catch (Exception e) {
                                log.warn("Could not parse birth date: {}", e.getMessage());
                            }
                        }
                        
                        // Create customer locally (without card - card will be installed manually)
                        // Store Treez customer data separately
                        Customer customer = Customer.builder()
                                .merchantId(merchantId)
                                .integrationType(IntegrationType.TREEZ)
                                .externalCustomerId(treezCustomerId)
                                .treezEmail(email)
                                .treezPhone(phone)
                                .treezFirstName(firstName)
                                .treezLastName(lastName)
                                .treezBirthDate(birthDate)
                                .totalPoints(0) // No points until card is installed
                                .syncedAt(LocalDateTime.now())
                                .build();
                        
                        customerRepository.save(customer);
                        log.info("Created customer {} in local database (Boomerangme customer ID: {})", 
                                customer.getId(), boomerangmeCustomerId);
                        log.info("NOTE: Customer must manually install card via link/QR code. Card will be linked when installed.");
                        
                    } else {
                        // Log the actual response structure for debugging
                        log.error("Customer creation response missing id field. Response structure: {}", customerResponse.toPrettyString());
                        throw new RuntimeException("Customer creation response missing id field. Expected structure: { \"data\": { \"id\": \"...\" } }");
                    }
                } else {
                    throw new RuntimeException("Null response from Boomerangme API");
                }
                
            } catch (Exception e) {
                log.error("Failed to create Boomerangme customer: {}", e.getMessage(), e);
                
                // Create customer locally even if Boomerangme creation failed
                // This allows us to retry later
                // Store Treez customer data separately
                Customer customer = Customer.builder()
                        .merchantId(merchantId)
                        .integrationType(IntegrationType.TREEZ)
                        .externalCustomerId(treezCustomerId)
                        .treezEmail(email)
                        .treezPhone(phone)
                        .treezFirstName(firstName)
                        .treezLastName(lastName)
                        .totalPoints(0)
                        .syncedAt(LocalDateTime.now())
                        .build();
                
                customerRepository.save(customer);
                log.warn("Created customer {} locally but Boomerangme creation failed. Will retry on next sync.", customer.getId());
                
                // Re-throw to trigger dead letter queue
                throw new RuntimeException("Failed to create Boomerangme customer: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Normalize phone number for Boomerangme matching
     * Boomerangme stores US phone numbers with country code "1" prefix
     * e.g., "9999999999" in Treez becomes "19999999999" in Boomerangme
     * 
     * @param phone Phone number from Treez (may or may not have country code)
     * @return Phone number with "1" prefix if it doesn't already start with it
     */
    private String normalizePhoneForBoomerangme(String phone) {
        if (phone == null || phone.isEmpty()) {
            return phone;
        }
        
        // Remove any non-digit characters for comparison
        String digitsOnly = phone.replaceAll("[^0-9]", "");
        
        // If phone doesn't start with "1", add it (US country code)
        if (!digitsOnly.startsWith("1") && digitsOnly.length() == 10) {
            String normalized = "1" + digitsOnly;
            log.debug("Normalized phone {} to {} for Boomerangme matching", phone, normalized);
            return normalized;
        }
        
        // Return original if already has country code or doesn't match expected format
        return phone;
    }

    /**
     * Find Boomerangme card by phone number
     * First checks local database, then fetches from Boomerangme API
     * Normalizes phone number with "1" prefix for Boomerangme format
     */
    private Card findBoomerangmeCardByPhone(IntegrationConfig config, String phone) {
        if (phone == null || phone.isEmpty()) {
            log.warn("Phone number is required for finding Boomerangme card");
            return null;
        }
        
        log.info("Searching for Boomerangme card by phone: {}", phone);
        
        String normalizedPhone = normalizePhoneForBoomerangme(phone);
        
        // Step 1: Check local database first
        if (normalizedPhone != null && !normalizedPhone.isEmpty()) {
            // Try normalized phone first (with "1" prefix)
            Optional<Card> cardByPhone = cardRepository.findByCardholderPhone(normalizedPhone);
            if (cardByPhone.isPresent()) {
                log.info("Found existing Boomerangme card in local DB by phone: {}", normalizedPhone);
                return cardByPhone.get();
            }
            
            // Also try original phone (without prefix) in case card was saved differently
            if (!phone.equals(normalizedPhone)) {
                Optional<Card> cardByOriginalPhone = cardRepository.findByCardholderPhone(phone);
                if (cardByOriginalPhone.isPresent()) {
                    log.info("Found existing Boomerangme card in local DB by original phone: {}", phone);
                    return cardByOriginalPhone.get();
                }
            }
        }
        
        // Step 2: Not found locally - fetch from Boomerangme API
        log.info("Card not found in local DB, fetching from Boomerangme API by phone: {}", normalizedPhone);
        
        try {
            if (normalizedPhone != null && !normalizedPhone.isEmpty()) {
                log.debug("Searching Boomerangme API by phone: {}", normalizedPhone);
                JsonNode cardsResponse = boomerangmeApiClient.searchCardsByPhone(
                        config.getBoomerangmeApiKey(), normalizedPhone
                ).block();
                
                // If found, process and return
                if (cardsResponse != null && cardsResponse.has("data") && 
                    cardsResponse.get("data").isArray() && cardsResponse.get("data").size() > 0) {
                    log.info("Found card in Boomerangme API by phone");
                    return processBoomerangmeCardResponse(config.getMerchantId(), cardsResponse);
                }
            }
            
            log.info("Card not found in Boomerangme API by phone");
            return null;
        } catch (Exception e) {
            log.error("Error fetching cards from Boomerangme API: {}", e.getMessage(), e);
        }
        
        return null;
    }

    /**
     * Process Boomerangme card response and save the first card found
     */
    private Card processBoomerangmeCardResponse(String merchantId, JsonNode cardsResponse) {
        try {
            if (cardsResponse != null && cardsResponse.has("data") && cardsResponse.get("data").isArray()) {
                // Process cards from API response
                Iterator<JsonNode> cardsIterator = cardsResponse.get("data").elements();
                while (cardsIterator.hasNext()) {
                    JsonNode cardData = cardsIterator.next();
                    Card savedCard = saveCardFromBoomerangmeResponse(merchantId, cardData);
                    if (savedCard != null) {
                        String cardId = savedCard.getSerialNumber() != null 
                                ? savedCard.getSerialNumber() 
                                : savedCard.getCardholderId();
                        log.info("Fetched and saved Boomerangme card: {}", cardId);
                        return savedCard;
                    }
                }
            } else if (cardsResponse != null && cardsResponse.isArray()) {
                // Response might be a direct array
                Iterator<JsonNode> cardsIterator = cardsResponse.elements();
                while (cardsIterator.hasNext()) {
                    JsonNode cardData = cardsIterator.next();
                    Card savedCard = saveCardFromBoomerangmeResponse(merchantId, cardData);
                    if (savedCard != null) {
                        String cardId = savedCard.getSerialNumber() != null 
                                ? savedCard.getSerialNumber() 
                                : savedCard.getCardholderId();
                        log.info("Fetched and saved Boomerangme card: {}", cardId);
                        return savedCard;
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error processing Boomerangme card response: {}", e.getMessage(), e);
        }
        return null;
    }

    /**
     * Fetch Boomerangme card for an existing customer based on match type
     * Uses Treez-specific fields (treez_email or treez_phone) based on configuration
     */
    private Card fetchBoomerangmeCardForCustomer(IntegrationConfig config, Customer customer) {
        log.info("Fetching Boomerangme card for customer {} by phone", customer.getId());
        
        // Use Treez phone for matching
        String phone = customer.getTreezPhone();
        
        return findBoomerangmeCardByPhone(config, phone);
    }

    /**
     * Save card from Boomerangme API response to local database
     * Uses cardholder_id as primary identifier (serial_number is optional)
     * Handles both webhook format (cardholder_id, serial_number) and API response format (customerId, id)
     */
    private Card saveCardFromBoomerangmeResponse(String merchantId, JsonNode cardData) {
        try {
            // cardholder_id is required - it's the primary identifier
            // API response uses "customerId" or "customer.id", webhook uses "cardholder_id"
            String cardholderId = null;
            if (cardData.has("cardholder_id") && !cardData.get("cardholder_id").isNull()) {
                cardholderId = cardData.get("cardholder_id").asText();
            } else if (cardData.has("customerId") && !cardData.get("customerId").isNull()) {
                cardholderId = cardData.get("customerId").asText();
            } else if (cardData.has("customer") && cardData.get("customer").has("id") 
                    && !cardData.get("customer").get("id").isNull()) {
                cardholderId = cardData.get("customer").get("id").asText();
            }
            
            if (cardholderId == null || cardholderId.isEmpty()) {
                log.warn("Card data missing cardholder_id/customerId, skipping");
                return null;
            }
            
            // serial_number is optional - may not be present in API responses
            // API response uses "id", webhook uses "serial_number"
            String serialNumber = null;
            if (cardData.has("serial_number") && !cardData.get("serial_number").isNull()) {
                serialNumber = cardData.get("serial_number").asText();
            } else if (cardData.has("id") && !cardData.get("id").isNull()) {
                serialNumber = cardData.get("id").asText();
            }
            
            // Check if card already exists by cardholder_id (primary identifier)
            Optional<Card> existingCard = cardRepository.findByCardholderId(cardholderId);
            Card card;
            
            if (existingCard.isPresent()) {
                card = existingCard.get();
                log.info("Card with cardholder_id {} already exists, updating", cardholderId);
            } else {
                // Also check by serial_number if provided (for backward compatibility)
                if (serialNumber != null && !serialNumber.isEmpty()) {
                    Optional<Card> existingBySerial = cardRepository.findBySerialNumber(serialNumber);
                    if (existingBySerial.isPresent()) {
                        card = existingBySerial.get();
                        log.info("Card with serial_number {} already exists, updating", serialNumber);
                    } else {
                        card = Card.builder()
                                .merchantId(merchantId)
                                .serialNumber(serialNumber)
                                .cardholderId(cardholderId)
                                .build();
                    }
                } else {
                    card = Card.builder()
                            .merchantId(merchantId)
                            .cardholderId(cardholderId)
                            .build();
                }
            }
            
            // Get config for template sync and default template
            IntegrationConfig config = integrationConfigRepository.findByMerchantId(merchantId)
                    .orElseThrow(() -> new RuntimeException("Integration config not found for merchant: " + merchantId));
            
            // Update card fields from API response (this will set template_id if present in data)
            updateCardFromBoomerangmeResponse(card, cardData);
            
            // Ensure template_id is set (use default if not in API response)
            if (card.getTemplateId() == null) {
                if (config.getDefaultTemplateId() != null) {
                    card.setTemplateId(config.getDefaultTemplateId());
                    log.info("Using default template {} for card {} (not in API response)", config.getDefaultTemplateId(), card.getCardholderId());
                } else {
                    throw new RuntimeException("Cannot save card: template_id is required but not provided and no default_template_id in config for merchant: " + merchantId);
                }
            }
            
            // Ensure template exists before saving card (required for foreign key)
            ensureTemplateExists(merchantId, card.getTemplateId(), config);
            
            card = cardRepository.save(card);
            log.info("Saved card (cardholder_id: {}, serial_number: {}) to database", cardholderId, serialNumber);
            
            return card;
        } catch (Exception e) {
            log.error("Error saving card from Boomerangme response: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Update card entity from Boomerangme API response
     * Handles both webhook format (cardholder_*) and API response format (customer.*, customFields)
     */
    private void updateCardFromBoomerangmeResponse(Card card, JsonNode cardData) {
        // Update serial_number if present and not already set
        // API response uses "id", webhook uses "serial_number"
        if (card.getSerialNumber() == null || card.getSerialNumber().isEmpty()) {
            if (cardData.has("serial_number") && !cardData.get("serial_number").isNull()) {
                card.setSerialNumber(cardData.get("serial_number").asText());
            } else if (cardData.has("id") && !cardData.get("id").isNull()) {
                card.setSerialNumber(cardData.get("id").asText());
            }
        }
        
        // Card type - API uses "type", webhook uses "card_type"
        if (cardData.has("card_type") && !cardData.get("card_type").isNull()) {
            card.setCardType(cardData.get("card_type").asText());
        } else if (cardData.has("type") && !cardData.get("type").isNull()) {
            card.setCardType(cardData.get("type").asText());
        }
        
        // Template ID - API uses "templateId", webhook uses "template_id"
        // Just extract template_id from API data - default template logic is handled in saveCardFromBoomerangmeResponse
        if (cardData.has("template_id") && !cardData.get("template_id").isNull()) {
            try {
                card.setTemplateId(cardData.get("template_id").asInt());
            } catch (Exception e) {
                log.warn("Could not parse template_id as integer: {}", cardData.get("template_id").asText());
                // Leave null - will be set to default in saveCardFromBoomerangmeResponse
            }
        } else if (cardData.has("templateId") && !cardData.get("templateId").isNull()) {
            try {
                card.setTemplateId(cardData.get("templateId").asInt());
            } catch (Exception e) {
                log.warn("Could not parse templateId as integer: {}", cardData.get("templateId").asText());
                // Leave null - will be set to default in saveCardFromBoomerangmeResponse
            }
        }
        // If no template_id in API response, leave null - will be set to default in saveCardFromBoomerangmeResponse
        
        // Extract customer data - handle both webhook format and API response format
        JsonNode customerNode = null;
        if (cardData.has("customer") && !cardData.get("customer").isNull()) {
            customerNode = cardData.get("customer");
        }
        
        // Email - webhook: cardholder_email, API: customer.email or customFields
        if (cardData.has("cardholder_email") && !cardData.get("cardholder_email").isNull()) {
            card.setCardholderEmail(cardData.get("cardholder_email").asText());
        } else if (customerNode != null && customerNode.has("email") && !customerNode.get("email").isNull()) {
            card.setCardholderEmail(customerNode.get("email").asText());
        } else {
            // Try customFields for email
            String email = extractFromCustomFields(cardData, "email");
            if (email != null) {
                card.setCardholderEmail(email);
            }
        }
        
        // Phone - webhook: cardholder_phone, API: customer.phone or customFields
        if (cardData.has("cardholder_phone") && !cardData.get("cardholder_phone").isNull()) {
            card.setCardholderPhone(cardData.get("cardholder_phone").asText());
        } else if (customerNode != null && customerNode.has("phone") && !customerNode.get("phone").isNull()) {
            card.setCardholderPhone(customerNode.get("phone").asText());
        } else {
            // Try customFields for phone
            String phone = extractFromCustomFields(cardData, "phone");
            if (phone != null) {
                card.setCardholderPhone(phone);
            }
        }
        
        // First name - webhook: cardholder_first_name, API: customer.firstName or customFields
        if (cardData.has("cardholder_first_name") && !cardData.get("cardholder_first_name").isNull()) {
            card.setCardholderFirstName(cardData.get("cardholder_first_name").asText());
        } else if (customerNode != null && customerNode.has("firstName") && !customerNode.get("firstName").isNull()) {
            card.setCardholderFirstName(customerNode.get("firstName").asText());
        } else {
            // Try customFields for first name
            String firstName = extractFromCustomFields(cardData, "FName");
            if (firstName != null) {
                card.setCardholderFirstName(firstName);
            }
        }
        
        // Last name - webhook: cardholder_last_name, API: customer.surname or customFields
        if (cardData.has("cardholder_last_name") && !cardData.get("cardholder_last_name").isNull()) {
            card.setCardholderLastName(cardData.get("cardholder_last_name").asText());
        } else if (customerNode != null && customerNode.has("surname") && !customerNode.get("surname").isNull()) {
            card.setCardholderLastName(customerNode.get("surname").asText());
        } else {
            // Try customFields for last name
            String lastName = extractFromCustomFields(cardData, "SName");
            if (lastName != null) {
                card.setCardholderLastName(lastName);
            }
        }
        
        // Birth date - webhook: cardholder_birth_date, API: customer.dateOfBirth or customFields
        if (cardData.has("cardholder_birth_date") && !cardData.get("cardholder_birth_date").isNull()) {
            try {
                String birthDateStr = cardData.get("cardholder_birth_date").asText();
                card.setCardholderBirthDate(LocalDate.parse(birthDateStr));
            } catch (Exception e) {
                log.warn("Failed to parse birth date: {}", e.getMessage());
            }
        } else if (customerNode != null && customerNode.has("dateOfBirth") && !customerNode.get("dateOfBirth").isNull()) {
            try {
                String birthDateStr = customerNode.get("dateOfBirth").asText();
                card.setCardholderBirthDate(LocalDate.parse(birthDateStr));
            } catch (Exception e) {
                log.warn("Failed to parse birth date: {}", e.getMessage());
            }
        } else {
            // Try customFields for birth date
            String birthDate = extractFromCustomFields(cardData, "DateOfBirth");
            if (birthDate != null) {
                try {
                    card.setCardholderBirthDate(LocalDate.parse(birthDate));
                } catch (Exception e) {
                    log.warn("Failed to parse birth date from customFields: {}", e.getMessage());
                }
            }
        }
        
        String licenseExp = extractFromCustomFields(cardData, "date");
        if (licenseExp != null) {
            try {
                card.setCardholderLicenseExpiration(LocalDate.parse(licenseExp));
            } catch (Exception e) {
                log.warn("Failed to parse license expiration date from customFields: {}", e.getMessage());
            }
        }
        // Balance - webhook: bonus_balance, API: balance.bonusBalance
        if (cardData.has("bonus_balance") && !cardData.get("bonus_balance").isNull()) {
            card.setBonusBalance(cardData.get("bonus_balance").asInt());
        } else if (cardData.has("balance") && cardData.get("balance").has("bonusBalance") 
                && !cardData.get("balance").get("bonusBalance").isNull()) {
            card.setBonusBalance(cardData.get("balance").get("bonusBalance").asInt());
        }
        
        // Count visits
        if (cardData.has("count_visits") && !cardData.get("count_visits").isNull()) {
            card.setCountVisits(cardData.get("count_visits").asInt());
        } else if (cardData.has("countVisits") && !cardData.get("countVisits").isNull()) {
            card.setCountVisits(cardData.get("countVisits").asInt());
        }
        
        // Other balance fields
        if (cardData.has("balance") && !cardData.get("balance").isNull() && !cardData.get("balance").isObject()) {
            card.setBalance(cardData.get("balance").asInt());
        } else if (cardData.has("balance") && cardData.get("balance").has("balance") 
                && !cardData.get("balance").get("balance").isNull()) {
            card.setBalance(cardData.get("balance").get("balance").asInt());
        }
        
        if (cardData.has("number_stamps_total") && !cardData.get("number_stamps_total").isNull()) {
            card.setNumberStampsTotal(cardData.get("number_stamps_total").asInt());
        } else if (cardData.has("balance") && cardData.get("balance").has("numberStampsTotal") 
                && !cardData.get("balance").get("numberStampsTotal").isNull()) {
            card.setNumberStampsTotal(cardData.get("balance").get("numberStampsTotal").asInt());
        }
        
        if (cardData.has("number_rewards_unused") && !cardData.get("number_rewards_unused").isNull()) {
            card.setNumberRewardsUnused(cardData.get("number_rewards_unused").asInt());
        } else if (cardData.has("balance") && cardData.get("balance").has("numberRewardsUnused") 
                && !cardData.get("balance").get("numberRewardsUnused").isNull()) {
            card.setNumberRewardsUnused(cardData.get("balance").get("numberRewardsUnused").asInt());
        }
        
        // Status - API uses "status", webhook uses "status"
        if (cardData.has("status") && !cardData.get("status").isNull()) {
            card.setStatus(cardData.get("status").asText());
        }
        
        // Device type - API uses "device", webhook uses "device_type"
        if (cardData.has("device_type") && !cardData.get("device_type").isNull()) {
            card.setDeviceType(cardData.get("device_type").asText());
        } else if (cardData.has("device") && !cardData.get("device").isNull()) {
            card.setDeviceType(cardData.get("device").asText());
        }
        
        // Links - API uses different structure
        if (cardData.has("short_link") && !cardData.get("short_link").isNull()) {
            card.setShortLink(cardData.get("short_link").asText());
        }
        
        if (cardData.has("share_link") && !cardData.get("share_link").isNull()) {
            card.setShareLink(cardData.get("share_link").asText());
        } else if (cardData.has("shareLink") && !cardData.get("shareLink").isNull()) {
            card.setShareLink(cardData.get("shareLink").asText());
        }
        
        // Install links - API uses directInstallLink object
        if (cardData.has("install_link_universal") && !cardData.get("install_link_universal").isNull()) {
            card.setInstallLinkUniversal(cardData.get("install_link_universal").asText());
        } else if (cardData.has("directInstallLink") && cardData.get("directInstallLink").has("universal") 
                && !cardData.get("directInstallLink").get("universal").isNull()) {
            card.setInstallLinkUniversal(cardData.get("directInstallLink").get("universal").asText());
        }
        
        if (cardData.has("install_link_apple") && !cardData.get("install_link_apple").isNull()) {
            card.setInstallLinkApple(cardData.get("install_link_apple").asText());
        } else if (cardData.has("directInstallLink") && cardData.get("directInstallLink").has("apple") 
                && !cardData.get("directInstallLink").get("apple").isNull()) {
            card.setInstallLinkApple(cardData.get("directInstallLink").get("apple").asText());
        }
        
        if (cardData.has("install_link_google") && !cardData.get("install_link_google").isNull()) {
            card.setInstallLinkGoogle(cardData.get("install_link_google").asText());
        } else if (cardData.has("directInstallLink") && cardData.get("directInstallLink").has("google") 
                && !cardData.get("directInstallLink").get("google").isNull()) {
            card.setInstallLinkGoogle(cardData.get("directInstallLink").get("google").asText());
        }
        
        if (cardData.has("install_link_pwa") && !cardData.get("install_link_pwa").isNull()) {
            card.setInstallLinkPwa(cardData.get("install_link_pwa").asText());
        } else if (cardData.has("directInstallLink") && cardData.get("directInstallLink").has("pwa") 
                && !cardData.get("directInstallLink").get("pwa").isNull()) {
            card.setInstallLinkPwa(cardData.get("directInstallLink").get("pwa").asText());
        }
        
        card.setSyncedAt(LocalDateTime.now());
    }

    /**
     * Extract value from customFields array in API response
     * @param cardData Card data JSON node
     * @param fieldType Type of field to extract (e.g., "phone", "email", "FName", "SName", "DateOfBirth", "date")
     * @return Field value or null if not found
     */
    private String extractFromCustomFields(JsonNode cardData, String fieldType) {
        if (!cardData.has("customFields") || !cardData.get("customFields").isArray()) {
            return null;
        }
        
        for (JsonNode customField : cardData.get("customFields")) {
            if (customField.has("type") && customField.get("type").asText().equalsIgnoreCase(fieldType)) {
                if (customField.has("value") && !customField.get("value").isNull()) {
                    return customField.get("value").asText();
                }
            }
        }
        
        return null;
    }

    /**
     * Update card holder information
     * Note: Card data comes from Boomerangme, so we typically don't update it from Treez data
     * This method is kept for backward compatibility but should not be used for Treez customers
     * @deprecated Card data should come from Boomerangme webhooks, not from Treez
     */
    @Deprecated
    private void updateCardHolderInfo(Card card, String email, String phone, String firstName, String lastName) {
        log.warn("updateCardHolderInfo called - Card data should come from Boomerangme webhooks, not Treez");
        // Card holder info is managed by Boomerangme and synced via webhooks
        // We don't update Boomerangme card data from Treez customer data
    }

    /**
     * Extract field value from JSON, trying multiple possible field names
     */
    private String extractField(JsonNode data, String... fieldNames) {
        for (String fieldName : fieldNames) {
            if (data.has(fieldName) && !data.get(fieldName).isNull()) {
                String value = data.get(fieldName).asText();
                if (value != null && !value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }

    /**
     * Extract order total from ticket data
     * Tries multiple possible field names: total_amount, total, amount, subtotal
     */
    private BigDecimal extractOrderTotal(JsonNode data) {
        // Try total_amount first
        if (data.has("total_amount") && !data.get("total_amount").isNull()) {
            try {
                return new BigDecimal(data.get("total_amount").asText());
            } catch (Exception e) {
                log.warn("Failed to parse total_amount: {}", e.getMessage());
            }
        }
        
        // Try total
        if (data.has("total") && !data.get("total").isNull()) {
            try {
                return new BigDecimal(data.get("total").asText());
            } catch (Exception e) {
                log.warn("Failed to parse total: {}", e.getMessage());
            }
        }
        
        // Try amount
        if (data.has("amount") && !data.get("amount").isNull()) {
            try {
                return new BigDecimal(data.get("amount").asText());
            } catch (Exception e) {
                log.warn("Failed to parse amount: {}", e.getMessage());
            }
        }
        
        // Try subtotal as fallback
        if (data.has("subtotal") && !data.get("subtotal").isNull()) {
            try {
                return new BigDecimal(data.get("subtotal").asText());
            } catch (Exception e) {
                log.warn("Failed to parse subtotal: {}", e.getMessage());
            }
        }
        
        log.warn("Could not extract order total from ticket data");
        return null;
    }

    /**
     * Extract order date from ticket data
     * Tries multiple possible field names and formats
     */
    private LocalDateTime extractOrderDate(JsonNode data) {
        // Try created_at
        if (data.has("created_at") && !data.get("created_at").isNull()) {
            try {
                String dateStr = data.get("created_at").asText();
                return parseDateTime(dateStr);
            } catch (Exception e) {
                log.warn("Failed to parse created_at: {}", e.getMessage());
            }
        }
        
        // Try timestamp
        if (data.has("timestamp") && !data.get("timestamp").isNull()) {
            try {
                String dateStr = data.get("timestamp").asText();
                return parseDateTime(dateStr);
            } catch (Exception e) {
                log.warn("Failed to parse timestamp: {}", e.getMessage());
            }
        }
        
        // Try date
        if (data.has("date") && !data.get("date").isNull()) {
            try {
                String dateStr = data.get("date").asText();
                return parseDateTime(dateStr);
            } catch (Exception e) {
                log.warn("Failed to parse date: {}", e.getMessage());
            }
        }
        
        // Try order_date
        if (data.has("order_date") && !data.get("order_date").isNull()) {
            try {
                String dateStr = data.get("order_date").asText();
                return parseDateTime(dateStr);
            } catch (Exception e) {
                log.warn("Failed to parse order_date: {}", e.getMessage());
            }
        }
        
        log.warn("Could not extract order date from ticket data, using current time");
        return LocalDateTime.now();
    }

    /**
     * Parse date-time string in various formats
     */
    private LocalDateTime parseDateTime(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return LocalDateTime.now();
        }
        
        try {
            // Try ISO 8601 with timezone (e.g., "2024-01-15T10:30:00Z" or "2024-01-15T10:30:00+00:00")
            if (dateStr.contains("T") || dateStr.contains("Z") || dateStr.contains("+") || 
                (dateStr.contains("-") && dateStr.length() > 19)) {
                return OffsetDateTime.parse(dateStr, 
                        java.time.format.DateTimeFormatter.ISO_DATE_TIME)
                        .toLocalDateTime();
            }
            
            // Try simple LocalDateTime format
            return LocalDateTime.parse(dateStr);
        } catch (Exception e) {
            log.warn("Failed to parse date string '{}': {}", dateStr, e.getMessage());
            return LocalDateTime.now();
        }
    }

    /**
     * Calculate points based on order total (1 point per dollar, rounded down)
     */
    private int calculatePoints(BigDecimal orderTotal) {
        return (int) Math.floor(orderTotal.doubleValue() * POINTS_PER_DOLLAR);
    }

    /**
     * Build a beautiful, customer-friendly reason message for adding points
     * Includes thank you message, order details, and points earned
     * Example: "Thank you for your purchase! Order #12345 - $125.50 | Earned 125 points on Dec 14, 2024"
     */
    private String buildPointsReasonMessage(String orderId, BigDecimal orderTotal, int pointsEarned, LocalDateTime orderDate) {
        StringBuilder message = new StringBuilder();
        
        // Thank you message
        message.append("Thank you for your purchase! ");
        
        // Order details with formatting
        message.append(String.format("Order #%s - $%.2f", orderId, orderTotal));
        
        // Points earned with proper pluralization
        if (pointsEarned == 1) {
            message.append(String.format(" | Earned %d point", pointsEarned));
        } else {
            message.append(String.format(" | Earned %d points", pointsEarned));
        }
        
        // Add formatted date if available
        if (orderDate != null) {
            try {
                String formattedDate = orderDate.format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, yyyy"));
                message.append(String.format(" on %s", formattedDate));
            } catch (Exception e) {
                log.debug("Could not format order date: {}", e.getMessage());
            }
        }
        
        return message.toString();
    }

    /**
     * Build customer-friendly redemption message for Boomerangme
     * Example: "Points redeemed! Order #12345678 - $41.00 discount applied on Jan 12, 2026"
     */
    private String buildRedemptionReasonMessage(String orderId, BigDecimal orderTotal, int pointsRedeemed, LocalDateTime orderDate) {
        StringBuilder message = new StringBuilder();
        
        // Redemption message
        message.append("Points redeemed! ");
        
        // Order details with formatting
        String shortOrderId = orderId.length() > 8 ? orderId.substring(0, 8) : orderId;
        message.append(String.format("Order #%s - $%.2f discount applied", shortOrderId, (double) pointsRedeemed));
        
        // Add formatted date if available
        if (orderDate != null) {
            try {
                String formattedDate = orderDate.format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, yyyy"));
                message.append(String.format(" on %s", formattedDate));
            } catch (Exception e) {
                log.debug("Could not format order date: {}", e.getMessage());
            }
        }
        
        return message.toString();
    }

    /**
     * Extract HM redemption discount points from order data by matching discount titles to reward tiers
     * Checks for discounts with titles starting with "HM" prefix
     * Extracts the remaining part after "HM" and matches it against reward tiers from the card's template
     * Returns the THRESHOLD points (points required to unlock the reward) from the matched reward tier
     * 
     * Important: Uses tier.getThreshold() (points cost), NOT tier.getValue() (reward amount)
     * 
     * Example:
     * - Discount: "HM 10$ off"
     * - Matches reward tier: "10$ off" with threshold=50, value=10.00
     * - Returns: 50 points (threshold), not 10 (value)
     * 
     * Treez webhook structure:
     * - data.items[] - array of order items
     * - data.items[].discounts[] - array of discounts applied to each item
     * - discount object has: discount_title (e.g., "HM 10$ off")
     * 
     * @param data Order/ticket data from Treez webhook
     * @param templateId Template ID from the customer's card
     * @return Total redemption points (threshold values) from matched reward tiers, or 0 if no matches found
     */
    private int extractHMRedeemDiscountPoints(JsonNode data, Integer templateId) {
        int totalRedemptionPoints = 0;
        int matchedDiscountCount = 0;
        
        try {
            // Check if items array exists
            if (!data.has("items") || !data.get("items").isArray()) {
                log.debug("No items array found in order data");
                return 0;
            }
            
            // Get all reward tiers for this template
            List<RewardTier> rewardTiers = rewardTierRepository.findByTemplateId(templateId);
            if (rewardTiers.isEmpty()) {
                log.debug("No reward tiers found for template {}", templateId);
                return 0;
            }
            
            log.debug("Found {} reward tiers for template {}", rewardTiers.size(), templateId);
            
            JsonNode items = data.get("items");
            
            // Iterate through each item
            for (JsonNode item : items) {
                if (!item.has("discounts") || !item.get("discounts").isArray()) {
                    continue;
                }
                
                JsonNode discounts = item.get("discounts");
                
                // Iterate through each discount on this item
                for (JsonNode discount : discounts) {
                    // Check if discount has a title starting with "HM"
                    String discountTitle = null;
                    if (discount.has("discount_title") && !discount.get("discount_title").isNull()) {
                        discountTitle = discount.get("discount_title").asText();
                    }
                    
                    if (discountTitle == null || discountTitle.isEmpty()) {
                        continue;
                    }
                    
                    // Use utility method to find matching reward tier
                    Optional<RewardTier> matchedTier = RewardTierMatcher.findMatchingRewardTier(discountTitle, rewardTiers);
                    
                    if (matchedTier.isPresent()) {
                        RewardTier tier = matchedTier.get();
                        // Use threshold (points required to unlock), NOT value (reward amount)
                        int thresholdPoints = tier.getThreshold();
                        totalRedemptionPoints += thresholdPoints;
                        matchedDiscountCount++;
                        
                        log.info("Matched HM discount '{}' to reward tier '{}' - deducting {} points (threshold, not value)", 
                                discountTitle, tier.getName(), thresholdPoints);
                    } else {
                        // Only log if it starts with HM (to avoid spam for non-HM discounts)
                        if (discountTitle.toUpperCase().trim().startsWith("HM")) {
                            log.warn("HM discount '{}' does not match any reward tier for template {}", 
                                    discountTitle, templateId);
                        }
                    }
                }
            }
            
            if (matchedDiscountCount > 0) {
                log.info("Extracted {} HM redemption discount(s) totaling {} points from template {}", 
                         matchedDiscountCount, totalRedemptionPoints, templateId);
                return totalRedemptionPoints;
            } else {
                log.debug("No HM redemption discounts found in order");
                return 0;
            }
            
        } catch (Exception e) {
            log.error("Error extracting HM redemption discounts: {}", e.getMessage(), e);
            return 0;
        }
    }

    /**
     * Ensure template exists in database before saving card.
     * If template doesn't exist, fetch it from Boomerangme API.
     * 
     * @param merchantId Merchant ID
     * @param templateId Template ID to check
     * @param config Integration config (for API key)
     */
    private void ensureTemplateExists(String merchantId, Integer templateId, IntegrationConfig config) {
        if (templateId == null) {
            return;
        }
        
        // Check if template already exists
        if (templateRepository.findByTemplateId(templateId).isPresent()) {
            log.debug("Template {} already exists in database", templateId);
            return;
        }
        
        // Template doesn't exist - fetch it from API
        log.info("Template {} not found in database, fetching from Boomerangme API for merchant: {}", templateId, merchantId);
        try {
            templateService.syncTemplateFromApi(merchantId, templateId);
            log.info("Template {} fetched and synced successfully", templateId);
        } catch (Exception e) {
            log.error("Failed to fetch template {} for merchant {}: {}", templateId, merchantId, e.getMessage(), e);
            throw new RuntimeException("Template " + templateId + " does not exist and could not be fetched from Boomerangme API: " + e.getMessage(), e);
        }
    }
}

