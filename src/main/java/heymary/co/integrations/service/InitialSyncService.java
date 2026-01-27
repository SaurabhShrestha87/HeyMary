package heymary.co.integrations.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import heymary.co.integrations.exception.ApiException;
import heymary.co.integrations.model.Card;
import heymary.co.integrations.model.Customer;
import heymary.co.integrations.model.IntegrationConfig;
import heymary.co.integrations.model.IntegrationType;
import heymary.co.integrations.repository.CardRepository;
import heymary.co.integrations.repository.CustomerRepository;
import heymary.co.integrations.repository.IntegrationConfigRepository;
import heymary.co.integrations.repository.TemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service for performing initial synchronization of cards and customers
 * when a new integration config is added.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InitialSyncService {

    private final BoomerangmeApiClient boomerangmeApiClient;
    private final TreezApiClient treezApiClient;
    private final CardRepository cardRepository;
    private final CustomerRepository customerRepository;
    private final IntegrationConfigRepository integrationConfigRepository;
    private final TemplateService templateService;
    private final TemplateRepository templateRepository;
    private final ObjectMapper objectMapper;

    /**
     * Perform initial sync of all cards from Boomerangme
     * This method fetches all cards from Boomerangme API with pagination
     * and syncs them with the local database and POS system (Treez).
     * 
     * @param config Integration configuration
     */
    @Async("syncTaskExecutor")
    @Transactional
    public void performInitialSync(IntegrationConfig config) {
        String merchantId = config.getMerchantId();
        log.info("Starting initial sync for merchant: {}", merchantId);
        
        try {
            int page = 1;
            int itemsPerPage = 50; // Boomerangme API default
            int totalCardsFetched = 0;
            int totalCardsProcessed = 0;
            int totalCustomersLinked = 0;
            
            boolean hasMorePages = true;
            
            while (hasMorePages) {
                log.info("Fetching cards page {} for merchant {}", page, merchantId);
                
                try {
                    JsonNode response = boomerangmeApiClient.getCards(
                            config.getBoomerangmeApiKey(), 
                            page, 
                            itemsPerPage
                    ).block();
                    
                    if (response == null) {
                        log.warn("Null response from Boomerangme API for page {}", page);
                        break;
                    }
                    
                    // Extract cards from response
                    JsonNode cardsData = null;
                    if (response.has("data") && response.get("data").isArray()) {
                        cardsData = response.get("data");
                    } else if (response.isArray()) {
                        cardsData = response;
                    }
                    
                    if (cardsData == null || cardsData.size() == 0) {
                        log.info("No more cards found on page {}, ending pagination", page);
                        hasMorePages = false;
                        break;
                    }
                    
                    int cardsOnPage = cardsData.size();
                    totalCardsFetched += cardsOnPage;
                    log.info("Processing {} cards from page {}", cardsOnPage, page);
                    
                    // Process each card
                    Iterator<JsonNode> cardsIterator = cardsData.elements();
                    while (cardsIterator.hasNext()) {
                        JsonNode cardData = cardsIterator.next();
                        
                        try {
                            // Save card to database
                            Card savedCard = saveCardFromBoomerangmeResponse(merchantId, cardData);
                            
                            if (savedCard != null) {
                                totalCardsProcessed++;
                                
                                // If card is installed, try to link with POS customer
                                if ("installed".equals(savedCard.getStatus())) {
                                    boolean linked = linkCardToTreezCustomer(config, savedCard);
                                    if (linked) {
                                        totalCustomersLinked++;
                                    }
                                }
                            }
                        } catch (Exception e) {
                            String cardId = cardData.has("cardholder_id") 
                                    ? cardData.get("cardholder_id").asText() 
                                    : "unknown";
                            log.error("Error processing card {}: {}", cardId, e.getMessage(), e);
                            // Continue with next card
                        }
                    }
                    
                    // Check if there are more pages
                    // If we got fewer cards than itemsPerPage, we've reached the end
                    if (cardsOnPage < itemsPerPage) {
                        log.info("Received {} cards (less than {}), ending pagination", cardsOnPage, itemsPerPage);
                        hasMorePages = false;
                    } else {
                        page++;
                    }
                    
                } catch (ApiException e) {
                    log.error("API error fetching cards page {}: {} - {}", page, e.getStatusCode(), e.getResponseBody());
                    // If we get a 404 or other error, assume no more pages
                    if (e.getStatusCode() == 404) {
                        hasMorePages = false;
                    } else {
                        // For other errors, stop the sync
                        throw e;
                    }
                } catch (Exception e) {
                    log.error("Error fetching cards page {}: {}", page, e.getMessage(), e);
                    throw e;
                }
            }
            
            log.info("Initial sync completed for merchant {}. Cards fetched: {}, Cards processed: {}, Customers linked: {}", 
                    merchantId, totalCardsFetched, totalCardsProcessed, totalCustomersLinked);
                    
        } catch (Exception e) {
            log.error("Initial sync failed for merchant {}: {}", merchantId, e.getMessage(), e);
        }
    }

    /**
     * Perform reverse sync: fetch Treez customers and create Boomerangme customers/cards if missing
     * This syncs customers from Treez to Boomerangme (opposite direction of initial sync)
     * 
     * @param config Integration configuration
     */
    @Async("syncTaskExecutor")
    @Transactional
    public void performReverseSync(IntegrationConfig config) {
        String merchantId = config.getMerchantId();
        log.info("Starting reverse sync for merchant: {}", merchantId);
        
        try {
            // Get all Treez customers for this merchant that don't have Boomerangme cards
            List<Customer> treezCustomers = customerRepository.findByMerchantIdAndIntegrationType(
                    merchantId, IntegrationType.TREEZ);
            
            int totalCustomers = treezCustomers.size();
            int customersProcessed = 0;
            int customersLinked = 0;
            int customersCreated = 0;
            
            log.info("Found {} Treez customers to process for reverse sync", totalCustomers);
            
            for (Customer customer : treezCustomers) {
                try {
                    // Skip if customer already has a card
                    if (customer.getCard() != null) {
                        log.debug("Customer {} already has Boomerangme card, skipping", customer.getId());
                        customersProcessed++;
                        continue;
                    }
                    
                    // Skip if customer doesn't have phone number (required for matching)
                    String phone = customer.getTreezPhone();
                    if (phone == null || phone.isEmpty()) {
                        log.debug("Customer {} has no phone number, skipping reverse sync", customer.getId());
                        customersProcessed++;
                        continue;
                    }
                    
                    // Check if Boomerangme card exists for this phone
                    Card existingCard = findBoomerangmeCardByPhone(config, phone);
                    
                    if (existingCard != null) {
                        // Link existing card to customer
                        customer.setCard(existingCard);
                        customer.setSyncedAt(LocalDateTime.now());
                        customerRepository.save(customer);
                        customersLinked++;
                        log.info("Linked existing Boomerangme card to Treez customer {}", customer.getExternalCustomerId());
                    } else {
                        // Create Boomerangme customer (card will be installed manually)
                        boolean created = createBoomerangmeCustomerForTreezCustomer(config, customer);
                        if (created) {
                            customersCreated++;
                        }
                    }
                    
                    customersProcessed++;
                    
                } catch (Exception e) {
                    log.error("Error processing Treez customer {} for reverse sync: {}", 
                            customer.getId(), e.getMessage(), e);
                    // Continue with next customer
                }
            }
            
            log.info("Reverse sync completed for merchant {}. Customers processed: {}, Linked: {}, Created: {}", 
                    merchantId, customersProcessed, customersLinked, customersCreated);
                    
        } catch (Exception e) {
            log.error("Reverse sync failed for merchant {}: {}", merchantId, e.getMessage(), e);
        }
    }

    /**
     * Find Boomerangme card by phone number
     * Checks local database first, then Boomerangme API
     */
    private Card findBoomerangmeCardByPhone(IntegrationConfig config, String phone) {
        if (phone == null || phone.isEmpty()) {
            return null;
        }
        
        // Normalize phone for Boomerangme (add "1" prefix if needed)
        String normalizedPhone = normalizePhoneForBoomerangme(phone);
        
        // Check local database
        Optional<Card> cardByPhone = cardRepository.findByCardholderPhone(normalizedPhone);
        if (cardByPhone.isPresent()) {
            log.debug("Found Boomerangme card in local DB by phone: {}", normalizedPhone);
            return cardByPhone.get();
        }
        
        // Also try original phone
        if (!phone.equals(normalizedPhone)) {
            Optional<Card> cardByOriginalPhone = cardRepository.findByCardholderPhone(phone);
            if (cardByOriginalPhone.isPresent()) {
                log.debug("Found Boomerangme card in local DB by original phone: {}", phone);
                return cardByOriginalPhone.get();
            }
        }
        
        // Not found locally - try Boomerangme API
        try {
            JsonNode cardsResponse = boomerangmeApiClient.searchCardsByPhone(
                    config.getBoomerangmeApiKey(), normalizedPhone
            ).block();
            
            if (cardsResponse != null && cardsResponse.has("data") && 
                cardsResponse.get("data").isArray() && cardsResponse.get("data").size() > 0) {
                // Process and save card from API response
                JsonNode cardData = cardsResponse.get("data").get(0);
                Card card = saveCardFromBoomerangmeResponse(config.getMerchantId(), cardData);
                if (card != null) {
                    log.info("Found and saved Boomerangme card from API by phone: {}", normalizedPhone);
                    return card;
                }
            }
        } catch (Exception e) {
            log.debug("Error searching Boomerangme API by phone: {}", e.getMessage());
        }
        
        return null;
    }

    /**
     * Normalize phone number for Boomerangme (add "1" prefix if needed)
     */
    private String normalizePhoneForBoomerangme(String phone) {
        if (phone == null || phone.isEmpty()) {
            return phone;
        }
        
        String digitsOnly = phone.replaceAll("[^0-9]", "");
        
        // If phone doesn't start with "1" and has 10 digits, add "1" prefix
        if (!digitsOnly.startsWith("1") && digitsOnly.length() == 10) {
            return "1" + digitsOnly;
        }
        
        return phone;
    }

    /**
     * Create Boomerangme customer for a Treez customer
     * Returns true if customer was created successfully
     */
    private boolean createBoomerangmeCustomerForTreezCustomer(IntegrationConfig config, Customer treezCustomer) {
        String treezCustomerId = treezCustomer.getExternalCustomerId();
        
        try {
            // Prepare customer creation request
            Map<String, Object> customerData = new HashMap<>();
            
            // Required: firstName
            if (treezCustomer.getTreezFirstName() == null || treezCustomer.getTreezFirstName().isEmpty()) {
                log.warn("Cannot create Boomerangme customer: first name is required for Treez customer {}", treezCustomerId);
                return false;
            }
            customerData.put("firstName", treezCustomer.getTreezFirstName());
            
            // Required: phone OR email (at least one)
            String phone = treezCustomer.getTreezPhone();
            if (phone != null && !phone.isEmpty()) {
                // Normalize phone for Boomerangme
                String normalizedPhone = normalizePhoneForBoomerangme(phone);
                customerData.put("phone", normalizedPhone);
            }
            
            String email = treezCustomer.getTreezEmail();
            if (email != null && !email.isEmpty()) {
                customerData.put("email", email);
            }
            
            // Optional fields
            if (treezCustomer.getTreezLastName() != null && !treezCustomer.getTreezLastName().isEmpty()) {
                customerData.put("surname", treezCustomer.getTreezLastName());
            }
            
            if (treezCustomer.getTreezBirthDate() != null) {
                customerData.put("dateOfBirth", treezCustomer.getTreezBirthDate().toString());
            }
            
            // Set externalUserId to Treez customer ID
            customerData.put("externalUserId", treezCustomerId);
            
            log.info("Creating Boomerangme customer for Treez customer {} (phone: {})", treezCustomerId, phone);
            
            // Call Boomerangme API to create customer
            JsonNode customerResponse = boomerangmeApiClient.createCustomer(
                    config.getBoomerangmeApiKey(),
                    customerData
            ).block();
            
            if (customerResponse != null) {
                // Extract customer ID from response
                String boomerangmeCustomerId = null;
                if (customerResponse.has("data") && customerResponse.get("data").has("id")) {
                    boomerangmeCustomerId = customerResponse.get("data").get("id").asText();
                } else if (customerResponse.has("id")) {
                    boomerangmeCustomerId = customerResponse.get("id").asText();
                }
                
                if (boomerangmeCustomerId != null) {
                    log.info("Successfully created Boomerangme customer {} for Treez customer {}", 
                            boomerangmeCustomerId, treezCustomerId);
                    log.info("NOTE: Customer must manually install card via link/QR code. Card will be linked when installed.");
                    return true;
                } else {
                    log.error("Boomerangme customer creation response missing id field");
                    return false;
                }
            } else {
                log.error("Null response from Boomerangme API when creating customer");
                return false;
            }
            
        } catch (Exception e) {
            log.error("Error creating Boomerangme customer for Treez customer {}: {}", 
                    treezCustomerId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Save card from Boomerangme API response
     * 
     * @param merchantId Merchant ID
     * @param cardData Card data from Boomerangme API
     * @return Saved Card entity or null if failed
     */
    private Card saveCardFromBoomerangmeResponse(String merchantId, JsonNode cardData) {
        // Get config to access default_template_id
        IntegrationConfig config = integrationConfigRepository.findByMerchantId(merchantId)
                .orElseThrow(() -> new RuntimeException("Integration config not found for merchant: " + merchantId));
        try {
            // For list API response: id is the card serial number (e.g., "153111-927-114")
            // customerId is the cardholder_id (e.g., "019b1df3-a306-739e-988b-50dbfedc2922")
            
            String serialNumber = null;
            String cardholderId = null;
            
            // Extract serial number from "id" field
            if (cardData.has("id") && !cardData.get("id").isNull()) {
                serialNumber = cardData.get("id").asText();
            }
            
            // Extract cardholder ID from "customerId" field
            if (cardData.has("customerId") && !cardData.get("customerId").isNull()) {
                cardholderId = cardData.get("customerId").asText();
            }
            
            // For webhook format compatibility: check cardholder_id and serial_number fields
            if (cardholderId == null && cardData.has("cardholder_id") && !cardData.get("cardholder_id").isNull()) {
                cardholderId = cardData.get("cardholder_id").asText();
            }
            if (serialNumber == null && cardData.has("serial_number") && !cardData.get("serial_number").isNull()) {
                serialNumber = cardData.get("serial_number").asText();
            }
            
            // Require at least cardholder_id
            if (cardholderId == null || cardholderId.isEmpty()) {
                log.warn("Card data missing required customerId/cardholder_id, skipping. Card data: {}", 
                        cardData.has("id") ? cardData.get("id").asText() : "unknown");
                return null;
            }
            
            // Check if card already exists
            Optional<Card> existingCard = cardRepository.findByCardholderId(cardholderId);
            
            Card card;
            if (existingCard.isPresent()) {
                card = existingCard.get();
                String identifier = card.getSerialNumber() != null ? card.getSerialNumber() : cardholderId;
                log.debug("Card {} already exists, updating", identifier);
            } else {
                // Also check by serial_number if provided
                if (serialNumber != null && !serialNumber.isEmpty()) {
                    Optional<Card> existingBySerial = cardRepository.findBySerialNumber(serialNumber);
                    if (existingBySerial.isPresent()) {
                        card = existingBySerial.get();
                        log.debug("Card with serial_number {} already exists, updating", serialNumber);
                    } else {
                        card = Card.builder()
                                .merchantId(merchantId)
                                .serialNumber(serialNumber)
                                .cardholderId(cardholderId)
                                .templateId(config.getDefaultTemplateId()) // Set default template
                                .status("not_installed")
                                .build();
                    }
                } else {
                    card = Card.builder()
                            .merchantId(merchantId)
                            .cardholderId(cardholderId)
                            .templateId(config.getDefaultTemplateId()) // Set default template
                            .status("not_installed")
                            .build();
                }
            }
            
            // Update card fields from API data
            updateCardFromApiData(card, cardData, config);
            
            // Ensure template exists before saving card (required for foreign key)
            if (card.getTemplateId() != null) {
                ensureTemplateExists(merchantId, card.getTemplateId(), config);
            }
            
            card = cardRepository.save(card);
            String identifier = card.getSerialNumber() != null ? card.getSerialNumber() : cardholderId;
            log.debug("Card {} saved successfully", identifier);
            
            return card;
            
        } catch (Exception e) {
            String cardId = cardData.has("id") ? cardData.get("id").asText() : 
                           (cardData.has("customerId") ? cardData.get("customerId").asText() : "unknown");
            log.error("Error saving card {}: {}", cardId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Update Card entity from Boomerangme API data
     * Handles both list API format and webhook format
     */
    private void updateCardFromApiData(Card card, JsonNode cardData, IntegrationConfig config) {
        // Update serial number if present (list API uses "id", webhooks use "serial_number")
        if (cardData.has("id") && !cardData.get("id").isNull()) {
            card.setSerialNumber(cardData.get("id").asText());
        } else if (cardData.has("serial_number") && !cardData.get("serial_number").isNull()) {
            card.setSerialNumber(cardData.get("serial_number").asText());
        }
        
        // Update card type and template (list API uses "type", webhooks use "card_type")
        if (cardData.has("type") && !cardData.get("type").isNull()) {
            card.setCardType(cardData.get("type").asText());
        } else if (cardData.has("card_type") && !cardData.get("card_type").isNull()) {
            card.setCardType(cardData.get("card_type").asText());
        }
        
        // Set template_id from API data or use default from config
        if (cardData.has("templateId") && !cardData.get("templateId").isNull()) {
            card.setTemplateId(cardData.get("templateId").asInt());
        } else if (cardData.has("template_id") && !cardData.get("template_id").isNull()) {
            try {
                card.setTemplateId(cardData.get("template_id").asInt());
            } catch (Exception e) {
                log.warn("Could not parse template_id as integer: {}", cardData.get("template_id").asText());
                // Fallback to default template from config
                if (card.getTemplateId() == null && config != null && config.getDefaultTemplateId() != null) {
                    card.setTemplateId(config.getDefaultTemplateId());
                    log.info("Using default template {} for card {}", config.getDefaultTemplateId(), card.getCardholderId());
                } else if (card.getTemplateId() == null) {
                    throw new RuntimeException("Cannot set template_id: not in API response and no default_template_id in config for merchant: " + card.getMerchantId());
                }
            }
        } else {
            // No template_id in API response - use default from config
            if (card.getTemplateId() == null && config != null && config.getDefaultTemplateId() != null) {
                card.setTemplateId(config.getDefaultTemplateId());
                log.info("Using default template {} for card {} (not in API response)", config.getDefaultTemplateId(), card.getCardholderId());
            } else if (card.getTemplateId() == null) {
                throw new RuntimeException("Cannot set template_id: not in API response and no default_template_id in config for merchant: " + card.getMerchantId());
            }
        }
        
        // Update status
        if (cardData.has("status") && !cardData.get("status").isNull()) {
            String status = cardData.get("status").asText();
            card.setStatus(status);
            
            // Set installed_at if status is installed
            if ("installed".equals(status) && card.getInstalledAt() == null) {
                card.setInstalledAt(LocalDateTime.now());
            }
        }
        
        // Update device type (list API uses "device", webhooks use "device_type")
        if (cardData.has("device") && !cardData.get("device").isNull()) {
            card.setDeviceType(cardData.get("device").asText());
        } else if (cardData.has("device_type") && !cardData.get("device_type").isNull()) {
            card.setDeviceType(cardData.get("device_type").asText());
        }
        
        // Extract cardholder information from nested "customer" object (list API format)
        JsonNode customerNode = cardData.has("customer") ? cardData.get("customer") : null;
        
        if (customerNode != null) {
            // List API format - customer data is in nested object
            if (customerNode.has("email") && !customerNode.get("email").isNull()) {
                card.setCardholderEmail(customerNode.get("email").asText());
            }
            if (customerNode.has("phone") && !customerNode.get("phone").isNull()) {
                card.setCardholderPhone(customerNode.get("phone").asText());
            }
            if (customerNode.has("firstName") && !customerNode.get("firstName").isNull()) {
                card.setCardholderFirstName(customerNode.get("firstName").asText());
            }
            if (customerNode.has("surname") && !customerNode.get("surname").isNull()) {
                card.setCardholderLastName(customerNode.get("surname").asText());
            }
            if (customerNode.has("dateOfBirth") && !customerNode.get("dateOfBirth").isNull()) {
                try {
                    String birthDateStr = customerNode.get("dateOfBirth").asText();
                    card.setCardholderBirthDate(LocalDate.parse(birthDateStr));
                } catch (Exception e) {
                    log.warn("Failed to parse birth date: {}", e.getMessage());
                }
            }
        } else {
            // Webhook format - customer data is at root level with cardholder_ prefix
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
        }
        
        // Update card metrics from "balance" object (list API format)
        JsonNode balanceNode = cardData.has("balance") ? cardData.get("balance") : null;
        
        if (balanceNode != null && balanceNode.isObject()) {
            // List API format - balance data is in nested object
            if (balanceNode.has("bonusBalance") && !balanceNode.get("bonusBalance").isNull()) {
                card.setBonusBalance(balanceNode.get("bonusBalance").asInt());
            }
            if (balanceNode.has("numberStampsTotal") && !balanceNode.get("numberStampsTotal").isNull()) {
                card.setNumberStampsTotal(balanceNode.get("numberStampsTotal").asInt());
            }
            if (balanceNode.has("numberRewardsUnused") && !balanceNode.get("numberRewardsUnused").isNull()) {
                card.setNumberRewardsUnused(balanceNode.get("numberRewardsUnused").asInt());
            }
            if (balanceNode.has("balance") && !balanceNode.get("balance").isNull()) {
                card.setBalance(balanceNode.get("balance").asInt());
            }
        } else {
            // Webhook format - balance data is at root level
            if (cardData.has("bonus_balance") && !cardData.get("bonus_balance").isNull()) {
                card.setBonusBalance(cardData.get("bonus_balance").asInt());
            }
            if (cardData.has("number_stamps_total") && !cardData.get("number_stamps_total").isNull()) {
                card.setNumberStampsTotal(cardData.get("number_stamps_total").asInt());
            }
            if (cardData.has("number_rewards_unused") && !cardData.get("number_rewards_unused").isNull()) {
                card.setNumberRewardsUnused(cardData.get("number_rewards_unused").asInt());
            }
            if (cardData.has("balance") && !cardData.get("balance").isNull() && cardData.get("balance").isInt()) {
                card.setBalance(cardData.get("balance").asInt());
            }
        }
        
        // Update visit counts
        if (cardData.has("countVisits") && !cardData.get("countVisits").isNull()) {
            card.setCountVisits(cardData.get("countVisits").asInt());
        }
        
        // Update links (list API format uses different field names)
        if (cardData.has("shareLink") && !cardData.get("shareLink").isNull()) {
            card.setShareLink(cardData.get("shareLink").asText());
        } else if (cardData.has("share_link") && !cardData.get("share_link").isNull()) {
            card.setShareLink(cardData.get("share_link").asText());
        }
        
        if (cardData.has("installLink") && !cardData.get("installLink").isNull()) {
            card.setShortLink(cardData.get("installLink").asText());
        } else if (cardData.has("short_link") && !cardData.get("short_link").isNull()) {
            card.setShortLink(cardData.get("short_link").asText());
        }
        
        // Direct install links (list API uses "directInstallLink" object)
        JsonNode directInstallNode = cardData.has("directInstallLink") ? cardData.get("directInstallLink") : null;
        if (directInstallNode != null && directInstallNode.isObject()) {
            if (directInstallNode.has("universal") && !directInstallNode.get("universal").isNull()) {
                card.setInstallLinkUniversal(directInstallNode.get("universal").asText());
            }
            if (directInstallNode.has("apple") && !directInstallNode.get("apple").isNull()) {
                card.setInstallLinkApple(directInstallNode.get("apple").asText());
            }
            if (directInstallNode.has("google") && !directInstallNode.get("google").isNull()) {
                card.setInstallLinkGoogle(directInstallNode.get("google").asText());
            }
            if (directInstallNode.has("pwa") && !directInstallNode.get("pwa").isNull()) {
                card.setInstallLinkPwa(directInstallNode.get("pwa").asText());
            }
        } else {
            // Webhook format
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
        }
        
        // Update tracking data
        if (cardData.has("utmSource") && !cardData.get("utmSource").isNull()) {
            card.setUtmSource(cardData.get("utmSource").asText());
        } else if (cardData.has("utm_source") && !cardData.get("utm_source").isNull()) {
            card.setUtmSource(cardData.get("utm_source").asText());
        }
        
        if (cardData.has("latitude") && !cardData.get("latitude").isNull()) {
            card.setLatitude(cardData.get("latitude").asDouble());
        }
        if (cardData.has("longitude") && !cardData.get("longitude").isNull()) {
            card.setLongitude(cardData.get("longitude").asDouble());
        }
        
        // Store custom_fields as JSON string (list API uses "customFields")
        if (cardData.has("customFields") && !cardData.get("customFields").isNull()) {
            try {
                card.setCustomFields(objectMapper.writeValueAsString(cardData.get("customFields")));
            } catch (Exception e) {
                log.warn("Failed to serialize customFields: {}", e.getMessage());
            }
        } else if (cardData.has("custom_fields") && !cardData.get("custom_fields").isNull()) {
            try {
                card.setCustomFields(objectMapper.writeValueAsString(cardData.get("custom_fields")));
            } catch (Exception e) {
                log.warn("Failed to serialize custom_fields: {}", e.getMessage());
            }
        }
        
        // Set issued_at from createdAt if available
        if (cardData.has("createdAt") && !cardData.get("createdAt").isNull()) {
            try {
                String createdAtStr = cardData.get("createdAt").asText();
                card.setIssuedAt(LocalDateTime.parse(createdAtStr.replace("+00:00", "").replace("Z", "")));
            } catch (Exception e) {
                log.debug("Could not parse createdAt: {}", e.getMessage());
                if (card.getIssuedAt() == null) {
                    card.setIssuedAt(LocalDateTime.now());
                }
            }
        } else if (card.getIssuedAt() == null) {
            card.setIssuedAt(LocalDateTime.now());
        }
        
        card.setSyncedAt(LocalDateTime.now());
    }

    /**
     * Link card to Treez customer based on phone number
     * If customer not found in Treez, creates a new one
     * 
     * @param config Integration configuration
     * @param card Card to link
     * @return true if successfully linked, false otherwise
     */
    private boolean linkCardToTreezCustomer(IntegrationConfig config, Card card) {
        String merchantId = config.getMerchantId();
        String cardId = card.getSerialNumber() != null ? card.getSerialNumber() : card.getCardholderId();
        
        // Only process for Treez integrations
        if (config.getIntegrationType() != IntegrationType.TREEZ) {
            log.debug("Skipping Treez customer linking for non-Treez integration");
            return false;
        }
        
        try {
            // Validate we have required phone number for matching
            if (card.getCardholderPhone() == null || card.getCardholderPhone().isEmpty()) {
                log.debug("Card {} has no phone for matching, skipping customer linking", cardId);
                return false;
            }
            
            // Check if customer already exists in local database
            Customer existingCustomer = findExistingTreezCustomer(merchantId, card);
            
            if (existingCustomer != null) {
                // Link card to existing customer
                if (existingCustomer.getCard() == null || !existingCustomer.getCard().getId().equals(card.getId())) {
                    existingCustomer.setCard(card);
                    existingCustomer.setSyncedAt(LocalDateTime.now());
                    customerRepository.save(existingCustomer);
                    log.info("Linked card {} to existing Treez customer {}", cardId, existingCustomer.getId());
                    return true;
                } else {
                    log.debug("Card {} already linked to customer {}", cardId, existingCustomer.getId());
                    return true;
                }
            } else {
                // Try to find customer in Treez POS
                Customer treezCustomer = findCustomerInTreezPOS(config, card);
                
                if (treezCustomer != null) {
                    // Save customer with link to card
                    treezCustomer = customerRepository.save(treezCustomer);
                    log.info("Found and linked Treez customer {} to card {}", 
                            treezCustomer.getExternalCustomerId(), cardId);
                    return true;
                } else {
                    // Customer not found in Treez - create new one if we have required data
                    log.info("Customer not found in Treez for card {}, attempting to create new customer", cardId);
                    Customer newCustomer = createTreezCustomer(config, card);
                    
                    if (newCustomer != null) {
                        newCustomer = customerRepository.save(newCustomer);
                        log.info("Created new Treez customer {} and linked to card {}", 
                                newCustomer.getExternalCustomerId(), cardId);
                        return true;
                    } else {
                        log.warn("Failed to create Treez customer for card {} (missing required data)", cardId);
                        return false;
                    }
                }
            }
            
        } catch (Exception e) {
            log.error("Error linking card {} to Treez customer: {}", cardId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Find existing Treez customer in local database by phone number
     */
    private Customer findExistingTreezCustomer(String merchantId, Card card) {
        String phone = card.getCardholderPhone();
        if (phone != null && !phone.isEmpty()) {
            // Normalize phone from Boomerangme format (remove "1" prefix)
            String normalizedPhone = normalizePhoneFromBoomerangme(phone);
            
            Optional<Customer> byPhone = customerRepository.findByMerchantIdAndTreezPhoneAndIntegrationType(
                    merchantId, normalizedPhone, IntegrationType.TREEZ);
            if (byPhone.isPresent()) {
                log.debug("Found existing Treez customer by phone: {}", normalizedPhone);
                return byPhone.get();
            }
            
            // Also try original phone
            if (!phone.equals(normalizedPhone)) {
                Optional<Customer> byOriginalPhone = customerRepository.findByMerchantIdAndTreezPhoneAndIntegrationType(
                        merchantId, phone, IntegrationType.TREEZ);
                if (byOriginalPhone.isPresent()) {
                    log.debug("Found existing Treez customer by original phone: {}", phone);
                    return byOriginalPhone.get();
                }
            }
        }
        
        return null;
    }

    /**
     * Find customer in Treez POS system by phone number
     */
    private Customer findCustomerInTreezPOS(IntegrationConfig config, Card card) {
        try {
            if (config.getTreezApiKey() == null || config.getTreezDispensaryId() == null) {
                log.warn("Treez API credentials not configured");
                return null;
            }
            
            String phone = card.getCardholderPhone();
            if (phone == null || phone.isEmpty()) {
                log.debug("Card has no phone number, cannot search Treez");
                return null;
            }
            
            // Normalize phone for Treez API (10 digits, no "1" prefix)
            String normalizedPhone = normalizePhoneForTreez(phone);
            if (normalizedPhone == null || normalizedPhone.replaceAll("[^0-9]", "").length() != 10) {
                log.warn("Phone number {} cannot be normalized to 10 digits for Treez search", phone);
                return null;
            }
            
            String phoneDigits = normalizedPhone.replaceAll("[^0-9]", "");
            JsonNode treezCustomerData = treezApiClient.findCustomerByPhone(config, phoneDigits).block();
            
            if (treezCustomerData != null) {
                // Check if response has data array (Treez wraps results in data array)
                JsonNode dataArray = treezCustomerData.has("data") ? treezCustomerData.get("data") : null;
                JsonNode customerNode = null;
                
                if (dataArray != null && dataArray.isArray() && dataArray.size() > 0) {
                    customerNode = dataArray.get(0);  // Get first customer from array
                } else {
                    customerNode = treezCustomerData;  // Use response directly
                }
                
                // Extract customer ID from response
                String treezCustomerId = extractCustomerId(customerNode);
                
                if (treezCustomerId != null) {
                    // Create customer entity
                    Customer customer = Customer.builder()
                            .merchantId(config.getMerchantId())
                            .integrationType(IntegrationType.TREEZ)
                            .externalCustomerId(treezCustomerId)
                            .card(card)
                            .treezEmail(card.getCardholderEmail())
                            .treezPhone(normalizePhoneForTreez(card.getCardholderPhone()))
                            .treezFirstName(card.getCardholderFirstName())
                            .treezLastName(card.getCardholderLastName())
                            .treezBirthDate(card.getCardholderBirthDate())
                            .totalPoints(card.getBonusBalance() != null ? card.getBonusBalance() : 0)
                            .syncedAt(LocalDateTime.now())
                            .build();
                    
                    log.info("Found Treez customer {} for card {}", treezCustomerId, 
                            card.getSerialNumber() != null ? card.getSerialNumber() : card.getCardholderId());
                    
                    return customer;
                }
            }
            
        } catch (ApiException e) {
            if (e.getStatusCode() != 404) {
                log.warn("Error finding customer in Treez POS: {} - {}", e.getStatusCode(), e.getResponseBody());
            } else {
                log.debug("Customer not found in Treez by phone: {}", card.getCardholderPhone());
            }
        } catch (Exception e) {
            log.warn("Error finding customer in Treez POS: {}", e.getMessage());
        }
        
        return null;
    }

    /**
     * Extract customer ID from Treez API response
     */
    private String extractCustomerId(JsonNode response) {
        if (response == null) {
            return null;
        }
        
        if (response.has("customer_id") && !response.get("customer_id").isNull()) {
            return response.get("customer_id").asText();
        } else if (response.has("id") && !response.get("id").isNull()) {
            return response.get("id").asText();
        } else if (response.has("data")) {
            JsonNode data = response.get("data");
            if (data.has("customer_id") && !data.get("customer_id").isNull()) {
                return data.get("customer_id").asText();
            } else if (data.has("id") && !data.get("id").isNull()) {
                return data.get("id").asText();
            }
        }
        
        log.warn("Could not extract customer_id from Treez response: {}", response.toString());
        return null;
    }

    /**
     * Normalize phone number from Boomerangme format to Treez format
     * Boomerangme stores US phone numbers with country code "1" prefix
     * Treez stores phone numbers without country code
     */
    private String normalizePhoneFromBoomerangme(String phone) {
        if (phone == null || phone.isEmpty()) {
            return phone;
        }
        
        String digitsOnly = phone.replaceAll("[^0-9]", "");
        
        if (digitsOnly.startsWith("1") && digitsOnly.length() == 11) {
            return digitsOnly.substring(1);
        }
        
        return phone;
    }

    /**
     * Normalize phone number for Treez API (10 digits, no "1" prefix)
     */
    private String normalizePhoneForTreez(String phone) {
        if (phone == null || phone.isEmpty()) {
            return phone;
        }
        
        String digitsOnly = phone.replaceAll("[^0-9]", "");
        
        if (digitsOnly.startsWith("1") && digitsOnly.length() == 11) {
            return digitsOnly.substring(1);
        }
        
        if (digitsOnly.length() == 10) {
            return digitsOnly;
        }
        
        return phone;
    }
    
    /**
     * Create a new customer in Treez POS
     * 
     * @param config Integration configuration
     * @param card Card with customer information
     * @return Customer entity if created successfully, null otherwise
     */
    private Customer createTreezCustomer(IntegrationConfig config, Card card) {
        String cardId = card.getSerialNumber() != null ? card.getSerialNumber() : card.getCardholderId();
        String merchantId = config.getMerchantId();
        
        try {
            // Validate required fields for Treez customer creation
            if (card.getCardholderBirthDate() == null) {
                log.warn("Cannot create Treez customer: birthday is required but missing for card {}", cardId);
                return null;
            }
            
            if (card.getCardholderFirstName() == null || card.getCardholderFirstName().isEmpty()) {
                log.warn("Cannot create Treez customer: first_name is required but missing for card {}", cardId);
                return null;
            }
            
            if (card.getCardholderLastName() == null || card.getCardholderLastName().isEmpty()) {
                log.warn("Cannot create Treez customer: last_name is required but missing for card {}", cardId);
                return null;
            }
            
            // Prepare customer data for Treez API
            Map<String, Object> customerData = new HashMap<>();
            
            // Required fields
            customerData.put("birthday", card.getCardholderBirthDate().toString()); // yyyy-MM-dd format
            customerData.put("first_name", card.getCardholderFirstName());
            customerData.put("last_name", card.getCardholderLastName());
            customerData.put("patient_type", "ADULT"); // Default to ADULT
            
            // Optional but recommended fields
            if (card.getCardholderEmail() != null && !card.getCardholderEmail().isEmpty()) {
                customerData.put("email", card.getCardholderEmail());
            }
            
            // Normalize phone number (remove "1" prefix, ensure 10 digits)
            String normalizedPhone = normalizePhoneForTreez(card.getCardholderPhone());
            if (normalizedPhone != null && !normalizedPhone.isEmpty()) {
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
            customerData.put("drivers_license", "N/A"); // Required field
            customerData.put("drivers_license_expiration", "2030-12-31"); // Required field with drivers_license
            
            // Optional: rewards balance from Boomerangme
            if (card.getBonusBalance() != null && card.getBonusBalance() > 0) {
                customerData.put("rewards_balance", card.getBonusBalance());
            }
            
            log.info("Creating Treez customer for card {}: email={}, phone={}, name={} {}",
                    cardId, card.getCardholderEmail(), normalizedPhone, 
                    card.getCardholderFirstName(), card.getCardholderLastName());
            
            // Call Treez API to create customer
            JsonNode response = treezApiClient.createCustomer(config, customerData).block();
            
            if (response == null) {
                log.error("Null response from Treez API when creating customer for card {}", cardId);
                return null;
            }
            
            // Check if creation was successful
            if (response.has("resultCode") && !"SUCCESS".equals(response.get("resultCode").asText())) {
                String reason = response.has("resultReason") ? response.get("resultReason").asText() : "Unknown error";
                log.error("Treez customer creation failed for card {}: {}", cardId, reason);
                return null;
            }
            
            // Extract customer data from response
            JsonNode dataArray = response.has("data") ? response.get("data") : null;
            JsonNode customerNode = null;
            
            if (dataArray != null && dataArray.isArray() && dataArray.size() > 0) {
                customerNode = dataArray.get(0);
            } else if (response.has("customer_id") || response.has("id")) {
                customerNode = response;
            }
            
            if (customerNode == null) {
                log.error("Could not extract customer data from Treez response for card {}", cardId);
                return null;
            }
            
            // Extract Treez customer ID
            String treezCustomerId = extractCustomerId(customerNode);
            
            if (treezCustomerId == null || treezCustomerId.isEmpty()) {
                log.error("Could not extract customer_id from Treez response for card {}", cardId);
                return null;
            }
            
            // Create customer entity
            Customer customer = Customer.builder()
                    .merchantId(merchantId)
                    .integrationType(IntegrationType.TREEZ)
                    .externalCustomerId(treezCustomerId)
                    .card(card)
                    .treezEmail(card.getCardholderEmail())
                    .treezPhone(normalizedPhone)
                    .treezFirstName(card.getCardholderFirstName())
                    .treezLastName(card.getCardholderLastName())
                    .treezBirthDate(card.getCardholderBirthDate())
                    .totalPoints(card.getBonusBalance() != null ? card.getBonusBalance() : 0)
                    .syncedAt(LocalDateTime.now())
                    .build();
            
            log.info("Successfully created Treez customer {} for card {}", treezCustomerId, cardId);
            return customer;
            
        } catch (Exception e) {
            log.error("Error creating Treez customer for card {}: {}", cardId, e.getMessage(), e);
            return null;
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
            // Don't throw - let the card save fail with FK constraint error
            // This provides better error message
            throw new RuntimeException("Template " + templateId + " does not exist and could not be fetched from Boomerangme API: " + e.getMessage(), e);
        }
    }
}
