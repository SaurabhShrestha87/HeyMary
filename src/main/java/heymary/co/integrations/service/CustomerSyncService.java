package heymary.co.integrations.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import heymary.co.integrations.exception.ApiException;
import heymary.co.integrations.model.Card;
import heymary.co.integrations.model.Customer;
import heymary.co.integrations.model.IntegrationConfig;
import heymary.co.integrations.model.IntegrationType;
import heymary.co.integrations.model.SyncLog;
import heymary.co.integrations.repository.CardRepository;
import heymary.co.integrations.repository.CustomerRepository;
import heymary.co.integrations.repository.IntegrationConfigRepository;
import heymary.co.integrations.repository.SyncLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerSyncService {

    private final BoomerangmeApiClient boomerangmeApiClient;
    private final DutchieApiClient dutchieApiClient;
    private final TreezApiClient treezApiClient;
    private final CustomerRepository customerRepository;
    private final CardRepository cardRepository;
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

            String externalCustomerId = customerData.get("id").asText();
            String email = customerData.has("email") ? customerData.get("email").asText() : null;

            // Check if customer already exists (for Dutchie)
            Optional<Customer> existingCustomer = customerRepository
                    .findByMerchantIdAndExternalCustomerIdAndIntegrationType(
                            merchantId, externalCustomerId, IntegrationType.DUTCHIE);

            Customer customer;
            if (existingCustomer.isPresent()) {
                customer = existingCustomer.get();
                log.debug("Customer {} already exists, updating", externalCustomerId);
            } else {
                customer = Customer.builder()
                        .merchantId(merchantId)
                        .externalCustomerId(externalCustomerId)
                        .build();
            }

            // Update customer data
            if (email != null) customer.setEmail(email);
            if (customerData.has("phone")) customer.setPhone(customerData.get("phone").asText());
            if (customerData.has("first_name")) customer.setFirstName(customerData.get("first_name").asText());
            if (customerData.has("last_name")) customer.setLastName(customerData.get("last_name").asText());

            // If customer doesn't have Boomerangme card, create one
            if (customer.getBoomerangmeCardId() == null) {
                if (config.getDefaultTemplateId() == null) {
                    throw new RuntimeException("Boomerangme default template ID not configured for merchant: " + merchantId);
                }

                Map<String, Object> customerDataMap = new HashMap<>();
                if (email != null) customerDataMap.put("email", email);
                if (customer.getPhone() != null) customerDataMap.put("phone", customer.getPhone());
                if (customer.getFirstName() != null) customerDataMap.put("firstName", customer.getFirstName());
                if (customer.getLastName() != null) customerDataMap.put("lastName", customer.getLastName());

                JsonNode cardResponse = boomerangmeApiClient.createCard(
                        config.getBoomerangmeApiKey(),
                        config.getDefaultTemplateId(),
                        customerDataMap
                ).block();

                if (cardResponse != null && cardResponse.has("id")) {
                    customer.setBoomerangmeCardId(cardResponse.get("id").asText());
                    log.info("Created Boomerangme card {} for customer {}", customer.getBoomerangmeCardId(), externalCustomerId);
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

            createSyncLog(merchantId, SyncLog.SyncType.CUSTOMER, externalCustomerId, 
                    SyncLog.SyncStatus.SUCCESS, null, toJsonString(customerData),
                    String.format("{\"boomerangme_card_id\": \"%s\"}", customer.getBoomerangmeCardId()));

            log.info("Successfully synced customer {} to Boomerangme", externalCustomerId);

        } catch (Exception e) {
            log.error("Error syncing customer for merchant {}: {}", merchantId, e.getMessage(), e);
            String customerId = customerData.has("id") ? customerData.get("id").asText() : "unknown";
            
            createSyncLog(merchantId, SyncLog.SyncType.CUSTOMER, customerId, 
                    SyncLog.SyncStatus.FAILED, e.getMessage(), toJsonString(customerData), null);
            
            deadLetterQueueService.addToDeadLetterQueue(
                    merchantId,
                    DeadLetterQueueService.SyncType.CUSTOMER,
                    DeadLetterQueueService.EntityType.CUSTOMER,
                    customerId,
                    e.getMessage(),
                    toJsonString(customerData)
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
                customerDataMap.put("CustomerId", Integer.parseInt(customer.getExternalCustomerId()));

                dutchieApiClient.createOrUpdateCustomer(
                        config.getDutchieAuthHeader(),
                        customerDataMap
                ).block();

                log.info("Updated Dutchie customer {} from Boomerangme", customer.getExternalCustomerId());
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
                    String externalCustomerId = String.valueOf(dutchieCustomerResponse.get("CustomerId").asInt());
                    
                    customer = Customer.builder()
                            .merchantId(merchantId)
                            .externalCustomerId(externalCustomerId)
                            .boomerangmeCardId(boomerangmeCardId)
                            .email(email)
                            .syncedAt(LocalDateTime.now())
                            .build();
                    
                    customer = customerRepository.save(customer);
                    log.info("Created Dutchie customer {} from Boomerangme", externalCustomerId);
                } else {
                    throw new RuntimeException("Failed to create Dutchie customer: invalid response");
                }
            }

            createSyncLog(merchantId, SyncLog.SyncType.CUSTOMER, boomerangmeCardId, 
                    SyncLog.SyncStatus.SUCCESS, null, toJsonString(cardData),
                    String.format("{\"external_customer_id\": \"%s\"}", customer.getExternalCustomerId()));

            log.info("Successfully synced customer from Boomerangme to Dutchie");

        } catch (Exception e) {
            log.error("Error syncing customer from Boomerangme for merchant {}: {}", merchantId, e.getMessage(), e);
            String cardId = cardData.has("id") ? cardData.get("id").asText() : "unknown";
            
            createSyncLog(merchantId, SyncLog.SyncType.CUSTOMER, cardId, 
                    SyncLog.SyncStatus.FAILED, e.getMessage(), toJsonString(cardData), null);
            
            deadLetterQueueService.addToDeadLetterQueue(
                    merchantId,
                    DeadLetterQueueService.SyncType.CUSTOMER,
                    DeadLetterQueueService.EntityType.CUSTOMER,
                    cardId,
                    e.getMessage(),
                    toJsonString(cardData)
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

    /**
     * Process CardIssuedEvent from Boomerangme
     * Card is created/issued but not yet installed by customer
     */
    @Async("syncTaskExecutor")
    @Transactional
    public void processCardIssued(IntegrationConfig config, JsonNode webhookData) {
        String merchantId = config.getMerchantId();
        log.info("Processing CardIssuedEvent for merchant: {}", merchantId);
        
        try {
            JsonNode cardData = webhookData.has("data") ? webhookData.get("data") : webhookData;
            
            // cardholder_id is required - primary identifier
            if (!cardData.has("cardholder_id") || cardData.get("cardholder_id").isNull()) {
                throw new RuntimeException("Card data missing required cardholder_id");
            }
            
            String cardholderId = cardData.get("cardholder_id").asText();
            
            // serial_number is optional - may not be present in all webhooks
            String serialNumber = null;
            if (cardData.has("serial_number") && !cardData.get("serial_number").isNull()) {
                serialNumber = cardData.get("serial_number").asText();
            }
            
            // Check if card already exists by cardholder_id (primary identifier)
            Optional<Card> existingCard = cardRepository.findByCardholderId(cardholderId);
            
            Card card;
            if (existingCard.isPresent()) {
                card = existingCard.get();
                String identifier = card.getSerialNumber() != null ? card.getSerialNumber() : cardholderId;
                log.info("Card {} already exists, updating", identifier);
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
                                .status("not_installed")
                                .issuedAt(LocalDateTime.now())
                                .build();
                    }
                } else {
                    card = Card.builder()
                            .merchantId(merchantId)
                            .cardholderId(cardholderId)
                            .status("not_installed")
                            .issuedAt(LocalDateTime.now())
                            .build();
                }
            }
            
            // Update card fields from webhook data
            updateCardFromWebhookData(card, cardData);
            
            card = cardRepository.save(card);
            String identifier = card.getSerialNumber() != null ? card.getSerialNumber() : cardholderId;
            log.info("Card {} saved successfully (status: {})", identifier, card.getStatus());
            
            createSyncLog(merchantId, SyncLog.SyncType.CUSTOMER, identifier,
                    SyncLog.SyncStatus.SUCCESS, null, toJsonString(webhookData),
                    String.format("{\"card_id\": %d, \"status\": \"issued\"}", card.getId()));
                    
        } catch (Exception e) {
            log.error("Error processing CardIssuedEvent for merchant {}: {}", merchantId, e.getMessage(), e);
            String identifier = "unknown";
            try {
                JsonNode cardData = webhookData.has("data") ? webhookData.get("data") : webhookData;
                if (cardData.has("cardholder_id") && !cardData.get("cardholder_id").isNull()) {
                    identifier = cardData.get("cardholder_id").asText();
                } else if (cardData.has("serial_number") && !cardData.get("serial_number").isNull()) {
                    identifier = cardData.get("serial_number").asText();
                }
            } catch (Exception ignored) {}
                    
            createSyncLog(merchantId, SyncLog.SyncType.CUSTOMER, identifier,
                    SyncLog.SyncStatus.FAILED, e.getMessage(), toJsonString(webhookData), null);
        }
    }

    /**
     * Process CardInstalledEvent from Boomerangme
     * Customer has installed the card on their device - create/sync customer in POS
     */
    @Async("syncTaskExecutor")
    @Transactional
    public void processCardInstalled(IntegrationConfig config, JsonNode webhookData) {
        String merchantId = config.getMerchantId();
        IntegrationType integrationType = config.getIntegrationType();
        log.info("Processing CardInstalledEvent for merchant: {} (POS: {})", merchantId, integrationType);
        
        try {
            JsonNode cardData = webhookData.has("data") ? webhookData.get("data") : webhookData;
            
            // cardholder_id is required - primary identifier
            if (!cardData.has("cardholder_id") || cardData.get("cardholder_id").isNull()) {
                throw new RuntimeException("Card data missing required cardholder_id");
            }
            
            String cardholderId = cardData.get("cardholder_id").asText();
            
            // serial_number is optional - make it final for lambda usage
            final String serialNumber = cardData.has("serial_number") && !cardData.get("serial_number").isNull()
                    ? cardData.get("serial_number").asText()
                    : null;
            
            // Find or create card by cardholder_id (primary identifier)
            Card card = cardRepository.findByCardholderId(cardholderId)
                    .orElseGet(() -> {
                        // Also check by serial_number if provided (for backward compatibility)
                        if (serialNumber != null && !serialNumber.isEmpty()) {
                            Optional<Card> existingBySerial = cardRepository.findBySerialNumber(serialNumber);
                            if (existingBySerial.isPresent()) {
                                return existingBySerial.get();
                            }
                        }
                        
                        Card newCard = Card.builder()
                                .merchantId(merchantId)
                                .cardholderId(cardholderId)
                                .build();
                        if (serialNumber != null && !serialNumber.isEmpty()) {
                            newCard.setSerialNumber(serialNumber);
                        }
                        return newCard;
                    });
            
            // Update card data
            updateCardFromWebhookData(card, cardData);
            card.setStatus("installed");
            card.setInstalledAt(LocalDateTime.now());
            if (cardData.has("device_type") && !cardData.get("device_type").isNull()) {
                card.setDeviceType(cardData.get("device_type").asText());
            }
            
            card = cardRepository.save(card);
            String identifier = card.getSerialNumber() != null ? card.getSerialNumber() : cardholderId;
            log.info("Card {} updated to installed status (device: {})", identifier, card.getDeviceType());
            
            // Now sync customer to POS based on integration type
            if (integrationType == IntegrationType.TREEZ) {
                syncCustomerToTreez(config, card);
            } else if (integrationType == IntegrationType.DUTCHIE) {
                syncCustomerToDutchie(config, card);
            } else {
                log.warn("Unknown integration type: {}", integrationType);
            }
            
            // Reuse identifier from above
            createSyncLog(merchantId, SyncLog.SyncType.CUSTOMER, identifier,
                    SyncLog.SyncStatus.SUCCESS, null, toJsonString(webhookData),
                    String.format("{\"card_id\": %d, \"status\": \"installed\"}", card.getId()));
                    
        } catch (Exception e) {
            log.error("Error processing CardInstalledEvent for merchant {}: {}", merchantId, e.getMessage(), e);
            String identifier = "unknown";
            try {
                JsonNode cardData = webhookData.has("data") ? webhookData.get("data") : webhookData;
                if (cardData.has("cardholder_id") && !cardData.get("cardholder_id").isNull()) {
                    identifier = cardData.get("cardholder_id").asText();
                } else if (cardData.has("serial_number") && !cardData.get("serial_number").isNull()) {
                    identifier = cardData.get("serial_number").asText();
                }
            } catch (Exception ignored) {}
                    
            createSyncLog(merchantId, SyncLog.SyncType.CUSTOMER, identifier,
                    SyncLog.SyncStatus.FAILED, e.getMessage(), toJsonString(webhookData), null);
        }
    }

    /**
     * Sync customer to Treez POS
     * Flow: Boomerangme card installed → Link to or create Treez customer
     * Matches based on phone number
     */
    private void syncCustomerToTreez(IntegrationConfig config, Card card) {
        String merchantId = config.getMerchantId();
        String cardId = card.getSerialNumber() != null ? card.getSerialNumber() : card.getCardholderId();
        log.info("Syncing customer to Treez for card: {}", cardId);
        
        // Step 1: Check if customer already exists by phone
        Customer existingCustomer = findExistingTreezCustomer(merchantId, card);
        
        if (existingCustomer != null) {
            // Customer exists - link card if not already linked
            linkCardToCustomer(existingCustomer, card);
        } else {
            // No existing customer - create new one
            createNewTreezCustomer(config, card);
        }
    }

    /**
     * Normalize phone number from Boomerangme format to Treez format
     * Boomerangme stores US phone numbers with country code "1" prefix
     * Treez stores phone numbers without country code
     * e.g., "19999999999" in Boomerangme becomes "9999999999" in Treez
     * 
     * @param phone Phone number from Boomerangme (may have "1" prefix)
     * @return Phone number without "1" prefix if it starts with it
     */
    private String normalizePhoneFromBoomerangme(String phone) {
        if (phone == null || phone.isEmpty()) {
            return phone;
        }
        
        // Remove any non-digit characters for comparison
        String digitsOnly = phone.replaceAll("[^0-9]", "");
        
        // If phone starts with "1" and has 11 digits, remove the "1" prefix
        if (digitsOnly.startsWith("1") && digitsOnly.length() == 11) {
            String normalized = digitsOnly.substring(1);
            log.debug("Normalized phone {} to {} for Treez matching", phone, normalized);
            return normalized;
        }
        
        // Return original if doesn't match expected format
        return phone;
    }

    /**
     * Find existing Treez customer by phone number
     * Uses Treez-specific fields for matching
     * Normalizes Boomerangme phone (removes "1" prefix) to match Treez format
     */
    private Customer findExistingTreezCustomer(String merchantId, Card card) {
        String phone = card.getCardholderPhone();
        if (phone != null && !phone.isEmpty()) {
            // Normalize phone from Boomerangme format (remove "1" prefix) to match Treez format
            String normalizedPhone = normalizePhoneFromBoomerangme(phone);
            
            // Try normalized phone first (without "1" prefix)
            Optional<Customer> byPhone = customerRepository.findByMerchantIdAndTreezPhoneAndIntegrationType(
                    merchantId, normalizedPhone, IntegrationType.TREEZ);
            if (byPhone.isPresent()) {
                log.info("Found existing Treez customer by normalized phone: {}", normalizedPhone);
                return byPhone.get();
            }
            
            // Also try original phone (with "1" prefix) in case Treez customer has it stored that way
            if (!phone.equals(normalizedPhone)) {
                Optional<Customer> byOriginalPhone = customerRepository.findByMerchantIdAndTreezPhoneAndIntegrationType(
                        merchantId, phone, IntegrationType.TREEZ);
                if (byOriginalPhone.isPresent()) {
                    log.info("Found existing Treez customer by original phone: {}", phone);
                    return byOriginalPhone.get();
                }
            }
        }
        
        return null;
    }

    /**
     * Link card to existing customer
     */
    private void linkCardToCustomer(Customer customer, Card card) {
        if (customer.getCard() == null || !customer.getCard().getId().equals(card.getId())) {
            String cardId = card.getSerialNumber() != null ? card.getSerialNumber() : card.getCardholderId();
            log.info("Linking card {} to existing customer {}", cardId, customer.getId());
            customer.setCard(card);
            customer.setSyncedAt(LocalDateTime.now());
            
            // Update customer info from card if different
            updateCustomerInfoFromCard(customer, card);
            
            customerRepository.save(customer);
            log.info("Successfully linked card to customer");
        } else {
            log.info("Card already linked to customer {}", customer.getId());
            
            // Still update customer info to stay in sync
            updateCustomerInfoFromCard(customer, card);
            customer.setSyncedAt(LocalDateTime.now());
            customerRepository.save(customer);
        }
    }

    /**
     * Create new Treez customer
     * Stores Treez customer data separately from Boomerangme card data
     * Handles duplicate customer errors by finding and linking to existing customer
     */
    private void createNewTreezCustomer(IntegrationConfig config, Card card) {
        String cardId = card.getSerialNumber() != null ? card.getSerialNumber() : card.getCardholderId();
        String merchantId = config.getMerchantId();
        log.info("Creating new Treez customer for card: {}", cardId);
        
        // Validate required fields
        if (card.getCardholderBirthDate() == null) {
            log.error("Cannot create Treez customer: cardholder_birth_date is required but missing for card {}", cardId);
            // Create local record without Treez customer ID for now
            Customer customer = Customer.builder()
                    .merchantId(merchantId)
                    .integrationType(IntegrationType.TREEZ)
                    .card(card)
                    .treezEmail(card.getCardholderEmail())
                    .treezPhone(card.getCardholderPhone())
                    .treezFirstName(card.getCardholderFirstName())
                    .treezLastName(card.getCardholderLastName())
                    .treezBirthDate(null)
                    .totalPoints(card.getBonusBalance() != null ? card.getBonusBalance() : 0)
                    .syncedAt(LocalDateTime.now())
                    .build();
            customer = customerRepository.save(customer);
            log.warn("Created local customer record {} without Treez customer ID (missing birth date)", customer.getId());
            return;
        }
        
        if (card.getCardholderFirstName() == null || card.getCardholderFirstName().isEmpty()) {
            log.error("Cannot create Treez customer: first_name is required but missing for card {}", cardId);
            return;
        }
        
        if (card.getCardholderLastName() == null || card.getCardholderLastName().isEmpty()) {
            log.error("Cannot create Treez customer: last_name is required but missing for card {}", cardId);
            return;
        }
        
        try {
            // Prepare customer data for Treez API
            Map<String, Object> customerData = new HashMap<>();
            
            // Required fields
            customerData.put("birthday", card.getCardholderBirthDate().format(DateTimeFormatter.ISO_LOCAL_DATE)); // yyyy-MM-dd
            customerData.put("first_name", card.getCardholderFirstName());
            customerData.put("last_name", card.getCardholderLastName());
            customerData.put("patient_type", "ADULT"); // Default to ADULT
            
            // Optional fields
            if (card.getCardholderEmail() != null && !card.getCardholderEmail().isEmpty()) {
                customerData.put("email", card.getCardholderEmail());
            }
            
            // Normalize phone number (remove "1" prefix, ensure 10 digits)
            String normalizedPhone = normalizePhoneForTreez(card.getCardholderPhone());
            if (normalizedPhone != null && !normalizedPhone.isEmpty()) {
                // Ensure it's exactly 10 digits
                String digitsOnly = normalizedPhone.replaceAll("[^0-9]", "");
                if (digitsOnly.length() == 10) {
                    customerData.put("phone", digitsOnly);
                } else {
                    log.warn("Phone number {} is not 10 digits after normalization, skipping phone field", card.getCardholderPhone());
                }
            }
            
            // Set defaults
            customerData.put("gender", "U"); // U for unspecified
            customerData.put("banned", false);
            customerData.put("opt_out", false);
            customerData.put("addresses", new Object[0]); // Empty array
            
            // License expiration from Boomerangme - Treez requires drivers_license and drivers_license_expiration
            customerData.put("drivers_license", "N/A");
            if (card.getCardholderLicenseExpiration() != null) {
                customerData.put("drivers_license_expiration", card.getCardholderLicenseExpiration().format(DateTimeFormatter.ISO_LOCAL_DATE));
            } else {
                customerData.put("drivers_license_expiration", "2030-12-31"); // Default when not provided
            }
            
            // Optional: rewards balance
            if (card.getBonusBalance() != null) {
                customerData.put("rewards_balance", card.getBonusBalance());
            }
            
            // Validate Treez credentials
            if (config.getTreezApiKey() == null || config.getTreezApiKey().isEmpty()) {
                throw new RuntimeException("Treez API key not configured for merchant: " + merchantId);
            }
            if (config.getTreezDispensaryId() == null || config.getTreezDispensaryId().isEmpty()) {
                throw new RuntimeException("Treez dispensary ID not configured for merchant: " + merchantId);
            }
            
            log.info("Calling Treez API to create customer: email={}, phone={}, name={} {}",
                    card.getCardholderEmail(), normalizedPhone, 
                    card.getCardholderFirstName(), card.getCardholderLastName());
            
            // Call Treez API to create customer
            JsonNode response = treezApiClient.createCustomer(config, customerData).block();
            
            if (response == null) {
                throw new RuntimeException("Null response from Treez API");
            }
            
            // Extract Treez customer ID from response
            // Response structure needs to be verified - trying common patterns
            String treezCustomerId = null;
            if (response.has("customer_id")) {
                treezCustomerId = response.get("customer_id").asText();
            } else if (response.has("id")) {
                treezCustomerId = response.get("id").asText();
            } else if (response.has("data") && response.get("data").has("customer_id")) {
                treezCustomerId = response.get("data").get("customer_id").asText();
            } else if (response.has("data") && response.get("data").has("id")) {
                treezCustomerId = response.get("data").get("id").asText();
            }
            
            if (treezCustomerId == null || treezCustomerId.isEmpty()) {
                log.warn("Treez API response does not contain customer_id field. Response: {}", response.toString());
                // Still create local record, but log warning
            }
            
            // Create customer entity with Treez customer ID
            Customer customer = Customer.builder()
                    .merchantId(merchantId)
                    .integrationType(IntegrationType.TREEZ)
                    .externalCustomerId(treezCustomerId)
                    .card(card)
                    .treezEmail(card.getCardholderEmail())
                    .treezPhone(normalizedPhone != null ? normalizedPhone : card.getCardholderPhone())
                    .treezFirstName(card.getCardholderFirstName())
                    .treezLastName(card.getCardholderLastName())
                    .treezBirthDate(card.getCardholderBirthDate())
                    .totalPoints(card.getBonusBalance() != null ? card.getBonusBalance() : 0)
                    .syncedAt(LocalDateTime.now())
                    .build();
            
            customer = customerRepository.save(customer);
            log.info("Successfully created Treez customer {} and linked to card {}", treezCustomerId, cardId);
            
        } catch (ApiException e) {
            // Handle duplicate customer errors (400)
            if (e.getStatusCode() == 400) {
                String errorBody = e.getResponseBody() != null ? e.getResponseBody().toLowerCase() : "";
                boolean isDuplicate = errorBody.contains("duplicate") || 
                                     errorBody.contains("already exists") ||
                                     errorBody.contains("validation_error");
                
                if (isDuplicate) {
                    log.info("Treez customer creation failed due to duplicate (phone/email). Searching for existing customer...");
                    
                    // Try to find existing customer by phone or email
                    String normalizedPhone = normalizePhoneForTreez(card.getCardholderPhone());
                    
                    JsonNode existingCustomer = null;
                    String treezCustomerId = null;
                    
                    // Try phone first if available
                    if (normalizedPhone != null && !normalizedPhone.isEmpty()) {
                        String digitsOnly = normalizedPhone.replaceAll("[^0-9]", "");
                        if (digitsOnly.length() == 10) {
                            try {
                                existingCustomer = treezApiClient.findCustomerByPhone(config, digitsOnly).block();
                                if (existingCustomer != null) {
                                    // Extract customer ID from response
                                    if (existingCustomer.has("customer_id")) {
                                        treezCustomerId = existingCustomer.get("customer_id").asText();
                                    } else if (existingCustomer.has("id")) {
                                        treezCustomerId = existingCustomer.get("id").asText();
                                    } else if (existingCustomer.has("data") && existingCustomer.get("data").has("customer_id")) {
                                        treezCustomerId = existingCustomer.get("data").get("customer_id").asText();
                                    } else if (existingCustomer.has("data") && existingCustomer.get("data").has("id")) {
                                        treezCustomerId = existingCustomer.get("data").get("id").asText();
                                    }
                                    log.info("Found existing Treez customer {} by phone", treezCustomerId);
                                }
                            } catch (Exception phoneSearchError) {
                                log.debug("Could not find customer by phone: {}", phoneSearchError.getMessage());
                            }
                        }
                    }
                    
                    // Try email if phone search didn't find customer
                    if (treezCustomerId == null && card.getCardholderEmail() != null && !card.getCardholderEmail().isEmpty()) {
                        try {
                            existingCustomer = treezApiClient.findCustomerByEmail(config, card.getCardholderEmail()).block();
                            if (existingCustomer != null) {
                                // Extract customer ID from response
                                if (existingCustomer.has("customer_id")) {
                                    treezCustomerId = existingCustomer.get("customer_id").asText();
                                } else if (existingCustomer.has("id")) {
                                    treezCustomerId = existingCustomer.get("id").asText();
                                } else if (existingCustomer.has("data") && existingCustomer.get("data").has("customer_id")) {
                                    treezCustomerId = existingCustomer.get("data").get("customer_id").asText();
                                } else if (existingCustomer.has("data") && existingCustomer.get("data").has("id")) {
                                    treezCustomerId = existingCustomer.get("data").get("id").asText();
                                }
                                log.info("Found existing Treez customer {} by email", treezCustomerId);
                            }
                        } catch (Exception emailSearchError) {
                            log.debug("Could not find customer by email: {}", emailSearchError.getMessage());
                        }
                    }
                    
                    // Link card to existing customer if found
                    if (treezCustomerId != null && !treezCustomerId.isEmpty()) {
                        Customer customer = Customer.builder()
                                .merchantId(merchantId)
                                .integrationType(IntegrationType.TREEZ)
                                .externalCustomerId(treezCustomerId)
                                .card(card)
                                .treezEmail(card.getCardholderEmail())
                                .treezPhone(normalizedPhone != null ? normalizedPhone : card.getCardholderPhone())
                                .treezFirstName(card.getCardholderFirstName())
                                .treezLastName(card.getCardholderLastName())
                                .treezBirthDate(card.getCardholderBirthDate())
                                .totalPoints(card.getBonusBalance() != null ? card.getBonusBalance() : 0)
                                .syncedAt(LocalDateTime.now())
                                .build();
                        
                        customer = customerRepository.save(customer);
                        log.info("Linked card {} to existing Treez customer {}", cardId, treezCustomerId);
                        return;
                    } else {
                        log.warn("Duplicate error from Treez but could not find existing customer by phone/email. Creating local record without Treez ID.");
                    }
                }
            }
            
            // For other errors or if duplicate handling failed, log and create local record
            log.error("Error creating Treez customer: {} - {}", e.getStatusCode(), e.getResponseBody());
            
            // Create local record without Treez customer ID
            String normalizedPhone = normalizePhoneForTreez(card.getCardholderPhone());
            Customer customer = Customer.builder()
                    .merchantId(merchantId)
                    .integrationType(IntegrationType.TREEZ)
                    .card(card)
                    .treezEmail(card.getCardholderEmail())
                    .treezPhone(normalizedPhone != null ? normalizedPhone : card.getCardholderPhone())
                    .treezFirstName(card.getCardholderFirstName())
                    .treezLastName(card.getCardholderLastName())
                    .treezBirthDate(card.getCardholderBirthDate())
                    .totalPoints(card.getBonusBalance() != null ? card.getBonusBalance() : 0)
                    .syncedAt(LocalDateTime.now())
                    .build();
            
            customer = customerRepository.save(customer);
            log.warn("Created local customer record {} without Treez customer ID (API error)", customer.getId());
            
        } catch (Exception e) {
            log.error("Unexpected error creating Treez customer for card {}: {}", cardId, e.getMessage(), e);
            
            // Create local record without Treez customer ID
            String normalizedPhone = normalizePhoneForTreez(card.getCardholderPhone());
            Customer customer = Customer.builder()
                    .merchantId(merchantId)
                    .integrationType(IntegrationType.TREEZ)
                    .card(card)
                    .treezEmail(card.getCardholderEmail())
                    .treezPhone(normalizedPhone != null ? normalizedPhone : card.getCardholderPhone())
                    .treezFirstName(card.getCardholderFirstName())
                    .treezLastName(card.getCardholderLastName())
                    .treezBirthDate(card.getCardholderBirthDate())
                    .totalPoints(card.getBonusBalance() != null ? card.getBonusBalance() : 0)
                    .syncedAt(LocalDateTime.now())
                    .build();
            
            customer = customerRepository.save(customer);
            log.warn("Created local customer record {} without Treez customer ID (unexpected error)", customer.getId());
        }
    }
    
    /**
     * Normalize phone number for Treez API (10 digits, no "1" prefix)
     * Treez requires 10-digit phone numbers
     */
    private String normalizePhoneForTreez(String phone) {
        if (phone == null || phone.isEmpty()) {
            return phone;
        }
        
        // Remove any non-digit characters
        String digitsOnly = phone.replaceAll("[^0-9]", "");
        
        // If phone starts with "1" and has 11 digits, remove the "1" prefix
        if (digitsOnly.startsWith("1") && digitsOnly.length() == 11) {
            String normalized = digitsOnly.substring(1);
            log.debug("Normalized phone {} to {} for Treez API", phone, normalized);
            return normalized;
        }
        
        // If already 10 digits, return as is
        if (digitsOnly.length() == 10) {
            return digitsOnly;
        }
        
        // Return original if doesn't match expected format (will be logged as warning)
        return phone;
    }

    /**
     * Update customer information from card data
     * For Treez customers, updates Treez-specific fields
     * Note: Card data comes from Boomerangme, Treez customer data is stored separately
     */
    private void updateCustomerInfoFromCard(Customer customer, Card card) {
        boolean updated = false;
        
        // For Treez customers, update Treez-specific fields
        // Only update if Treez data is missing (initial sync from Boomerangme card)
        if (customer.getIntegrationType() == IntegrationType.TREEZ) {
            if (customer.getTreezEmail() == null && card.getCardholderEmail() != null) {
                customer.setTreezEmail(card.getCardholderEmail());
                updated = true;
            }
            if (customer.getTreezPhone() == null && card.getCardholderPhone() != null) {
                customer.setTreezPhone(card.getCardholderPhone());
                updated = true;
            }
            if (customer.getTreezFirstName() == null && card.getCardholderFirstName() != null) {
                customer.setTreezFirstName(card.getCardholderFirstName());
                updated = true;
            }
            if (customer.getTreezLastName() == null && card.getCardholderLastName() != null) {
                customer.setTreezLastName(card.getCardholderLastName());
                updated = true;
            }
            if (customer.getTreezBirthDate() == null && card.getCardholderBirthDate() != null) {
                customer.setTreezBirthDate(card.getCardholderBirthDate());
                updated = true;
            }
        } else {
            // For Dutchie customers, use legacy fields (backward compatibility)
            if (card.getCardholderEmail() != null && !card.getCardholderEmail().equals(customer.getEmail())) {
                customer.setEmail(card.getCardholderEmail());
                updated = true;
            }
            if (card.getCardholderPhone() != null && !card.getCardholderPhone().equals(customer.getPhone())) {
                customer.setPhone(card.getCardholderPhone());
                updated = true;
            }
            if (card.getCardholderFirstName() != null && !card.getCardholderFirstName().equals(customer.getFirstName())) {
                customer.setFirstName(card.getCardholderFirstName());
                updated = true;
            }
            if (card.getCardholderLastName() != null && !card.getCardholderLastName().equals(customer.getLastName())) {
                customer.setLastName(card.getCardholderLastName());
                updated = true;
            }
            if (card.getCardholderBirthDate() != null && !card.getCardholderBirthDate().equals(customer.getBirthDate())) {
                customer.setBirthDate(card.getCardholderBirthDate());
                updated = true;
            }
        }
        
        if (updated) {
            log.info("Updated customer info from card data");
        }
    }

    /**
     * Sync customer to Dutchie POS
     */
    private void syncCustomerToDutchie(IntegrationConfig config, Card card) {
        String cardId = card.getSerialNumber() != null ? card.getSerialNumber() : card.getCardholderId();
        log.info("Syncing customer to Dutchie for card: {}", cardId);
        
        try {
            // Check if customer already exists
            Optional<Customer> existingCustomer = customerRepository
                    .findByMerchantIdAndEmail(config.getMerchantId(), card.getCardholderEmail());
            
            if (existingCustomer.isPresent()) {
                Customer customer = existingCustomer.get();
                // Link card if not already linked
                if (customer.getCard() == null) {
                    customer.setCard(card);
                    customer.setSyncedAt(LocalDateTime.now());
                    customerRepository.save(customer);
                    // Reuse cardId from method start
                    log.info("Linked existing Dutchie customer {} to card {}", customer.getId(), cardId);
                }
            } else {
                // Create new customer in Dutchie
                Map<String, Object> customerDataMap = new HashMap<>();
                customerDataMap.put("EmailAddress", card.getCardholderEmail());
                customerDataMap.put("Phone", card.getCardholderPhone());
                customerDataMap.put("FirstName", card.getCardholderFirstName());
                customerDataMap.put("LastName", card.getCardholderLastName());
                customerDataMap.put("Status", "Active");
                customerDataMap.put("CustomerId", null); // null for new customer
                
                JsonNode dutchieResponse = dutchieApiClient.createOrUpdateCustomer(
                        config.getDutchieAuthHeader(),
                        customerDataMap
                ).block();
                
                if (dutchieResponse != null && dutchieResponse.has("CustomerId")) {
                    String externalCustomerId = String.valueOf(dutchieResponse.get("CustomerId").asInt());
                    
                    Customer customer = Customer.builder()
                            .merchantId(config.getMerchantId())
                            .integrationType(IntegrationType.DUTCHIE)
                            .externalCustomerId(externalCustomerId)
                            .card(card)
                            .email(card.getCardholderEmail())
                            .phone(card.getCardholderPhone())
                            .firstName(card.getCardholderFirstName())
                            .lastName(card.getCardholderLastName())
                            .birthDate(card.getCardholderBirthDate())
                            .totalPoints(card.getBonusBalance() != null ? card.getBonusBalance() : 0)
                            .syncedAt(LocalDateTime.now())
                            .build();
                    
                    customer = customerRepository.save(customer);
                    // Reuse cardId from method start
                    log.info("Created Dutchie customer {} and linked to card {}", externalCustomerId, cardId);
                } else {
                    throw new RuntimeException("Failed to create Dutchie customer: invalid response");
                }
            }
        } catch (Exception e) {
            log.error("Error syncing customer to Dutchie: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Update Card entity from Boomerangme webhook data
     */
    private void updateCardFromWebhookData(Card card, JsonNode cardData) {
        if (cardData.has("card_type")) {
            card.setCardType(cardData.get("card_type").asText());
        }
        if (cardData.has("template_id") && !cardData.get("template_id").isNull()) {
            try {
                card.setTemplateId(cardData.get("template_id").asInt());
            } catch (Exception e) {
                log.warn("Could not parse template_id as integer: {}", cardData.get("template_id").asText());
                card.setTemplateId(null);
            }
        }
        if (cardData.has("cardholder_email") && !cardData.get("cardholder_email").isNull()) {
            card.setCardholderEmail(cardData.get("cardholder_email").asText());
        }
        if (cardData.has("cardholder_phone") && !cardData.get("cardholder_phone").isNull()) {
            card.setCardholderPhone(cardData.get("cardholder_phone").asText());
        }
        if (cardData.has("cardholder_first_name") && !cardData.get("cardholder_first_name").isNull()) {
            card.setCardholderFirstName(cardData.get("cardholder_first_name").asText());
        }
        if (cardData.has("cardholder_last_name") && !cardData.get("cardholder_last_name").isNull()) {
            card.setCardholderLastName(cardData.get("cardholder_last_name").asText());
        }
        if (cardData.has("cardholder_birth_date") && !cardData.get("cardholder_birth_date").isNull()) {
            try {
                String birthDateStr = cardData.get("cardholder_birth_date").asText();
                card.setCardholderBirthDate(LocalDate.parse(birthDateStr));
            } catch (Exception e) {
                log.warn("Failed to parse birth date: {}", e.getMessage());
            }
        }
        if (cardData.has("cardholder_license_expiration") && !cardData.get("cardholder_license_expiration").isNull()) {
            try {
                String licenseExpStr = cardData.get("cardholder_license_expiration").asText();
                card.setCardholderLicenseExpiration(LocalDate.parse(licenseExpStr));
            } catch (Exception e) {
                log.warn("Failed to parse license expiration: {}", e.getMessage());
            }
        }
        if (cardData.has("bonus_balance") && !cardData.get("bonus_balance").isNull()) {
            card.setBonusBalance(cardData.get("bonus_balance").asInt());
        }
        if (cardData.has("countVisits") && !cardData.get("countVisits").isNull()) {
            card.setCountVisits(cardData.get("countVisits").asInt());
        }
        if (cardData.has("balance") && !cardData.get("balance").isNull()) {
            card.setBalance(cardData.get("balance").asInt());
        }
        if (cardData.has("number_stamps_total") && !cardData.get("number_stamps_total").isNull()) {
            card.setNumberStampsTotal(cardData.get("number_stamps_total").asInt());
        }
        if (cardData.has("number_rewards_unused") && !cardData.get("number_rewards_unused").isNull()) {
            card.setNumberRewardsUnused(cardData.get("number_rewards_unused").asInt());
        }
        if (cardData.has("short_link") && !cardData.get("short_link").isNull()) {
            card.setShortLink(cardData.get("short_link").asText());
        }
        if (cardData.has("share_link") && !cardData.get("share_link").isNull()) {
            card.setShareLink(cardData.get("share_link").asText());
        }
        if (cardData.has("direct_install_link_universal") && !cardData.get("direct_install_link_universal").isNull()) {
            card.setInstallLinkUniversal(cardData.get("direct_install_link_universal").asText());
        }
        if (cardData.has("direct_install_link_apple") && !cardData.get("direct_install_link_apple").isNull()) {
            card.setInstallLinkApple(cardData.get("direct_install_link_apple").asText());
        }
        if (cardData.has("direct_install_link_google") && !cardData.get("direct_install_link_google").isNull()) {
            card.setInstallLinkGoogle(cardData.get("direct_install_link_google").asText());
        }
        if (cardData.has("direct_install_link_pwa") && !cardData.get("direct_install_link_pwa").isNull()) {
            card.setInstallLinkPwa(cardData.get("direct_install_link_pwa").asText());
        }
        if (cardData.has("utm_source") && !cardData.get("utm_source").isNull()) {
            card.setUtmSource(cardData.get("utm_source").asText());
        }
        if (cardData.has("latitude") && !cardData.get("latitude").isNull()) {
            card.setLatitude(cardData.get("latitude").asDouble());
        }
        if (cardData.has("longitude") && !cardData.get("longitude").isNull()) {
            card.setLongitude(cardData.get("longitude").asDouble());
        }
        
        // Store custom_fields as JSON string
        if (cardData.has("custom_fields") && !cardData.get("custom_fields").isNull()) {
            try {
                card.setCustomFields(objectMapper.writeValueAsString(cardData.get("custom_fields")));
            } catch (Exception e) {
                log.warn("Failed to serialize custom_fields: {}", e.getMessage());
            }
        }
        
        card.setSyncedAt(LocalDateTime.now());
    }
    
    /**
     * Convert JsonNode to JSON string safely
     */
    private String toJsonString(JsonNode jsonNode) {
        try {
            return objectMapper.writeValueAsString(jsonNode);
        } catch (Exception e) {
            log.warn("Failed to convert JsonNode to string: {}", e.getMessage());
            return "{}";
        }
    }

    /**
     * Process CardBalanceUpdatedEvent from Boomerangme
     * This event is triggered when card balance is updated (points added/subtracted)
     * Can be triggered by API calls, manual updates, or other operations
     */
    @Async("syncTaskExecutor")
    @Transactional
    public void processCardBalanceUpdated(IntegrationConfig config, JsonNode webhookData) {
        String merchantId = config.getMerchantId();
        log.info("Processing CardBalanceUpdatedEvent for merchant: {}", merchantId);
        
        try {
            JsonNode cardData = webhookData.has("data") ? webhookData.get("data") : webhookData;
            
            // Extract card identifier - try serial_number first, then cardholder_id
            String serialNumber = null;
            String cardholderId = null;
            
            if (cardData.has("serial_number") && !cardData.get("serial_number").isNull()) {
                serialNumber = cardData.get("serial_number").asText();
            }
            
            if (cardData.has("cardholder_id") && !cardData.get("cardholder_id").isNull()) {
                cardholderId = cardData.get("cardholder_id").asText();
            }
            
            if (serialNumber == null && cardholderId == null) {
                log.warn("CardBalanceUpdatedEvent missing both serial_number and cardholder_id, skipping");
                return;
            }
            
            // Find card by serial_number or cardholder_id
            Optional<Card> cardOpt = Optional.empty();
            if (serialNumber != null) {
                cardOpt = cardRepository.findBySerialNumber(serialNumber);
            }
            if (cardOpt.isEmpty() && cardholderId != null) {
                cardOpt = cardRepository.findByCardholderId(cardholderId);
            }
            
            if (cardOpt.isEmpty()) {
                log.warn("Card not found for CardBalanceUpdatedEvent (serial_number: {}, cardholder_id: {}), skipping", 
                        serialNumber, cardholderId);
                return;
            }
            
            Card card = cardOpt.get();
            
            // Update card balance from webhook data
            if (cardData.has("bonus_balance") && !cardData.get("bonus_balance").isNull()) {
                int newBalance = cardData.get("bonus_balance").asInt();
                card.setBonusBalance(newBalance);
                log.info("Updated card balance to {} for card {}", newBalance, 
                        serialNumber != null ? serialNumber : cardholderId);
            }
            
            // Update other card fields if present
            updateCardFromWebhookData(card, cardData);
            
            card.setSyncedAt(LocalDateTime.now());
            card = cardRepository.save(card);
            
            // Update linked customer's total points if customer exists
            Optional<Customer> customerOpt = customerRepository.findByCardId(card.getId());
            if (customerOpt.isPresent()) {
                Customer customer = customerOpt.get();
                if (card.getBonusBalance() != null) {
                    customer.setTotalPoints(card.getBonusBalance());
                    customer.setSyncedAt(LocalDateTime.now());
                    customerRepository.save(customer);
                    log.info("Updated customer {} total points to {}", customer.getId(), card.getBonusBalance());
                }
            }
            
            String identifier = serialNumber != null ? serialNumber : cardholderId;
            createSyncLog(merchantId, SyncLog.SyncType.POINTS, identifier,
                    SyncLog.SyncStatus.SUCCESS, null, toJsonString(webhookData),
                    String.format("{\"card_id\": %d, \"balance\": %d}", card.getId(), 
                            card.getBonusBalance() != null ? card.getBonusBalance() : 0));
                    
        } catch (Exception e) {
            log.error("Error processing CardBalanceUpdatedEvent for merchant {}: {}", merchantId, e.getMessage(), e);
            String identifier = "unknown";
            try {
                JsonNode cardData = webhookData.has("data") ? webhookData.get("data") : webhookData;
                if (cardData.has("serial_number") && !cardData.get("serial_number").isNull()) {
                    identifier = cardData.get("serial_number").asText();
                } else if (cardData.has("cardholder_id") && !cardData.get("cardholder_id").isNull()) {
                    identifier = cardData.get("cardholder_id").asText();
                }
            } catch (Exception ignored) {}
                    
            createSyncLog(merchantId, SyncLog.SyncType.POINTS, identifier,
                    SyncLog.SyncStatus.FAILED, e.getMessage(), toJsonString(webhookData), null);
        }
    }
}

