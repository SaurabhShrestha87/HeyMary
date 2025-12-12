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
import java.util.Base64;
import java.util.Map;

@Slf4j
@Service
public class DutchieApiClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public DutchieApiClient(@Qualifier("dutchieWebClient") WebClient webClient, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Get customer by ID from Dutchie POS
     * Uses /customer/customers endpoint with customerID query parameter
     * @param authHeader Pre-computed Dutchie Authorization header (Basic Auth)
     */
    public Mono<JsonNode> getCustomer(String authHeader, String customerId) {
        log.debug("Getting customer from Dutchie: {}", customerId);
        
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/customer/customers")
                        .queryParam("customerID", customerId)
                        .build())
                .header(HttpHeaders.AUTHORIZATION, authHeader)
                .retrieve()
                .onStatus(status -> status.isError(), response -> {
                    return response.bodyToMono(String.class)
                            .flatMap(body -> {
                                log.error("Dutchie API error getting customer: {} - {}", response.statusCode(), body);
                                return Mono.error(new ApiException(
                                    "Failed to get customer: " + response.statusCode(),
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
                .doOnSuccess(response -> {
                    // Response is an array, extract first customer if exists
                    log.debug("Successfully retrieved customer {}", customerId);
                })
                .doOnError(error -> log.error("Error getting customer {}: {}", customerId, error.getMessage()));
    }

    /**
     * Get transaction by ID from Dutchie POS
     * Uses /reporting/transactions endpoint with TransactionId query parameter
     * @param authHeader Pre-computed Dutchie Authorization header (Basic Auth)
     */
    public Mono<JsonNode> getTransaction(String authHeader, String transactionId) {
        log.debug("Getting transaction from Dutchie: {}", transactionId);
        
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/reporting/transactions")
                        .queryParam("TransactionId", transactionId)
                        .queryParam("IncludeDetail", true)
                        .queryParam("IncludeTaxes", true)
                        .queryParam("IncludeOrderIds", true)
                        .build())
                .header(HttpHeaders.AUTHORIZATION, authHeader)
                .retrieve()
                .onStatus(status -> status.isError(), response -> {
                    return response.bodyToMono(String.class)
                            .flatMap(body -> {
                                log.error("Dutchie API error getting transaction: {} - {}", response.statusCode(), body);
                                return Mono.error(new ApiException(
                                    "Failed to get transaction: " + response.statusCode(),
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
                .doOnSuccess(response -> log.debug("Successfully retrieved transaction {}", transactionId))
                .doOnError(error -> log.error("Error getting transaction {}: {}", transactionId, error.getMessage()));
    }

    /**
     * List pre-orders from Dutchie POS
     * Uses /preorder/Status endpoint which requires "PreOrder" role (alternative to Reporting role)
     * Returns orders from last 14 days with status: Submitted, Processing, Filled, Complete, Cancelled
     * @param authHeader Pre-computed Dutchie Authorization header (Basic Auth)
     * @return Mono containing JsonNode array of PreOrderStatus objects
     */
    public Mono<JsonNode> listPreOrders(String authHeader) {
        log.debug("Listing pre-orders from Dutchie");
        
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/preorder/Status")
                        .queryParam("includeOrderedItems", true)
                        .build())
                .header(HttpHeaders.AUTHORIZATION, authHeader)
                .retrieve()
                .onStatus(status -> status.isError(), response -> {
                    return response.bodyToMono(String.class)
                            .flatMap(body -> {
                                String errorMessage;
                                if (response.statusCode() == HttpStatus.FORBIDDEN) {
                                    errorMessage = "Access forbidden (403): The API key does not have 'PreOrder' role authorization. " +
                                            "Please contact Dutchie support to enable the 'PreOrder' role for your API key. " +
                                            "Response: " + body;
                                } else {
                                    errorMessage = "Failed to list pre-orders: " + response.statusCode() + " - " + body;
                                }
                                log.error("Dutchie API error listing pre-orders: {} - {}", response.statusCode(), body);
                                return Mono.error(new ApiException(
                                    errorMessage,
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
                .doOnSuccess(response -> log.debug("Successfully retrieved pre-orders list"))
                .doOnError(error -> log.error("Error listing pre-orders: {}", error.getMessage()));
    }

    /**
     * List transactions (orders) from Dutchie POS with date filtering
     * Uses /reporting/transactions endpoint which returns completed transactions/orders
     * NOTE: Requires "Reporting" role - use listPreOrders() if you only have "PreOrder" role
     * @param authHeader Pre-computed Dutchie Authorization header (Basic Auth)
     * @param fromLastModifiedDateUTC Start date for filtering by last modified date (ISO 8601 UTC format)
     * @param toLastModifiedDateUTC End date for filtering by last modified date (ISO 8601 UTC format)
     * @return Mono containing JsonNode array of Transaction objects
     */
    @Deprecated
    public Mono<JsonNode> listTransactions(String authHeader, String fromLastModifiedDateUTC, String toLastModifiedDateUTC) {
        log.debug("Listing transactions from Dutchie - fromLastModifiedDateUTC: {}, toLastModifiedDateUTC: {}", 
                fromLastModifiedDateUTC, toLastModifiedDateUTC);
        
        return webClient.get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder.path("/reporting/transactions")
                            .queryParam("IncludeDetail", true)
                            .queryParam("IncludeTaxes", true)
                            .queryParam("IncludeOrderIds", true);
                    
                    if (fromLastModifiedDateUTC != null && !fromLastModifiedDateUTC.isEmpty()) {
                        builder.queryParam("FromLastModifiedDateUTC", fromLastModifiedDateUTC);
                    }
                    if (toLastModifiedDateUTC != null && !toLastModifiedDateUTC.isEmpty()) {
                        builder.queryParam("ToLastModifiedDateUTC", toLastModifiedDateUTC);
                    }
                    
                    return builder.build();
                })
                .header(HttpHeaders.AUTHORIZATION, authHeader)
                .retrieve()
                .onStatus(status -> status.isError(), response -> {
                    return response.bodyToMono(String.class)
                            .flatMap(body -> {
                                String errorMessage;
                                if (response.statusCode() == HttpStatus.FORBIDDEN) {
                                    errorMessage = "Access forbidden (403): The API key does not have 'Reporting' role authorization. " +
                                            "Please contact Dutchie support to enable the 'Reporting' role for your API key. " +
                                            "Response: " + body;
                                } else {
                                    errorMessage = "Failed to list transactions: " + response.statusCode() + " - " + body;
                                }
                                log.error("Dutchie API error listing transactions: {} - {}", response.statusCode(), body);
                                return Mono.error(new ApiException(
                                    errorMessage,
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
                .doOnSuccess(response -> log.debug("Successfully retrieved transactions list"))
                .doOnError(error -> log.error("Error listing transactions: {}", error.getMessage()));
    }

    /**
     * Create or update customer in Dutchie POS
     * Uses /customer/customer POST endpoint which handles both create and update
     * @param authHeader Pre-computed Dutchie Authorization header (Basic Auth)
     * @param customerData EcomCustomerEdit object - set CustomerId to null/0 for create, provide CustomerId for update
     */
    public Mono<JsonNode> createOrUpdateCustomer(String authHeader, Map<String, Object> customerData) {
        boolean isUpdate = customerData.containsKey("CustomerId") && 
                          customerData.get("CustomerId") != null && 
                          !customerData.get("CustomerId").equals(0);
        log.debug("{} customer in Dutchie: {}", isUpdate ? "Updating" : "Creating", customerData.get("EmailAddress"));
        
        return webClient.post()
                .uri("/customer/customer")
                .header(HttpHeaders.AUTHORIZATION, authHeader)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(customerData)
                .retrieve()
                .onStatus(status -> status.isError(), response -> {
                    return response.bodyToMono(String.class)
                            .flatMap(body -> {
                                log.error("Dutchie API error {} customer: {} - {}", 
                                        isUpdate ? "updating" : "creating", response.statusCode(), body);
                                return Mono.error(new ApiException(
                                    "Failed to " + (isUpdate ? "update" : "create") + " customer: " + response.statusCode(),
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
                .doOnSuccess(response -> log.debug("Successfully {} customer in Dutchie", isUpdate ? "updated" : "created"))
                .doOnError(error -> log.error("Error {} customer: {}", isUpdate ? "updating" : "creating", error.getMessage()));
    }

    /**
     * Update customer in Dutchie POS
     * Uses /customer/customer POST endpoint with CustomerId set in the request body
     * @param authHeader Pre-computed Dutchie Authorization header (Basic Auth)
     * @param customerId Customer ID to update
     * @param customerData EcomCustomerEdit object with CustomerId set
     */
    public Mono<JsonNode> updateCustomer(String authHeader, String customerId, Map<String, Object> customerData) {
        log.debug("Updating customer in Dutchie: {}", customerId);
        
        // Ensure CustomerId is set in the data for update
        customerData.put("CustomerId", Integer.parseInt(customerId));
        
        return createOrUpdateCustomer(authHeader, customerData);
    }

    /**
     * Create Basic Auth header for Dutchie API
     * Dutchie uses HTTP Basic Auth with API key as username
     * Note: This method is kept for backward compatibility, but auth headers should be pre-computed
     * and stored in IntegrationConfig.dutchieAuthHeader
     */
    @Deprecated
    private String createBasicAuthHeader(String apiKey) {
        String credentials = apiKey + ":";
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());
        return "Basic " + encoded;
    }
}

