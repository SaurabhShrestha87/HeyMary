# Boomerangme API Response Formats

This document describes the different response formats used by Boomerangme API endpoints and how our system handles them.

## Overview

Boomerangme uses different response formats for different API endpoints:
- **List API** (`GET /api/v2/cards`) - Returns cards in a paginated list format
- **Webhook Events** - Sends card data in webhook payloads with different field names

Our `InitialSyncService` is designed to handle both formats seamlessly.

## List API Format (`GET /api/v2/cards`)

### Response Structure

```json
{
  "responseId": "019bae92-8f14-7340-88bc-9e2f9d442689",
  "createdAt": "2026-01-11T19:39:59+00:00",
  "code": 200,
  "meta": {
    "totalItems": 4,
    "itemsPerPage": 30,
    "currentPage": 1
  },
  "data": [
    {
      "id": "153111-927-114",              // Card serial number
      "companyId": 154435,
      "templateId": 955805,
      "customerId": "019b1df3-a306-739e-988b-50dbfedc2922",  // Cardholder ID
      "type": "reward",                    // Card type
      "device": "Google Pay",              // Device type
      "status": "installed",               // Card status
      "customer": {                        // Nested customer object
        "id": "019b1df3-a306-739e-988b-50dbfedc2922",
        "phone": "12138167531",
        "email": "saurabhshresthadev@yopmail.com",
        "gender": 1,
        "dateOfBirth": "1990-01-01",
        "surname": "ShresthaDev",
        "firstName": "SaurabhDev",
        "externalUserId": null,
        "createdAt": "2025-12-14T17:41:04+00:00",
        "updatedAt": "2025-12-14T17:41:12+00:00"
      },
      "balance": {                         // Nested balance object
        "bonusBalance": 0,
        "numberStampsTotal": null,
        "numberRewardsUnused": null,
        "balance": null
      },
      "directInstallLink": {               // Nested install links
        "universal": "https://app.heymary.co/installcard/153111-927-114/universal",
        "apple": "https://app.heymary.co/installcard/153111-927-114/apple",
        "google": "https://app.heymary.co/installcard/153111-927-114/google",
        "pwa": "https://app.heymary.co/installcard/153111-927-114/pwa"
      },
      "installLink": "https://app.heymary.co/getpass/955805/20779267",
      "shareLink": "https://app.heymary.co/getpass/955805/20779267/share/",
      "utmSource": "qr",
      "countVisits": null,
      "createdAt": "2025-12-14T17:41:04+00:00",
      "updatedAt": "2026-01-01T12:01:00+00:00",
      "customFields": [                    // Array of custom fields
        {
          "id": 4225123,
          "name": "First name",
          "type": "FName",
          "value": "SaurabhDev"
        }
      ]
    }
  ]
}
```

### Key Field Mappings (List API → Database)

| List API Field | Database Field | Notes |
|----------------|----------------|-------|
| `id` | `serial_number` | Card serial number (e.g., "153111-927-114") |
| `customerId` | `cardholder_id` | Cardholder/customer UUID |
| `type` | `card_type` | Card type (reward, stamp, etc.) |
| `device` | `device_type` | Device type (Google Pay, Apple Wallet, etc.) |
| `templateId` | `template_id` | Template ID (integer) |
| `customer.email` | `cardholder_email` | Email from nested customer object |
| `customer.phone` | `cardholder_phone` | Phone from nested customer object |
| `customer.firstName` | `cardholder_first_name` | First name from nested customer object |
| `customer.surname` | `cardholder_last_name` | Last name from nested customer object |
| `customer.dateOfBirth` | `cardholder_birth_date` | Birth date from nested customer object |
| `balance.bonusBalance` | `bonus_balance` | Points balance from nested balance object |
| `balance.numberStampsTotal` | `number_stamps_total` | Stamps from nested balance object |
| `balance.numberRewardsUnused` | `number_rewards_unused` | Rewards from nested balance object |
| `directInstallLink.universal` | `install_link_universal` | Universal install link |
| `directInstallLink.apple` | `install_link_apple` | Apple Wallet link |
| `directInstallLink.google` | `install_link_google` | Google Pay link |
| `directInstallLink.pwa` | `install_link_pwa` | PWA install link |
| `installLink` | `short_link` | Short install link |
| `shareLink` | `share_link` | Share link |
| `utmSource` | `utm_source` | UTM source tracking |
| `customFields` | `custom_fields` | Array of custom fields (stored as JSON string) |

## Webhook Event Format

### Response Structure

```json
{
  "event": "CardInstalledEvent",
  "data": {
    "serial_number": "153111-927-114",        // Card serial number
    "cardholder_id": "019b1df3-a306-739e-988b-50dbfedc2922",  // Cardholder ID
    "card_type": "reward",                    // Card type
    "device_type": "Google Pay",              // Device type
    "template_id": "955805",                  // Template ID (string)
    "status": "installed",
    "cardholder_email": "test@example.com",   // Direct fields (no nesting)
    "cardholder_phone": "12138167531",
    "cardholder_first_name": "John",
    "cardholder_last_name": "Doe",
    "cardholder_birth_date": "1990-01-01",
    "bonus_balance": 100,                     // Direct fields (no nesting)
    "number_stamps_total": 5,
    "number_rewards_unused": 2,
    "direct_install_link_universal": "https://...",
    "direct_install_link_apple": "https://...",
    "direct_install_link_google": "https://...",
    "direct_install_link_pwa": "https://...",
    "short_link": "https://...",
    "share_link": "https://...",
    "utm_source": "qr",
    "custom_fields": {}                       // Object (not array)
  }
}
```

### Key Field Mappings (Webhook → Database)

| Webhook Field | Database Field | Notes |
|---------------|----------------|-------|
| `serial_number` | `serial_number` | Card serial number |
| `cardholder_id` | `cardholder_id` | Cardholder/customer UUID |
| `card_type` | `card_type` | Card type |
| `device_type` | `device_type` | Device type |
| `template_id` | `template_id` | Template ID (string) |
| `cardholder_email` | `cardholder_email` | Email (direct field) |
| `cardholder_phone` | `cardholder_phone` | Phone (direct field) |
| `cardholder_first_name` | `cardholder_first_name` | First name (direct field) |
| `cardholder_last_name` | `cardholder_last_name` | Last name (direct field) |
| `cardholder_birth_date` | `cardholder_birth_date` | Birth date (direct field) |
| `bonus_balance` | `bonus_balance` | Points balance (direct field) |
| `number_stamps_total` | `number_stamps_total` | Stamps (direct field) |
| `number_rewards_unused` | `number_rewards_unused` | Rewards (direct field) |
| `direct_install_link_*` | `install_link_*` | Install links (direct fields) |
| `short_link` | `short_link` | Short link |
| `share_link` | `share_link` | Share link |
| `utm_source` | `utm_source` | UTM source |
| `custom_fields` | `custom_fields` | Object (stored as JSON string) |

## Key Differences Between Formats

### 1. Nested vs. Flat Structure

**List API:**
- Customer data is nested in `customer` object
- Balance data is nested in `balance` object
- Install links are nested in `directInstallLink` object

**Webhooks:**
- All fields are at the root level with prefixes (e.g., `cardholder_*`)
- No nested objects

### 2. Field Naming Conventions

**List API:**
- Uses camelCase: `firstName`, `dateOfBirth`, `bonusBalance`
- Nested objects: `customer.email`, `balance.bonusBalance`

**Webhooks:**
- Uses snake_case: `first_name`, `date_of_birth`, `bonus_balance`
- Prefixed fields: `cardholder_email`, `cardholder_phone`

### 3. Card Identifier Fields

**List API:**
- `id` = card serial number (e.g., "153111-927-114")
- `customerId` = cardholder ID (UUID)

**Webhooks:**
- `serial_number` = card serial number
- `cardholder_id` = cardholder ID (UUID)

### 4. Custom Fields Format

**List API:**
- Array of objects with `id`, `name`, `type`, `value`
```json
"customFields": [
  {"id": 123, "name": "First name", "type": "FName", "value": "John"}
]
```

**Webhooks:**
- Object with key-value pairs
```json
"custom_fields": {
  "first_name": "John"
}
```

## Implementation in InitialSyncService

The `InitialSyncService.updateCardFromApiData()` method handles both formats by:

1. **Checking for nested objects first** (List API format)
   ```java
   JsonNode customerNode = cardData.has("customer") ? cardData.get("customer") : null;
   if (customerNode != null) {
       // Extract from nested customer object
       card.setCardholderEmail(customerNode.get("email").asText());
   } else {
       // Extract from root level with prefix
       card.setCardholderEmail(cardData.get("cardholder_email").asText());
   }
   ```

2. **Trying both field name formats**
   ```java
   // Try List API format first
   if (cardData.has("type")) {
       card.setCardType(cardData.get("type").asText());
   } 
   // Fall back to webhook format
   else if (cardData.has("card_type")) {
       card.setCardType(cardData.get("card_type").asText());
   }
   ```

3. **Handling both identifier formats**
   ```java
   // List API uses "id" and "customerId"
   String serialNumber = cardData.has("id") ? cardData.get("id").asText() : null;
   String cardholderId = cardData.has("customerId") ? cardData.get("customerId").asText() : null;
   
   // Webhook uses "serial_number" and "cardholder_id"
   if (serialNumber == null) {
       serialNumber = cardData.has("serial_number") ? cardData.get("serial_number").asText() : null;
   }
   if (cardholderId == null) {
       cardholderId = cardData.has("cardholder_id") ? cardData.get("cardholder_id").asText() : null;
   }
   ```

## Testing Both Formats

### Test with List API Format
```bash
POST /api/integration-configs/Evergreen/sync
```

This triggers the initial sync which uses the List API format.

### Test with Webhook Format
```bash
POST /api/webhooks/boomerangme/Evergreen
Content-Type: application/json

{
  "event": "CardInstalledEvent",
  "data": {
    "serial_number": "123-456-789",
    "cardholder_id": "uuid-here",
    ...
  }
}
```

This sends a webhook event which uses the webhook format.

## Conclusion

By handling both formats in a single method, our system can:
- Process cards from the initial sync (List API)
- Process cards from webhook events
- Maintain consistency across both data sources
- Avoid code duplication

The implementation is robust and handles missing fields gracefully, ensuring that cards are saved correctly regardless of the source format.
