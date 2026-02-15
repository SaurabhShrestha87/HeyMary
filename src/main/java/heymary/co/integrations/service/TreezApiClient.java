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

import java.time.Duration;
import java.util.Map;

@Slf4j
@Service
public class TreezApiClient {

    private final WebClient webClient;
    private final TreezTokenService tokenService;

    public TreezApiClient(@Qualifier("treezWebClient") WebClient webClient, 
                         TreezTokenService tokenService) {
        this.webClient = webClient;
        this.tokenService = tokenService;
    }

    /**
     * Create a new customer in Treez POS
     * POST /v2.0/dispensary/{dispensaryId}/customer/detailcustomer
     * 
     * @param config Integration configuration (used to get token)
     * @param customerData Customer information map
     * @return JsonNode with customer details including customer ID
     */
    public Mono<JsonNode> createCustomer(heymary.co.integrations.model.IntegrationConfig config, Map<String, Object> customerData) {
        String email = (String) customerData.getOrDefault("email", "unknown");
        log.info("Creating Treez customer with email: {}", email);
        
        // Get access token
        String accessToken = tokenService.getAccessToken(config);
        String clientId = config.getTreezClientId();
        String dispensaryId = config.getTreezDispensaryId();
        
        return webClient.post()
                .uri("/v2.0/dispensary/{dispensaryId}/customer/detailcustomer", dispensaryId)
                .header(HttpHeaders.AUTHORIZATION, accessToken)  // Token already includes "Bearer" prefix if needed
                .header("client_id", clientId)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(customerData)
                .retrieve()
                .onStatus(status -> status.isError(), response -> {
                    return response.bodyToMono(String.class)
                            .flatMap(body -> {
                                log.error("Treez API error creating customer: {} - {}", response.statusCode(), body);
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
                )
                .doOnSuccess(response -> log.debug("Successfully created Treez customer"))
                .doOnError(error -> log.error("Error creating Treez customer: {}", error.getMessage()));
    }

    /**
     * Update an existing customer in Treez POS (e.g. customer_groups, notes)
     * PATCH /v2.0/dispensary/{dispensaryId}/customer/update/{customerId}
     */
    public Mono<JsonNode> updateCustomer(heymary.co.integrations.model.IntegrationConfig config,
                                        String customerId, Map<String, Object> customerData) {
        log.debug("Updating Treez customer {} with fields: {}", customerId, customerData.keySet());

        String accessToken = tokenService.getAccessToken(config);
        String clientId = config.getTreezClientId();
        String dispensaryId = config.getTreezDispensaryId();

        return webClient.patch()
                .uri("/v2.0/dispensary/{dispensaryId}/customer/update/{customerId}", dispensaryId, customerId)
                .header(HttpHeaders.AUTHORIZATION, accessToken)
                .header("client_id", clientId)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(customerData)
                .retrieve()
                .onStatus(status -> status.isError(), response -> {
                    return response.bodyToMono(String.class)
                            .flatMap(body -> {
                                log.error("Treez API error updating customer: {} - {}", response.statusCode(), body);
                                return Mono.error(new ApiException(
                                        "Failed to update customer: " + response.statusCode(),
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
                .doOnSuccess(response -> log.debug("Successfully updated Treez customer {}", customerId))
                .doOnError(error -> log.error("Error updating Treez customer {}: {}", customerId, error.getMessage()));
    }

    /**
     * Get customer by ID in Treez
     * GET /v2.0/dispensary/{dispensaryId}/customer/{customerId}
     *
     * @param config     Integration configuration (used to get token)
     * @param customerId Treez customer ID
     * @return JsonNode with customer details (may be wrapped in data array)
     */
    public Mono<JsonNode> getCustomer(heymary.co.integrations.model.IntegrationConfig config, String customerId) {
        log.debug("Fetching Treez customer by ID: {}", customerId);

        String accessToken = tokenService.getAccessToken(config);
        String clientId = config.getTreezClientId();
        String dispensaryId = config.getTreezDispensaryId();

        return webClient.get()
                .uri("/v2.0/dispensary/{dispensaryId}/customer/{customerId}", dispensaryId, customerId)
                .header(HttpHeaders.AUTHORIZATION, accessToken)
                .header("client_id", clientId)
                .retrieve()
                .onStatus(status -> status.isError(), response -> {
                    return response.bodyToMono(String.class)
                            .flatMap(body -> {
                                if (response.statusCode().value() == 404) {
                                    log.debug("Treez customer not found by ID: {}", customerId);
                                    return Mono.error(new ApiException(
                                            "Customer not found",
                                            response.statusCode().value(),
                                            body
                                    ));
                                }
                                log.error("Treez API error fetching customer: {} - {}", response.statusCode(), body);
                                return Mono.error(new ApiException(
                                        "Failed to fetch customer: " + response.statusCode(),
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
                .doOnSuccess(response -> log.debug("Successfully fetched Treez customer {}", customerId))
                .doOnError(error -> {
                    if (error instanceof ApiException apiEx && apiEx.getStatusCode() == 404) {
                        log.debug("Treez customer not found by ID: {}", customerId);
                    } else {
                        log.error("Error fetching Treez customer {}: {}", customerId, error.getMessage());
                    }
                });
    }

    /**
     * Find customer by phone number in Treez
     * GET /v2.0/dispensary/{dispensaryId}/customer/phone/{phone}
     * 
     * @param config Integration configuration (used to get token)
     * @param phone Phone number (10 digits, normalized)
     * @return JsonNode with customer details if found
     */
    public Mono<JsonNode> findCustomerByPhone(heymary.co.integrations.model.IntegrationConfig config, String phone) {
        log.debug("Searching Treez customer by phone: {}", phone);
        
        // Get access token
        String accessToken = tokenService.getAccessToken(config);
        String clientId = config.getTreezClientId();
        String dispensaryId = config.getTreezDispensaryId();
        
        return webClient.get()
                .uri("/v2.0/dispensary/{dispensaryId}/customer/phone/{phone}", dispensaryId, phone)
                .header(HttpHeaders.AUTHORIZATION, accessToken)
                .header("client_id", clientId)
                .retrieve()
                .onStatus(status -> status.isError(), response -> {
                    return response.bodyToMono(String.class)
                            .flatMap(body -> {
                                if (response.statusCode().value() == 404) {
                                    log.debug("Treez customer not found by phone: {}", phone);
                                    return Mono.error(new ApiException(
                                        "Customer not found",
                                        response.statusCode().value(),
                                        body
                                    ));
                                }
                                log.error("Treez API error searching customer by phone: {} - {}", response.statusCode(), body);
                                return Mono.error(new ApiException(
                                    "Failed to search customer by phone: " + response.statusCode(),
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
                .doOnSuccess(response -> log.debug("Successfully found Treez customer by phone"))
                .doOnError(error -> {
                    if (error instanceof ApiException apiEx && apiEx.getStatusCode() == 404) {
                        log.debug("Treez customer not found by phone: {}", phone);
                    } else {
                        log.error("Error searching Treez customer by phone: {}", error.getMessage());
                    }
                });
    }

    /**
     * Find customer by email in Treez
     * GET /v2.0/dispensary/{dispensaryId}/customer/email/{email}
     * 
     * @param config Integration configuration (used to get token)
     * @param email Email address
     * @return JsonNode with customer details if found
     */
    public Mono<JsonNode> findCustomerByEmail(heymary.co.integrations.model.IntegrationConfig config, String email) {
        log.debug("Searching Treez customer by email: {}", email);
        
        // Get access token
        String accessToken = tokenService.getAccessToken(config);
        String clientId = config.getTreezClientId();
        String dispensaryId = config.getTreezDispensaryId();
        
        return webClient.get()
                .uri("/v2.0/dispensary/{dispensaryId}/customer/email/{email}", dispensaryId, email)
                .header(HttpHeaders.AUTHORIZATION, accessToken)
                .header("client_id", clientId)
                .retrieve()
                .onStatus(status -> status.isError(), response -> {
                    return response.bodyToMono(String.class)
                            .flatMap(body -> {
                                if (response.statusCode().value() == 404) {
                                    log.debug("Treez customer not found by email: {}", email);
                                    return Mono.error(new ApiException(
                                        "Customer not found",
                                        response.statusCode().value(),
                                        body
                                    ));
                                }
                                log.error("Treez API error searching customer by email: {} - {}", response.statusCode(), body);
                                return Mono.error(new ApiException(
                                    "Failed to search customer by email: " + response.statusCode(),
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
                .doOnSuccess(response -> log.debug("Successfully found Treez customer by email"))
                .doOnError(error -> {
                    if (error instanceof ApiException apiEx && apiEx.getStatusCode() == 404) {
                        log.debug("Treez customer not found by email: {}", email);
                    } else {
                        log.error("Error searching Treez customer by email: {}", error.getMessage());
                    }
                });
    }
}

