# Credentials Validation - Quick Start Guide

This guide will help you quickly set up and test the credentials validation endpoint.

## Prerequisites

1. Application is running: `docker-compose up -d`
2. Database has at least one merchant in `integration_configs` table
3. You have curl or PowerShell (for testing)

## Step 1: Generate a Secure Access Token

Choose one of the following methods:

### Option A: Generate Random Token (Recommended)

**Using PowerShell (Windows)**:
```powershell
$bytes = New-Object byte[] 32
[Security.Cryptography.RNGCryptoServiceProvider]::Create().GetBytes($bytes)
$token = [System.BitConverter]::ToString($bytes) -replace '-',''
Write-Host "Generated Token: $token"
```

**Using OpenSSL (Linux/Mac)**:
```bash
openssl rand -hex 32
```

**Using Python**:
```python
import secrets
print(secrets.token_hex(32))
```

### Option B: Use a Custom Token

Create your own token (minimum 16 characters recommended):
```
my-super-secure-token-2025
```

**For this example, we'll use**: `test-token-123`

---

## Step 2: Set the Access Token for Your Merchant

Replace `Evergreen` with your actual merchant ID.

### Using cURL (Linux/Mac):
```bash
curl -X POST http://localhost:8080/api/integration-configs/Evergreen/access-token \
  -H "Content-Type: application/json" \
  -d '{"accessToken": "test-token-123"}'
```

### Using PowerShell (Windows):
```powershell
$body = @{
    accessToken = "test-token-123"
} | ConvertTo-Json

Invoke-RestMethod -Uri "http://localhost:8080/api/integration-configs/Evergreen/access-token" `
    -Method Post `
    -ContentType "application/json" `
    -Body $body
```

**Expected Response**:
```json
{
  "success": true,
  "message": "Access token updated successfully"
}
```

---

## Step 3: Test the Credentials Validation

### Test 1: Valid Credentials

**Using cURL**:
```bash
curl -X POST http://localhost:8080/api/check-credentials-simple \
  -H "Content-Type: application/json" \
  -d '{
    "merchantId": "Evergreen",
    "accessToken": "test-token-123"
  }'
```

**Using PowerShell**:
```powershell
$body = @{
    merchantId = "Evergreen"
    accessToken = "test-token-123"
} | ConvertTo-Json

Invoke-RestMethod -Uri "http://localhost:8080/api/check-credentials-simple" `
    -Method Post `
    -ContentType "application/json" `
    -Body $body
```

**Expected Response**:
```json
{
  "isValid": true
}
```

### Test 2: Invalid Credentials

**Using cURL**:
```bash
curl -X POST http://localhost:8080/api/check-credentials-simple \
  -H "Content-Type: application/json" \
  -d '{
    "merchantId": "Evergreen",
    "accessToken": "wrong-token"
  }'
```

**Expected Response**:
```json
{
  "isValid": false
}
```

---

## Step 4: Run Automated Tests

We've included test scripts for comprehensive testing.

### Linux/Mac:
```bash
chmod +x scripts/test-credentials-api.sh
export MERCHANT_ID="Evergreen"
export ACCESS_TOKEN="test-token-123"
./scripts/test-credentials-api.sh
```

### Windows (PowerShell):
```powershell
$env:MERCHANT_ID = "Evergreen"
$env:ACCESS_TOKEN = "test-token-123"
.\scripts\test-credentials-api.ps1
```

---

## Step 5: Verify in Database (Optional)

You can verify the token was set correctly in the database:

```sql
-- Connect to your database
-- docker exec -it heymary-postgres psql -U postgres -d integrations_db

-- Check if token is set
SELECT merchant_id, 
       api_access_token IS NOT NULL as has_token,
       enabled
FROM integration_configs
WHERE merchant_id = 'Evergreen';
```

**Expected Output**:
```
merchant_id | has_token | enabled
------------|-----------|--------
Evergreen   | t         | t
```

---

## Common Issues and Solutions

### Issue 1: "Merchant not found" (404)

**Problem**: The merchant doesn't exist in the database.

**Solution**: Create the merchant first:
```bash
curl -X POST http://localhost:8080/api/integration-configs \
  -H "Content-Type: application/json" \
  -d '{
    "merchantId": "Evergreen",
    "boomerangmeApiKey": "your-key",
    "dutchieApiKey": "your-key",
    "enabled": true
  }'
```

### Issue 2: "isValid: false" with correct token

**Problem**: The merchant might be disabled or token wasn't set correctly.

**Solution**: Check merchant status and reset token:
```bash
# Get merchant details
curl http://localhost:8080/api/integration-configs/Evergreen

# Set token again
curl -X POST http://localhost:8080/api/integration-configs/Evergreen/access-token \
  -H "Content-Type: application/json" \
  -d '{"accessToken": "test-token-123"}'
```

### Issue 3: Connection refused

**Problem**: Application is not running.

**Solution**: Start the application:
```bash
docker-compose up -d
# Wait a few seconds for startup
docker-compose logs -f app
```

---

## Integration Example

Here's a complete example in JavaScript for integrating with your application:

```javascript
class MerchantAuthClient {
  constructor(apiBaseUrl) {
    this.apiBaseUrl = apiBaseUrl;
  }

  async validateCredentials(merchantId, accessToken) {
    try {
      const response = await fetch(`${this.apiBaseUrl}/api/check-credentials-simple`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          merchantId,
          accessToken,
        }),
      });

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const result = await response.json();
      return result.isValid;
    } catch (error) {
      console.error('Credential validation failed:', error);
      return false;
    }
  }

  async setAccessToken(merchantId, accessToken) {
    try {
      const response = await fetch(
        `${this.apiBaseUrl}/api/integration-configs/${merchantId}/access-token`,
        {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({
            accessToken,
          }),
        }
      );

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const result = await response.json();
      return result.success;
    } catch (error) {
      console.error('Failed to set access token:', error);
      return false;
    }
  }
}

// Usage
const client = new MerchantAuthClient('http://localhost:8080');

// Set token (one-time setup)
await client.setAccessToken('Evergreen', 'test-token-123');

// Validate credentials
const isValid = await client.validateCredentials('Evergreen', 'test-token-123');
console.log(`Credentials valid: ${isValid}`); // true
```

---

## Next Steps

1. **Production Setup**: Use strong, randomly-generated tokens
2. **Security**: Enable HTTPS and add rate limiting
3. **Monitoring**: Set up alerts for failed authentication attempts
4. **Documentation**: Share the API documentation with your team
5. **Token Rotation**: Implement a token rotation policy

For more details, see:
- [Complete API Documentation](CREDENTIALS_VALIDATION_API.md)
- [Main README](../README.md)

---

## Summary

You've successfully:
- ✅ Generated a secure access token
- ✅ Set the token for your merchant
- ✅ Validated credentials using the API
- ✅ Understood the integration process

The `/api/check-credentials` endpoint is now ready to use in your application!

