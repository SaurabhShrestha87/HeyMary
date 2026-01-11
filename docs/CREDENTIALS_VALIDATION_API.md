# Credentials Validation API

## Overview

The Credentials Validation API provides secure merchant authentication using BCrypt-hashed access tokens. This allows merchants to validate their credentials before making API requests to the integration service.

## Security Features

- **BCrypt Hashing**: Access tokens are stored as BCrypt hashes in the database
- **One-way Encryption**: Original tokens cannot be retrieved from the database
- **Constant-time Comparison**: BCrypt's matching algorithm prevents timing attacks
- **Enabled Check**: Only active (enabled) merchants can authenticate

## Endpoints

### 1. Check Credentials (Array Format)

**Endpoint**: `POST /api/check-credentials`

**Description**: Validates merchant credentials using an array of name-value pairs.

**Request Body**:
```json
[
  {
    "name": "merchantId",
    "value": "Evergreen"
  },
  {
    "name": "accessToken",
    "value": "your-plain-text-token-here"
  }
]
```

**Response**:
```json
{
  "isValid": true
}
```

**Status Codes**:
- `200 OK`: Request processed successfully (check `isValid` field for validation result)

---

### 2. Check Credentials (Simple Format)

**Endpoint**: `POST /api/check-credentials-simple`

**Description**: Alternative endpoint with simpler JSON object format.

**Request Body**:
```json
{
  "merchantId": "Evergreen",
  "accessToken": "your-plain-text-token-here"
}
```

**Response**:
```json
{
  "isValid": true
}
```

**Status Codes**:
- `200 OK`: Request processed successfully (check `isValid` field for validation result)

---

### 3. Set Access Token

**Endpoint**: `POST /api/integration-configs/{merchantId}/access-token`

**Description**: Sets or updates the access token for a merchant. The token is automatically hashed using BCrypt.

**Path Parameters**:
- `merchantId`: The merchant identifier

**Request Body**:
```json
{
  "accessToken": "your-new-secure-token-here"
}
```

**Response** (Success):
```json
{
  "success": true,
  "message": "Access token updated successfully"
}
```

**Status Codes**:
- `200 OK`: Token updated successfully
- `400 Bad Request`: Invalid or empty token
- `404 Not Found`: Merchant not found

---

## Usage Examples

### Using cURL

#### Check Credentials (Array Format)
```bash
curl -X POST http://localhost:8080/api/check-credentials \
  -H "Content-Type: application/json" \
  -d '[
    {"name": "merchantId", "value": "Evergreen"},
    {"name": "accessToken", "value": "my-secret-token-123"}
  ]'
```

#### Check Credentials (Simple Format)
```bash
curl -X POST http://localhost:8080/api/check-credentials-simple \
  -H "Content-Type: application/json" \
  -d '{
    "merchantId": "Evergreen",
    "accessToken": "my-secret-token-123"
  }'
```

#### Set Access Token
```bash
curl -X POST http://localhost:8080/api/integration-configs/Evergreen/access-token \
  -H "Content-Type: application/json" \
  -d '{
    "accessToken": "my-new-secret-token-456"
  }'
```

### Using JavaScript/TypeScript

```typescript
async function checkCredentials(merchantId: string, accessToken: string): Promise<boolean> {
  const response = await fetch('http://localhost:8080/api/check-credentials-simple', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      merchantId,
      accessToken,
    }),
  });

  const result = await response.json();
  return result.isValid;
}

// Usage
const isValid = await checkCredentials('Evergreen', 'my-secret-token-123');
console.log(`Credentials valid: ${isValid}`);
```

### Using Python

```python
import requests

def check_credentials(merchant_id: str, access_token: str) -> bool:
    url = 'http://localhost:8080/api/check-credentials-simple'
    payload = {
        'merchantId': merchant_id,
        'accessToken': access_token
    }
    
    response = requests.post(url, json=payload)
    result = response.json()
    return result['isValid']

# Usage
is_valid = check_credentials('Evergreen', 'my-secret-token-123')
print(f'Credentials valid: {is_valid}')
```

---

## Setting Up Access Tokens

### Method 1: Using the API

Use the `/api/integration-configs/{merchantId}/access-token` endpoint to set tokens programmatically:

```bash
curl -X POST http://localhost:8080/api/integration-configs/Evergreen/access-token \
  -H "Content-Type: application/json" \
  -d '{"accessToken": "super-secure-token-12345"}'
```

### Method 2: Using SQL Script

Use the provided SQL script (`scripts/generate-access-token.sql`) to set tokens directly in the database:

```sql
-- Enable pgcrypto extension (one-time setup)
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Set access token for a merchant
UPDATE integration_configs
SET api_access_token = crypt('your-secure-token-here', gen_salt('bf'))
WHERE merchant_id = 'Evergreen';
```

### Method 3: Generate Random Tokens

Generate cryptographically secure random tokens:

**Using OpenSSL (Linux/Mac)**:
```bash
openssl rand -hex 32
```

**Using Python**:
```python
import secrets
token = secrets.token_hex(32)
print(token)
```

**Using Node.js**:
```javascript
const crypto = require('crypto');
const token = crypto.randomBytes(32).toString('hex');
console.log(token);
```

---

## Best Practices

### Token Generation
1. **Length**: Use at least 32 characters for access tokens
2. **Randomness**: Use cryptographically secure random generation
3. **Uniqueness**: Each merchant should have a unique token
4. **Complexity**: Include mix of letters, numbers, and special characters

### Token Storage
1. **Never commit tokens to version control**
2. **Use a secrets management system** (e.g., HashiCorp Vault, AWS Secrets Manager)
3. **Store tokens encrypted at rest**
4. **Limit access to token storage**

### Token Rotation
1. **Regular rotation**: Rotate tokens periodically (e.g., every 90 days)
2. **Incident rotation**: Rotate immediately if compromise is suspected
3. **Track rotation history**: Keep audit log of token changes

### API Integration
1. **Use HTTPS**: Always use HTTPS in production
2. **Rate limiting**: Implement rate limiting on credential checks
3. **Logging**: Log failed authentication attempts
4. **Monitoring**: Set up alerts for unusual authentication patterns

---

## Validation Logic

The credential validation process:

1. **Extract credentials** from request (merchantId and accessToken)
2. **Validate presence** of both fields
3. **Query database** for merchant configuration
4. **Check merchant exists** in the system
5. **Check merchant is enabled** (enabled = true)
6. **Check token is configured** (not null/empty)
7. **Compare tokens** using BCrypt's secure matching
8. **Return result** (isValid: true/false)

### Validation Returns `false` When:
- merchantId or accessToken is missing/empty
- Merchant doesn't exist in database
- Merchant is disabled (enabled = false)
- No access token configured for merchant
- Access token doesn't match stored hash
- Any exception occurs during validation

---

## Database Schema

The `integration_configs` table includes:

```sql
CREATE TABLE integration_configs (
    id BIGSERIAL PRIMARY KEY,
    merchant_id VARCHAR(255) NOT NULL UNIQUE,
    api_access_token VARCHAR(500),  -- BCrypt hash of the access token
    enabled BOOLEAN DEFAULT true,
    -- ... other fields ...
);

CREATE INDEX idx_integration_configs_api_access_token 
ON integration_configs(api_access_token);
```

---

## Troubleshooting

### Problem: Credentials always return `isValid: false`

**Solutions**:
1. Verify merchant exists in database
2. Check merchant is enabled: `SELECT enabled FROM integration_configs WHERE merchant_id = 'YourMerchantId'`
3. Verify access token is set: `SELECT api_access_token IS NOT NULL FROM integration_configs WHERE merchant_id = 'YourMerchantId'`
4. Ensure you're sending the plain-text token (not the hash) in the validation request
5. Check application logs for specific error messages

### Problem: Cannot set access token

**Solutions**:
1. Verify merchant exists before setting token
2. Ensure token is not empty
3. Check database connection is working
4. Review application logs for errors

### Problem: Token was set but validation fails

**Solutions**:
1. Ensure you're using the same plain-text token that was set
2. Verify token wasn't accidentally modified (trailing spaces, encoding issues)
3. Check if merchant was disabled after token was set
4. Test with a new token to rule out hash corruption

---

## Security Considerations

### BCrypt Algorithm
- **Work factor**: Uses default BCrypt work factor (strength 10)
- **Salt**: Automatically generates unique salt per token
- **One-way**: Cannot reverse the hash to get original token
- **Slow by design**: Intentionally slow to prevent brute-force attacks

### Attack Prevention
- **Timing attacks**: BCrypt uses constant-time comparison
- **Rainbow tables**: Unique salts prevent precomputed hash attacks
- **Brute force**: Slow hashing makes brute force impractical
- **SQL injection**: Uses parameterized queries (JPA)

### Production Recommendations
1. Enable HTTPS/TLS for all API endpoints
2. Implement rate limiting (e.g., 5 requests per minute per IP)
3. Add IP whitelisting for known merchant IPs
4. Set up monitoring and alerting for failed attempts
5. Implement token expiration and rotation policies
6. Use API gateway with additional authentication layers
7. Enable audit logging for all credential checks

---

## Migration Guide

If you're adding this to an existing system with merchants:

1. **Run the migration**: `V3__add_api_access_token.sql` will add the column
2. **Generate tokens** for all existing merchants
3. **Update each merchant** using the API or SQL script
4. **Test validation** with each merchant's credentials
5. **Update client applications** to use the new endpoint
6. **Monitor logs** for any validation issues

---

## Support

For issues or questions:
1. Check application logs: `docker-compose logs app`
2. Review this documentation
3. Test with the provided example scripts
4. Contact the development team

---

## Version History

- **v1.0** (2025-12-13): Initial release with BCrypt-based credential validation

