# Treez Token-Based Authentication

## Overview

Treez uses a token-based authentication system where access tokens must be fetched before making API calls. Tokens expire after a certain period (typically 2 hours) and must be refreshed.

## Authentication Flow

```
1. Fetch Token:
   POST /v2.0/dispensary/{dispensaryId}/config/api/gettokens
   Body: client_id={clientId}&apikey={apiKey}
   
2. Receive Token Response:
   {
     "resultCode": "SUCCESS",
     "access_token": "eyJhbGciOiJIUzUxMiJ9...",
     "expires_at": "2026-01-11T13:56:05.000-0800",
     "expires_in": "7200",
     "refresh_token": "eyJhbGciOiJIUzUxMiJ9..."
   }
   
3. Use Token in API Requests:
   GET /v2.0/dispensary/{dispensaryId}/customer/email/{email}
   Headers:
     Authorization: {access_token}
     client_id: {clientId}
```

## Implementation

### 1. TreezTokenService

**Location:** `src/main/java/heymary/co/integrations/service/TreezTokenService.java`

**Responsibilities:**
- Fetch access tokens from Treez API
- Cache tokens per merchant to avoid excessive requests
- Automatically refresh expired tokens
- Provide valid tokens to API clients

**Key Methods:**

#### `getAccessToken(IntegrationConfig config)`
Returns a valid access token for the merchant. Uses cached token if still valid, otherwise fetches a new one.

```java
String token = tokenService.getAccessToken(config);
```

#### `fetchAccessToken(IntegrationConfig config)`
Fetches a new access token from Treez API.

```java
Mono<String> tokenMono = tokenService.fetchAccessToken(config);
```

#### `clearToken(String merchantId)`
Clears cached token for a merchant (forces refresh on next request).

```java
tokenService.clearToken("Evergreen");
```

### 2. Token Caching

Tokens are cached in-memory per merchant with expiration tracking:

```java
private static class TokenCache {
    private final String accessToken;
    private final LocalDateTime expiresAt;
    
    public boolean isValid() {
        return LocalDateTime.now().isBefore(expiresAt);
    }
}
```

**Cache Strategy:**
- Tokens are cached with a 5-minute safety margin before expiration
- Cache is checked before each API call
- Expired tokens are automatically refreshed
- Cache is thread-safe using `ConcurrentHashMap`

### 3. Updated TreezApiClient

All TreezApiClient methods now accept `IntegrationConfig` instead of individual credentials:

**Before:**
```java
treezApiClient.findCustomerByEmail(apiKey, clientId, dispensaryId, email)
```

**After:**
```java
treezApiClient.findCustomerByEmail(config, email)
```

The client automatically fetches and uses the token internally.

## Configuration

### Database Schema

New column added to `integration_configs` table:

```sql
ALTER TABLE integration_configs 
ADD COLUMN treez_client_id VARCHAR(500);
```

### IntegrationConfig Model

```java
@Column(name = "treez_api_key", length = 500)
private String treezApiKey;  // API key for token generation

@Column(name = "treez_client_id", length = 500)
private String treezClientId;  // Client ID for API requests

@Column(name = "treez_dispensary_id", length = 100)
private String treezDispensaryId;  // Dispensary ID

// Transient fields (not stored in DB)
@Transient
private String treezAccessToken;  // Current access token

@Transient
private LocalDateTime treezTokenExpiresAt;  // Token expiration
```

### Sample Configuration

```sql
INSERT INTO integration_configs (
    merchant_id,
    treez_api_key,
    treez_client_id,
    treez_dispensary_id,
    integration_type,
    enabled
) VALUES (
    'Evergreen',
    'ZjJmMGFiMWRiMTUzNzAwOWRhO',  -- API key
    'cK8VhOFxRXakxwERhh3aMHMg',  -- Client ID
    'partnersandbox3',  -- Dispensary ID
    'TREEZ',
    true
);
```

## API Examples

### Fetch Token

```bash
curl --location 'https://api.treez.io/v2.0/dispensary/partnersandbox3/config/api/gettokens' \
  --header 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'client_id=cK8VhOFxRXakxwERhh3aMHMg' \
  --data-urlencode 'apikey=ZjJmMGFiMWRiMTUzNzAwOWRhO'
```

**Response:**
```json
{
  "resultCode": "SUCCESS",
  "access_token": "eyJhbGciOiJIUzUxMiJ9...",
  "expires_at": "2026-01-11T13:56:05.000-0800",
  "expires_in": "7200",
  "refresh_token": "eyJhbGciOiJIUzUxMiJ9..."
}
```

### Find Customer by Email (Using Token)

```bash
curl --request GET \
  --url 'https://api.treez.io/v2.0/dispensary/partnersandbox3/customer/email/test@example.com' \
  --header 'Authorization: eyJhbGciOiJIUzUxMiJ9...' \
  --header 'client_id: cK8VhOFxRXakxwERhh3aMHMg'
```

**Response:**
```json
{
  "resultCode": "SUCCESS",
  "data": [
    {
      "customer_id": "2143",
      "first_name": "JOHN",
      "last_name": "DOE",
      "email": "test@example.com",
      "phone": "2132938005",
      "rewards_balance": 822,
      ...
    }
  ]
}
```

## Token Lifecycle

### 1. Initial Token Fetch
When the first API call is made for a merchant:
1. `TreezTokenService.getAccessToken()` is called
2. Cache is empty, so `fetchAccessToken()` is invoked
3. Token is fetched from Treez API
4. Token is cached with expiration time (expires_at - 5 minutes)
5. Token is returned to caller

### 2. Subsequent API Calls
For subsequent calls within the token validity period:
1. `TreezTokenService.getAccessToken()` is called
2. Cached token is found and validated
3. Cached token is returned immediately (no API call)

### 3. Token Expiration
When a cached token expires:
1. `TreezTokenService.getAccessToken()` is called
2. Cached token is found but `isValid()` returns false
3. New token is fetched from Treez API
4. Cache is updated with new token
5. New token is returned to caller

### 4. Manual Token Refresh
To force a token refresh:
```java
tokenService.clearToken("Evergreen");
// Next API call will fetch a new token
```

## Error Handling

### Token Fetch Failures

**Missing Credentials:**
```java
if (config.getTreezApiKey() == null || config.getTreezClientId() == null) {
    throw new ApiException("Treez credentials not configured", 400, "...");
}
```

**API Errors:**
```java
if (!"SUCCESS".equals(response.get("resultCode").asText())) {
    String reason = response.get("resultReason").asText();
    throw new ApiException("Token request failed: " + reason, 400, "...");
}
```

**Network Errors:**
- Automatic retry with exponential backoff for 5xx errors
- Maximum 2 retry attempts
- 1-second initial delay

### Token Expiration Handling

Tokens are cached with a 5-minute safety margin:
```java
LocalDateTime expiresAt = LocalDateTime.now()
    .plusSeconds(expiresIn)
    .minusMinutes(5);  // Safety margin
```

This ensures tokens are refreshed before they actually expire, preventing API call failures.

## Performance Considerations

### Token Caching Benefits
1. **Reduced API Calls:** Tokens are reused for up to 2 hours
2. **Faster Response Times:** Cached tokens are returned instantly
3. **Lower Rate Limiting:** Fewer token requests mean less API quota usage
4. **Better Reliability:** Reduces dependency on token endpoint availability

### Cache Memory Usage
- One `TokenCache` object per merchant (~100 bytes)
- Typical deployment: 10-100 merchants = ~10KB memory
- Negligible memory footprint

### Thread Safety
- `ConcurrentHashMap` ensures thread-safe token access
- Multiple concurrent requests can safely share cached tokens
- No locking or synchronization overhead

## Migration Guide

### Updating Existing Code

**Before:**
```java
String apiKey = config.getTreezApiKey();
String clientId = config.getTreezClientId();
String dispensaryId = config.getTreezDispensaryId();

JsonNode customer = treezApiClient.findCustomerByEmail(
    apiKey, clientId, dispensaryId, email
).block();
```

**After:**
```java
JsonNode customer = treezApiClient.findCustomerByEmail(config, email).block();
```

### Database Migration

Run the migration script:
```sql
-- V2__add_treez_client_id.sql
ALTER TABLE integration_configs 
ADD COLUMN IF NOT EXISTS treez_client_id VARCHAR(500);
```

Update existing records:
```sql
UPDATE integration_configs 
SET treez_client_id = 'your_client_id_here'
WHERE integration_type = 'TREEZ';
```

## Testing

### Unit Tests

```java
@Test
public void testTokenCaching() {
    IntegrationConfig config = createTestConfig();
    
    // First call - fetches token
    String token1 = tokenService.getAccessToken(config);
    assertNotNull(token1);
    
    // Second call - returns cached token
    String token2 = tokenService.getAccessToken(config);
    assertEquals(token1, token2);
}

@Test
public void testTokenExpiration() {
    IntegrationConfig config = createTestConfig();
    
    // Fetch token
    String token1 = tokenService.getAccessToken(config);
    
    // Clear cache to simulate expiration
    tokenService.clearToken(config.getMerchantId());
    
    // Should fetch new token
    String token2 = tokenService.getAccessToken(config);
    assertNotEquals(token1, token2);
}
```

### Integration Tests

```java
@Test
public void testCustomerLookupWithToken() {
    IntegrationConfig config = createTestConfig();
    
    // Should automatically fetch token and use it
    JsonNode customer = treezApiClient
        .findCustomerByEmail(config, "test@example.com")
        .block();
    
    assertNotNull(customer);
    assertEquals("SUCCESS", customer.get("resultCode").asText());
}
```

## Troubleshooting

### Common Issues

**1. "Treez credentials not configured"**
- Ensure `treez_api_key`, `treez_client_id`, and `treez_dispensary_id` are set
- Check database values are not NULL

**2. "Token request failed"**
- Verify API key and client ID are correct
- Check dispensary ID matches your Treez account
- Ensure Treez API is accessible from your server

**3. "Customer not found" (404)**
- This is expected when customer doesn't exist
- Not an error - handle gracefully in your code

**4. Token expires too quickly**
- Check system clock is synchronized
- Verify `expires_at` parsing is correct
- Safety margin is set to 5 minutes

### Debugging

Enable debug logging:
```yaml
logging:
  level:
    heymary.co.integrations.service.TreezTokenService: DEBUG
    heymary.co.integrations.service.TreezApiClient: DEBUG
```

Check logs for:
- Token fetch attempts
- Cache hits/misses
- Token expiration times
- API call patterns

## Related Documentation

- [Treez API Documentation](https://api.treez.io/docs)
- [Customer Sync Implementation](./CUSTOMER_SYNC_IMPLEMENTATION.md)
- [Initial Sync Implementation](./INITIAL_SYNC_IMPLEMENTATION.md)
