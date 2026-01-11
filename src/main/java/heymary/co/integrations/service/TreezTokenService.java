package heymary.co.integrations.service;

import com.fasterxml.jackson.databind.JsonNode;
import heymary.co.integrations.exception.ApiException;
import heymary.co.integrations.model.IntegrationConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing Treez API authentication tokens.
 * Treez uses a token-based authentication system where tokens must be fetched
 * before making API calls and refreshed when they expire.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TreezTokenService {

    private final WebClient treezWebClient;
    
    // Cache tokens per merchant to avoid excessive token requests
    private final Map<String, TokenCache> tokenCache = new ConcurrentHashMap<>();
    
    /**
     * Get a valid access token for the merchant.
     * Returns cached token if still valid, otherwise fetches a new one.
     * 
     * @param config Integration configuration
     * @return Valid access token
     */
    public synchronized String getAccessToken(IntegrationConfig config) {
        String merchantId = config.getMerchantId();
        
        // Check cache first
        TokenCache cached = tokenCache.get(merchantId);
        if (cached != null && cached.isValid()) {
            log.debug("Using cached token for merchant: {}", merchantId);
            return cached.accessToken;
        }
        
        // Fetch new token (synchronized to prevent multiple concurrent fetches)
        log.info("Fetching new Treez access token for merchant: {}", merchantId);
        String token = fetchAccessToken(config).block();
        
        return token;
    }
    
    /**
     * Fetch a new access token from Treez API.
     * POST /v2.0/dispensary/{dispensaryId}/config/api/gettokens
     * 
     * @param config Integration configuration
     * @return Mono with access token
     */
    public Mono<String> fetchAccessToken(IntegrationConfig config) {
        String merchantId = config.getMerchantId();
        String dispensaryId = config.getTreezDispensaryId();
        String clientId = config.getTreezClientId();
        String apiKey = config.getTreezApiKey();
        
        if (dispensaryId == null || clientId == null || apiKey == null) {
            return Mono.error(new ApiException(
                "Treez credentials not configured for merchant: " + merchantId,
                400,
                "Missing dispensaryId, clientId, or apiKey"
            ));
        }
        
        log.debug("Fetching Treez token for dispensary: {}", dispensaryId);
        
        // Prepare form data
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("client_id", clientId);
        formData.add("apikey", apiKey);
        
        return treezWebClient.post()
                .uri("/v2.0/dispensary/{dispensaryId}/config/api/gettokens", dispensaryId)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(formData))
                .retrieve()
                .onStatus(status -> status.isError(), response -> {
                    return response.bodyToMono(String.class)
                            .flatMap(body -> {
                                log.error("Treez token API error: {} - {}", response.statusCode(), body);
                                return Mono.error(new ApiException(
                                    "Failed to fetch Treez token: " + response.statusCode(),
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
                            log.warn("Retrying Treez token fetch, attempt: {}", retrySignal.totalRetries() + 1))
                )
                .map(response -> {
                    // Extract token from response
                    if (!response.has("resultCode") || !"SUCCESS".equals(response.get("resultCode").asText())) {
                        String reason = response.has("resultReason") ? response.get("resultReason").asText() : "Unknown error";
                        throw new ApiException("Treez token request failed: " + reason, 400, response.toString());
                    }
                    
                    String accessToken = response.get("access_token").asText();
                    String expiresAtStr = response.has("expires_at") ? response.get("expires_at").asText() : null;
                    int expiresIn = response.has("expires_in") ? response.get("expires_in").asInt() : 7200;
                    
                    // Calculate expiration time (subtract 5 minutes for safety margin)
                    LocalDateTime expiresAt;
                    if (expiresAtStr != null) {
                        try {
                            // Parse format: "2026-01-11T13:56:05.000-0800"
                            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
                            expiresAt = LocalDateTime.parse(expiresAtStr, formatter);
                        } catch (Exception e) {
                            log.warn("Failed to parse expires_at, using expires_in instead: {}", e.getMessage());
                            expiresAt = LocalDateTime.now().plusSeconds(expiresIn).minusMinutes(5);
                        }
                    } else {
                        expiresAt = LocalDateTime.now().plusSeconds(expiresIn).minusMinutes(5);
                    }
                    
                    // Cache the token
                    tokenCache.put(merchantId, new TokenCache(accessToken, expiresAt));
                    
                    log.info("Successfully fetched Treez token for merchant: {} (expires at: {})", 
                            merchantId, expiresAt);
                    
                    return accessToken;
                })
                .doOnError(error -> log.error("Error fetching Treez token for merchant {}: {}", 
                        merchantId, error.getMessage()));
    }
    
    /**
     * Clear cached token for a merchant (useful for forcing token refresh)
     */
    public void clearToken(String merchantId) {
        tokenCache.remove(merchantId);
        log.info("Cleared cached token for merchant: {}", merchantId);
    }
    
    /**
     * Clear all cached tokens
     */
    public void clearAllTokens() {
        tokenCache.clear();
        log.info("Cleared all cached tokens");
    }
    
    /**
     * Token cache entry
     */
    private static class TokenCache {
        private final String accessToken;
        private final LocalDateTime expiresAt;
        
        public TokenCache(String accessToken, LocalDateTime expiresAt) {
            this.accessToken = accessToken;
            this.expiresAt = expiresAt;
        }
        
        public boolean isValid() {
            return LocalDateTime.now().isBefore(expiresAt);
        }
    }
}
