package heymary.co.integrations.service;

import com.fasterxml.jackson.databind.JsonNode;
import heymary.co.integrations.exception.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class BoomerangmeApiClient {

    private final WebClient webClient;

    public BoomerangmeApiClient(@Qualifier("boomerangmeWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * Add points to a customer's loyalty card (Legacy method - use addScoresToCard instead)
     * POST /api/v2/cards/{cardId}/points/add
     */
    public Mono<JsonNode> addPoints(String apiKey, String cardId, Integer points, String reason) {
        log.debug("Adding {} points to card {} with reason: {}", points, cardId, reason);
        
        Map<String, Object> requestBody = Map.of(
            "points", points,
            "comment", reason != null ? reason : "Points from order"
        );

        return webClient.post()
                .uri("/api/v2/cards/{cardId}/points/add", cardId)
                .header("X-API-Key", apiKey)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(status -> status.isError(), response -> {
                    return response.bodyToMono(String.class)
                            .flatMap(body -> {
                                log.error("Boomerangme API error: {} - {}", response.statusCode(), body);
                                return Mono.error(new ApiException(
                                    "Failed to add points: " + response.statusCode(),
                                    response.statusCode().value(),
                                    body
                                ));
                            });
                })
                .bodyToMono(JsonNode.class)
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(1))
                        .filter(throwable -> throwable instanceof ApiException apiEx && 
                                (apiEx.getStatusCode() >= 500 || apiEx.getStatusCode() == 0))
                        .doBeforeRetry(retrySignal -> 
                            log.warn("Retrying Boomerangme API call, attempt: {}", retrySignal.totalRetries() + 1))
                )
                .doOnSuccess(response -> log.debug("Successfully added points to card {}", cardId))
                .doOnError(error -> log.error("Error adding points to card {}: {}", cardId, error.getMessage()));
    }

    /**
     * Create a new customer in Boomerangme
     * POST /api/v2/customers
     * 
     * @param apiKey Boomerangme API key
     * @param customerData Customer information (firstName, surname, phone, email, gender, dateOfBirth, externalUserId)
     * @return JsonNode with customer details including id
     */
    public Mono<JsonNode> createCustomer(String apiKey, Map<String, Object> customerData) {
        String email = (String) customerData.getOrDefault("email", "unknown");
        log.info("Creating Boomerangme customer with email: {}", email);
        
        return webClient.post()
                .uri("/api/v2/customers")
                .header("X-API-Key", apiKey)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(customerData)
                .retrieve()
                .onStatus(status -> status.isError(), response -> {
                    return response.bodyToMono(String.class)
                            .flatMap(body -> {
                                log.error("Boomerangme API error creating customer: {} - {}", response.statusCode(), body);
                                return Mono.error(new ApiException(
                                    "Failed to create customer: " + response.statusCode(),
                                    response.statusCode().value(),
                                    body
                                ));
                            });
                })
                .bodyToMono(JsonNode.class)
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(1))
                        .filter(throwable -> throwable instanceof ApiException apiEx && 
                                (apiEx.getStatusCode() >= 500 || apiEx.getStatusCode() == 0))
                        .doBeforeRetry(retrySignal -> 
                            log.warn("Retrying customer creation, attempt: {}", retrySignal.totalRetries() + 1))
                )
                .doOnSuccess(response -> {
                    String customerId = response.has("id") ? response.get("id").asText() : "unknown";
                    log.info("Successfully created Boomerangme customer: {}", customerId);
                })
                .doOnError(error -> log.error("Error creating customer for {}: {}", email, error.getMessage()));
    }

    /**
     * Create a new customer/card in Boomerangme (Issue card) - DEPRECATED
     * POST /api/v2/templates/{templateId}/customers
     * 
     * @deprecated Use createCustomer instead. Cards are installed manually by customers.
     * @param apiKey Boomerangme API key
     * @param templateId Boomerangme template ID (from program)
     * @param cardholderData Cardholder information (phone, email, firstName, lastName, dateOfBirth)
     * @return JsonNode with card details including id (serial number), customerId, etc.
     */
    @Deprecated
    public Mono<JsonNode> createCard(String apiKey, Integer templateId, Map<String, Object> cardholderData) {
        String email = (String) cardholderData.getOrDefault("email", "unknown");
        log.info("Creating Boomerangme card for template {} with email: {}", templateId, email);
        
        return webClient.post()
                .uri("/api/v2/templates/{templateId}/customers", templateId)
                .header("X-API-Key", apiKey)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(cardholderData)
                .retrieve()
                .onStatus(status -> status.isError(), response -> {
                    return response.bodyToMono(String.class)
                            .flatMap(body -> {
                                log.error("Boomerangme API error creating card: {} - {}", response.statusCode(), body);
                                return Mono.error(new ApiException(
                                    "Failed to create card: " + response.statusCode(),
                                    response.statusCode().value(),
                                    body
                                ));
                            });
                })
                .bodyToMono(JsonNode.class)
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(1))
                        .filter(throwable -> throwable instanceof ApiException apiEx && 
                                (apiEx.getStatusCode() >= 500 || apiEx.getStatusCode() == 0))
                        .doBeforeRetry(retrySignal -> 
                            log.warn("Retrying card creation, attempt: {}", retrySignal.totalRetries() + 1))
                )
                .doOnSuccess(response -> {
                    String cardId = response.has("id") ? response.get("id").asText() : "unknown";
                    log.info("Successfully created Boomerangme card: {}", cardId);
                })
                .doOnError(error -> log.error("Error creating card for {}: {}", email, error.getMessage()));
    }

    /**
     * Search/list cards by email
     * GET /api/v2/cards?customerEmail={email}
     */
    public Mono<JsonNode> searchCardsByEmail(String apiKey, String email) {
        log.debug("Searching for cards with email: {}", email);
        
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v2/cards")
                        .queryParam("customerEmail", email)
                        .build())
                .header("X-API-Key", apiKey)
                .retrieve()
                .onStatus(status -> status.isError(), response -> {
                    return response.bodyToMono(String.class)
                            .flatMap(body -> {
                                log.error("Boomerangme API error searching cards: {} - {}", response.statusCode(), body);
                                return Mono.error(new ApiException(
                                    "Failed to search cards: " + response.statusCode(),
                                    response.statusCode().value(),
                                    body
                                ));
                            });
                })
                .bodyToMono(JsonNode.class)
                .doOnSuccess(response -> log.debug("Card search completed"))
                .doOnError(error -> log.error("Error searching cards by email {}: {}", email, error.getMessage()));
    }

    /**
     * Search/list cards by phone number
     * GET /api/v2/cards?customerPhone={phone}
     */
    public Mono<JsonNode> searchCardsByPhone(String apiKey, String phone) {
        log.debug("Searching for cards with phone: {}", phone);
        
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v2/cards")
                        .queryParam("customerPhone", phone)
                        .build())
                .header("X-API-Key", apiKey)
                .retrieve()
                .onStatus(status -> status.isError(), response -> {
                    return response.bodyToMono(String.class)
                            .flatMap(body -> {
                                log.error("Boomerangme API error searching cards: {} - {}", response.statusCode(), body);
                                return Mono.error(new ApiException(
                                    "Failed to search cards: " + response.statusCode(),
                                    response.statusCode().value(),
                                    body
                                ));
                            });
                })
                .bodyToMono(JsonNode.class)
                .doOnSuccess(response -> log.debug("Card search by phone completed"))
                .doOnError(error -> log.error("Error searching cards by phone {}: {}", phone, error.getMessage()));
    }

    /**
     * Get card details by card ID
     * GET /api/v2/cards/{cardId}
     */
    public Mono<JsonNode> getCard(String apiKey, String cardId) {
        log.debug("Getting card details for card: {}", cardId);
        
        return webClient.get()
                .uri("/api/v2/cards/{cardId}", cardId)
                .header("X-API-Key", apiKey)
                .retrieve()
                .onStatus(status -> status.isError(), response -> {
                    return response.bodyToMono(String.class)
                            .flatMap(body -> {
                                log.error("Boomerangme API error getting card: {} - {}", response.statusCode(), body);
                                return Mono.error(new ApiException(
                                    "Failed to get card: " + response.statusCode(),
                                    response.statusCode().value(),
                                    body
                                ));
                            });
                })
                .bodyToMono(JsonNode.class)
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(1))
                        .filter(throwable -> throwable instanceof ApiException apiEx && 
                                (apiEx.getStatusCode() >= 500 || apiEx.getStatusCode() == 0))
                )
                .doOnSuccess(response -> log.debug("Successfully retrieved card {}", cardId))
                .doOnError(error -> log.error("Error getting card {}: {}", cardId, error.getMessage()));
    }

    /**
     * Add scores (points) to card - For Reward cards with Points mechanics
     * POST /api/v2/cards/{serial_number}/add-scores
     * 
     * @param apiKey Boomerangme API key
     * @param serialNumber Card serial number
     * @param scores Points to add
     * @param comment Optional comment/reason for adding points
     * @param purchaseSum Purchase amount (order total) - optional, can be null
     * @return JsonNode with updated card details (wrapped in data field)
     */
    public Mono<JsonNode> addScoresToCard(String apiKey, String serialNumber, Integer scores, String comment, BigDecimal purchaseSum) {
        if (purchaseSum != null) {
            log.info("Adding {} scores to card {} with comment: {} (purchase: ${})", scores, serialNumber, comment, purchaseSum);
        } else {
            log.info("Adding {} scores to card {} with comment: {}", scores, serialNumber, comment);
        }
        
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("scores", scores);
        requestBody.put("comment", comment != null ? comment : "Points from purchase");
        if (purchaseSum != null) {
            requestBody.put("purchaseSum", purchaseSum.doubleValue());
        }

        return webClient.post()
                .uri("/api/v2/cards/{serialNumber}/add-scores", serialNumber)
                .header("X-API-Key", apiKey)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(status -> status.isError(), response -> {
                    return response.bodyToMono(String.class)
                            .flatMap(body -> {
                                log.error("Boomerangme API error adding scores: {} - {}", response.statusCode(), body);
                                return Mono.error(new ApiException(
                                    "Failed to add scores: " + response.statusCode(),
                                    response.statusCode().value(),
                                    body
                                ));
                            });
                })
                .bodyToMono(JsonNode.class)
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(1))
                        .filter(throwable -> throwable instanceof ApiException apiEx && 
                                (apiEx.getStatusCode() >= 500 || apiEx.getStatusCode() == 0))
                        .doBeforeRetry(retrySignal -> 
                            log.warn("Retrying add scores, attempt: {}", retrySignal.totalRetries() + 1))
                )
                .doOnSuccess(response -> {
                    // Log response structure for debugging
                    if (response.has("data")) {
                        JsonNode data = response.get("data");
                        if (data.has("balance") && data.get("balance").has("bonusBalance")) {
                            int newBalance = data.get("balance").get("bonusBalance").asInt();
                            log.info("Successfully added {} scores to card {}. New balance: {}", scores, serialNumber, newBalance);
                        } else {
                            log.info("Successfully added {} scores to card {}", scores, serialNumber);
                        }
                    } else {
                        log.info("Successfully added {} scores to card {}", scores, serialNumber);
                    }
                })
                .doOnError(error -> log.error("Error adding scores to card {}: {}", serialNumber, error.getMessage()));
    }

    /**
     * Subtract scores (points) from card - For redemptions or reversals
     * POST /api/v2/cards/{cardId}/points/subtract
     * 
     * @param apiKey Boomerangme API key
     * @param cardId Card ID (serial number)
     * @param scores Points to subtract
     * @param comment Optional comment/reason for subtracting points
     * @return JsonNode with updated card details
     */
    public Mono<JsonNode> subtractScoresFromCard(String apiKey, String cardId, Integer scores, String comment) {
        log.info("Subtracting {} scores from card {} with comment: {}", scores, cardId, comment);
        
        Map<String, Object> requestBody = Map.of(
            "points", scores,
            "comment", comment != null ? comment : "Points redemption"
        );

        return webClient.post()
                .uri("/api/v2/cards/{cardId}/points/subtract", cardId)
                .header("X-API-Key", apiKey)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(status -> status.isError(), response -> {
                    return response.bodyToMono(String.class)
                            .flatMap(body -> {
                                log.error("Boomerangme API error subtracting scores: {} - {}", response.statusCode(), body);
                                return Mono.error(new ApiException(
                                    "Failed to subtract scores: " + response.statusCode(),
                                    response.statusCode().value(),
                                    body
                                ));
                            });
                })
                .bodyToMono(JsonNode.class)
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(1))
                        .filter(throwable -> throwable instanceof ApiException apiEx && 
                                (apiEx.getStatusCode() >= 500 || apiEx.getStatusCode() == 0))
                        .doBeforeRetry(retrySignal -> 
                            log.warn("Retrying subtract scores, attempt: {}", retrySignal.totalRetries() + 1))
                )
                .doOnSuccess(response -> log.info("Successfully subtracted {} scores from card {}", scores, cardId))
                .doOnError(error -> log.error("Error subtracting scores from card {}: {}", cardId, error.getMessage()));
    }

    /**
     * Update customer information on a card
     * PUT /api/v2/cards/{cardId}
     */
    public Mono<JsonNode> updateCard(String apiKey, String cardId, Map<String, Object> customerData) {
        log.debug("Updating card: {}", cardId);
        
        return webClient.put()
                .uri("/api/v2/cards/{cardId}", cardId)
                .header("X-API-Key", apiKey)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(customerData)
                .retrieve()
                .onStatus(status -> status.isError(), response -> {
                    return response.bodyToMono(String.class)
                            .flatMap(body -> {
                                log.error("Boomerangme API error updating card: {} - {}", response.statusCode(), body);
                                return Mono.error(new ApiException(
                                    "Failed to update card: " + response.statusCode(),
                                    response.statusCode().value(),
                                    body
                                ));
                            });
                })
                .bodyToMono(JsonNode.class)
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(1))
                        .filter(throwable -> throwable instanceof ApiException apiEx && 
                                (apiEx.getStatusCode() >= 500 || apiEx.getStatusCode() == 0))
                )
                .doOnSuccess(response -> log.debug("Successfully updated card {}", cardId))
                .doOnError(error -> log.error("Error updating card {}: {}", cardId, error.getMessage()));
    }
}

