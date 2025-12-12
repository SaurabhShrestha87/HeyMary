package heymary.co.integrations.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import heymary.co.integrations.model.Customer;
import heymary.co.integrations.model.IntegrationConfig;
import heymary.co.integrations.model.SyncLog;
import heymary.co.integrations.repository.CustomerRepository;
import heymary.co.integrations.repository.IntegrationConfigRepository;
import heymary.co.integrations.repository.SyncLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerSyncService {

    private final BoomerangmeApiClient boomerangmeApiClient;
    private final DutchieApiClient dutchieApiClient;
    private final CustomerRepository customerRepository;
    private final IntegrationConfigRepository integrationConfigRepository;
    private final SyncLogRepository syncLogRepository;
    private final ObjectMapper objectMapper;
    private final DeadLetterQueueService deadLetterQueueService;

    /**
     * Sync customer from Dutchie to Boomerangme
     */
    @Async("syncTaskExecutor")
    @Transactional
    public void syncCustomerFromDutchie(String merchantId, JsonNode customerData) {
        log.info("Syncing customer from Dutchie for merchant: {}", merchantId);
        
        try {
            IntegrationConfig config = integrationConfigRepository
                    .findByMerchantIdAndEnabledTrue(merchantId)
                    .orElseThrow(() -> new RuntimeException("Integration config not found or disabled for merchant: " + merchantId));

            String dutchieCustomerId = customerData.get("id").asText();
            String email = customerData.has("email") ? customerData.get("email").asText() : null;

            // Check if customer already exists
            Optional<Customer> existingCustomer = customerRepository
                    .findByMerchantIdAndDutchieCustomerId(merchantId, dutchieCustomerId);

            Customer customer;
            if (existingCustomer.isPresent()) {
                customer = existingCustomer.get();
                log.debug("Customer {} already exists, updating", dutchieCustomerId);
            } else {
                customer = Customer.builder()
                        .merchantId(merchantId)
                        .dutchieCustomerId(dutchieCustomerId)
                        .build();
            }

            // Update customer data
            if (email != null) customer.setEmail(email);
            if (customerData.has("phone")) customer.setPhone(customerData.get("phone").asText());
            if (customerData.has("first_name")) customer.setFirstName(customerData.get("first_name").asText());
            if (customerData.has("last_name")) customer.setLastName(customerData.get("last_name").asText());

            // If customer doesn't have Boomerangme card, create one
            if (customer.getBoomerangmeCardId() == null) {
                if (config.getBoomerangmeProgramId() == null) {
                    throw new RuntimeException("Boomerangme program ID not configured for merchant: " + merchantId);
                }

                Map<String, Object> customerDataMap = new HashMap<>();
                if (email != null) customerDataMap.put("email", email);
                if (customer.getPhone() != null) customerDataMap.put("phone", customer.getPhone());
                if (customer.getFirstName() != null) customerDataMap.put("firstName", customer.getFirstName());
                if (customer.getLastName() != null) customerDataMap.put("lastName", customer.getLastName());

                JsonNode cardResponse = boomerangmeApiClient.createCard(
                        config.getBoomerangmeApiKey(),
                        config.getBoomerangmeProgramId(),
                        customerDataMap
                ).block();

                if (cardResponse != null && cardResponse.has("id")) {
                    customer.setBoomerangmeCardId(cardResponse.get("id").asText());
                    log.info("Created Boomerangme card {} for customer {}", customer.getBoomerangmeCardId(), dutchieCustomerId);
                } else {
                    throw new RuntimeException("Failed to create Boomerangme card: invalid response");
                }
            } else {
                // Update existing card
                Map<String, Object> customerDataMap = new HashMap<>();
                if (email != null) customerDataMap.put("email", email);
                if (customer.getPhone() != null) customerDataMap.put("phone", customer.getPhone());
                if (customer.getFirstName() != null) customerDataMap.put("firstName", customer.getFirstName());
                if (customer.getLastName() != null) customerDataMap.put("lastName", customer.getLastName());

                boomerangmeApiClient.updateCard(
                        config.getBoomerangmeApiKey(),
                        customer.getBoomerangmeCardId(),
                        customerDataMap
                ).block();
            }

            customer.setSyncedAt(LocalDateTime.now());
            customer = customerRepository.save(customer);

            createSyncLog(merchantId, SyncLog.SyncType.CUSTOMER, dutchieCustomerId, 
                    SyncLog.SyncStatus.SUCCESS, null, customerData.toString(),
                    String.format("{\"boomerangme_card_id\": \"%s\"}", customer.getBoomerangmeCardId()));

            log.info("Successfully synced customer {} to Boomerangme", dutchieCustomerId);

        } catch (Exception e) {
            log.error("Error syncing customer for merchant {}: {}", merchantId, e.getMessage(), e);
            String customerId = customerData.has("id") ? customerData.get("id").asText() : "unknown";
            
            createSyncLog(merchantId, SyncLog.SyncType.CUSTOMER, customerId, 
                    SyncLog.SyncStatus.FAILED, e.getMessage(), customerData.toString(), null);
            
            deadLetterQueueService.addToDeadLetterQueue(
                    merchantId,
                    DeadLetterQueueService.SyncType.CUSTOMER,
                    DeadLetterQueueService.EntityType.CUSTOMER,
                    customerId,
                    e.getMessage(),
                    customerData.toString()
            );
        }
    }

    /**
     * Sync customer from Boomerangme to Dutchie
     */
    @Async("syncTaskExecutor")
    @Transactional
    public void syncCustomerFromBoomerangme(String merchantId, JsonNode cardData) {
        log.info("Syncing customer from Boomerangme for merchant: {}", merchantId);
        
        try {
            IntegrationConfig config = integrationConfigRepository
                    .findByMerchantIdAndEnabledTrue(merchantId)
                    .orElseThrow(() -> new RuntimeException("Integration config not found or disabled for merchant: " + merchantId));

            // Extract card ID - handle different payload structures
            String boomerangmeCardId;
            if (cardData.has("id")) {
                boomerangmeCardId = cardData.get("id").asText();
            } else if (cardData.has("card") && cardData.get("card").has("id")) {
                boomerangmeCardId = cardData.get("card").get("id").asText();
            } else {
                throw new RuntimeException("Card ID not found in webhook payload");
            }
            
            // Extract customer email - handle different payload structures
            String email = null;
            if (cardData.has("customer") && cardData.get("customer").has("email")) {
                email = cardData.get("customer").get("email").asText();
            } else if (cardData.has("email")) {
                email = cardData.get("email").asText();
            }

            // Find customer by Boomerangme card ID
            Optional<Customer> existingCustomer = customerRepository
                    .findByMerchantIdAndBoomerangmeCardId(merchantId, boomerangmeCardId);

            if (existingCustomer.isEmpty() && email != null) {
                // Try to find by email
                existingCustomer = customerRepository.findByMerchantIdAndEmail(merchantId, email);
            }

            Customer customer;
            if (existingCustomer.isPresent()) {
                customer = existingCustomer.get();
                // Update Dutchie customer
                Map<String, Object> customerDataMap = extractCustomerData(cardData);
                // Set CustomerId for update operation
                customerDataMap.put("CustomerId", Integer.parseInt(customer.getDutchieCustomerId()));

                dutchieApiClient.createOrUpdateCustomer(
                        config.getDutchieAuthHeader(),
                        customerDataMap
                ).block();

                log.info("Updated Dutchie customer {} from Boomerangme", customer.getDutchieCustomerId());
            } else {
                // Create new customer in Dutchie
                Map<String, Object> customerDataMap = extractCustomerData(cardData);
                // Don't set CustomerId (or set to null/0) for create operation
                customerDataMap.put("CustomerId", null);

                JsonNode dutchieCustomerResponse = dutchieApiClient.createOrUpdateCustomer(
                        config.getDutchieAuthHeader(),
                        customerDataMap
                ).block();

                // Customer response uses CustomerId field (integer)
                if (dutchieCustomerResponse != null && dutchieCustomerResponse.has("CustomerId")) {
                    String dutchieCustomerId = String.valueOf(dutchieCustomerResponse.get("CustomerId").asInt());
                    
                    customer = Customer.builder()
                            .merchantId(merchantId)
                            .dutchieCustomerId(dutchieCustomerId)
                            .boomerangmeCardId(boomerangmeCardId)
                            .email(email)
                            .syncedAt(LocalDateTime.now())
                            .build();
                    
                    customer = customerRepository.save(customer);
                    log.info("Created Dutchie customer {} from Boomerangme", dutchieCustomerId);
                } else {
                    throw new RuntimeException("Failed to create Dutchie customer: invalid response");
                }
            }

            createSyncLog(merchantId, SyncLog.SyncType.CUSTOMER, boomerangmeCardId, 
                    SyncLog.SyncStatus.SUCCESS, null, cardData.toString(),
                    String.format("{\"dutchie_customer_id\": \"%s\"}", customer.getDutchieCustomerId()));

            log.info("Successfully synced customer from Boomerangme to Dutchie");

        } catch (Exception e) {
            log.error("Error syncing customer from Boomerangme for merchant {}: {}", merchantId, e.getMessage(), e);
            String cardId = cardData.has("id") ? cardData.get("id").asText() : "unknown";
            
            createSyncLog(merchantId, SyncLog.SyncType.CUSTOMER, cardId, 
                    SyncLog.SyncStatus.FAILED, e.getMessage(), cardData.toString(), null);
            
            deadLetterQueueService.addToDeadLetterQueue(
                    merchantId,
                    DeadLetterQueueService.SyncType.CUSTOMER,
                    DeadLetterQueueService.EntityType.CUSTOMER,
                    cardId,
                    e.getMessage(),
                    cardData.toString()
            );
        }
    }

    /**
     * Extract customer data from webhook payload and convert to Dutchie EcomCustomerEdit format
     * Dutchie API uses PascalCase field names: FirstName, LastName, EmailAddress, Phone, etc.
     */
    private Map<String, Object> extractCustomerData(JsonNode cardData) {
        Map<String, Object> customerDataMap = new HashMap<>();
        
        // Try to get customer data from nested "customer" object first
        JsonNode customerNode = cardData.has("customer") ? cardData.get("customer") : null;
        
        // Extract fields and convert to Dutchie format (PascalCase)
        JsonNode sourceNode = customerNode != null ? customerNode : cardData;
        
        if (sourceNode.has("email")) {
            customerDataMap.put("EmailAddress", sourceNode.get("email").asText());
        }
        if (sourceNode.has("phone")) {
            customerDataMap.put("Phone", sourceNode.get("phone").asText());
        }
        if (sourceNode.has("firstName") || sourceNode.has("first_name")) {
            String firstName = sourceNode.has("firstName") 
                    ? sourceNode.get("firstName").asText()
                    : sourceNode.get("first_name").asText();
            customerDataMap.put("FirstName", firstName);
        }
        if (sourceNode.has("lastName") || sourceNode.has("last_name")) {
            String lastName = sourceNode.has("lastName")
                    ? sourceNode.get("lastName").asText()
                    : sourceNode.get("last_name").asText();
            customerDataMap.put("LastName", lastName);
        }
        
        // Set required fields for Dutchie API (minimal defaults if not provided)
        // Note: Address1, City, State, PostalCode, Status, CustomerType are required
        // These should ideally come from the source system or be configured per merchant
        if (!customerDataMap.containsKey("Status")) {
            customerDataMap.put("Status", "Active"); // Default status
        }
        
        return customerDataMap;
    }

    private void createSyncLog(String merchantId, SyncLog.SyncType syncType, String entityId,
                               SyncLog.SyncStatus status, String errorMessage, String requestPayload, 
                               String responsePayload) {
        SyncLog syncLog = SyncLog.builder()
                .merchantId(merchantId)
                .syncType(syncType)
                .entityType(SyncLog.EntityType.CUSTOMER)
                .entityId(entityId)
                .sourceSystem(status == SyncLog.SyncStatus.SUCCESS && syncType == SyncLog.SyncType.CUSTOMER 
                        ? SyncLog.SystemType.DUTCHIE : SyncLog.SystemType.BOOMERANGME)
                .targetSystem(status == SyncLog.SyncStatus.SUCCESS && syncType == SyncLog.SyncType.CUSTOMER 
                        ? SyncLog.SystemType.BOOMERANGME : SyncLog.SystemType.DUTCHIE)
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

