package heymary.co.integrations.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import heymary.co.integrations.exception.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Map;

@Slf4j
@Service
public class BoomerangmeApiClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public BoomerangmeApiClient(@Qualifier("boomerangmeWebClient") WebClient webClient, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Add points to a customer's loyalty card
     */
    public Mono<JsonNode> addPoints(String apiKey, String cardId, Integer points, String reason) {
        log.debug("Adding {} points to card {} with reason: {}", points, cardId, reason);
        
        Map<String, Object> requestBody = Map.of(
            "points", points,
            "reason", reason != null ? reason : "Points from order"
        );

        return webClient.post()
                .uri("/api/v2/cards/{cardId}/points", cardId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
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
     * Create a new customer/card in Boomerangme
     */
    public Mono<JsonNode> createCard(String apiKey, String programId, Map<String, Object> customerData) {
        log.debug("Creating card for customer: {}", customerData.get("email"));
        
        Map<String, Object> requestBody = Map.of(
            "programId", programId,
            "customer", customerData
        );

        return webClient.post()
                .uri("/api/v2/cards")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(requestBody)
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
                )
                .doOnSuccess(response -> log.debug("Successfully created card for customer"))
                .doOnError(error -> log.error("Error creating card: {}", error.getMessage()));
    }

    /**
     * Get card details by card ID
     */
    public Mono<JsonNode> getCard(String apiKey, String cardId) {
        log.debug("Getting card details for card: {}", cardId);
        
        return webClient.get()
                .uri("/api/v2/cards/{cardId}", cardId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
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
     * Update customer information on a card
     */
    public Mono<JsonNode> updateCard(String apiKey, String cardId, Map<String, Object> customerData) {
        log.debug("Updating card: {}", cardId);
        
        return webClient.put()
                .uri("/api/v2/cards/{cardId}", cardId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
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

