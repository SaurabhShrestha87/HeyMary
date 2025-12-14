# Boomerangme API Implementation

## ✅ What's Implemented

### BoomerangmeApiClient Service

Complete API client for Boomerangme Digital Wallet Cards API 2.0

---

## API Methods

### 1. Create Card (Issue Card for Customer)

**Method:** `createCard(apiKey, programId, cardholderData)`

**API Endpoint:** `POST /api/2.0/programs/{programId}/cards`

**Purpose:** Issue a new loyalty card for a customer

**Parameters:**
```java
Map<String, Object> cardholderData = {
    "cardholder_email": "customer@example.com",      // Required
    "cardholder_phone": "+1234567890",               // Recommended
    "cardholder_first_name": "John",                 // Optional
    "cardholder_last_name": "Doe",                   // Optional
    "cardholder_birth_date": "1990-01-15"            // Optional (YYYY-MM-DD)
};
```

**Response:**
```json
{
  "serial_number": "123456-789-012",
  "cardholder_id": "abc123-def456",
  "card_type": "reward",
  "status": "not_installed",
  "cardholder_email": "customer@example.com",
  "bonus_balance": 0,
  ...
}
```

**Usage in Code:**
```java
JsonNode response = boomerangmeApiClient
    .createCard(apiKey, programId, cardholderData)
    .block();

String serialNumber = response.get("serial_number").asText();
String cardholderId = response.get("cardholder_id").asText();
```

---

### 2. Add Scores to Card (Award Points)

**Method:** `addScoresToCard(apiKey, serialNumber, scores, comment)`

**API Endpoint:** `POST /api/2.0/cards/{serialNumber}/scores/add`

**Purpose:** Award loyalty points to a customer's card

**Parameters:**
```java
String serialNumber = "123456-789-012";
Integer scores = 100;  // Points to add
String comment = "Purchase of $100.00 - Order #12345";  // Optional
```

**Response:**
```json
{
  "serial_number": "123456-789-012",
  "bonus_balance": 100,  // Updated balance
  "cardholder_email": "customer@example.com",
  ...
}
```

**Usage in Code:**
```java
JsonNode response = boomerangmeApiClient
    .addScoresToCard(apiKey, serialNumber, 100, "Order #12345")
    .block();

Integer newBalance = response.get("bonus_balance").asInt();
```

---

### 3. Subtract Scores from Card (Redeem/Reverse Points)

**Method:** `subtractScoresFromCard(apiKey, serialNumber, scores, comment)`

**API Endpoint:** `POST /api/2.0/cards/{serialNumber}/scores/subtract`

**Purpose:** Subtract points for redemptions or reversals

**Parameters:**
```java
String serialNumber = "123456-789-012";
Integer scores = 50;  // Points to subtract
String comment = "Redeemed $5 discount - Order #12346";  // Optional
```

**Response:**
```json
{
  "serial_number": "123456-789-012",
  "bonus_balance": 50,  // Updated balance (was 100, now 50)
  "cardholder_email": "customer@example.com",
  ...
}
```

**Usage in Code:**
```java
JsonNode response = boomerangmeApiClient
    .subtractScoresFromCard(apiKey, serialNumber, 50, "Redemption")
    .block();

Integer newBalance = response.get("bonus_balance").asInt();
```

---

### 4. Get Card Details

**Method:** `getCard(apiKey, serialNumber)`

**API Endpoint:** `GET /api/2.0/cards/{serialNumber}`

**Purpose:** Retrieve current card information and balance

**Response:**
```json
{
  "serial_number": "123456-789-012",
  "cardholder_id": "abc123-def456",
  "cardholder_email": "customer@example.com",
  "cardholder_phone": "+1234567890",
  "bonus_balance": 100,
  "status": "installed",
  "device_type": "Google Pay",
  ...
}
```

**Usage in Code:**
```java
JsonNode card = boomerangmeApiClient
    .getCard(apiKey, serialNumber)
    .block();

Integer currentBalance = card.get("bonus_balance").asInt();
String status = card.get("status").asText();
```

---

### 5. Search Cards by Email

**Method:** `searchCardsByEmail(apiKey, email)`

**API Endpoint:** `GET /api/2.0/cards?email={email}`

**Purpose:** Find existing cards for a customer by email

**Response:**
```json
{
  "cards": [
    {
      "serial_number": "123456-789-012",
      "cardholder_email": "customer@example.com",
      "bonus_balance": 100,
      ...
    }
  ]
}
```

**Usage in Code:**
```java
JsonNode result = boomerangmeApiClient
    .searchCardsByEmail(apiKey, "customer@example.com")
    .block();

JsonNode cards = result.get("cards");
boolean exists = cards.size() > 0;
```

---

### 6. Update Card (Legacy)

**Method:** `updateCard(apiKey, cardId, customerData)`

**API Endpoint:** `PUT /api/2.0/cards/{cardId}`

**Purpose:** Update cardholder information

---

### 7. Add Points (Legacy method - use addScoresToCard instead)

**Method:** `addPoints(apiKey, cardId, points, reason)`

**Status:** Legacy - kept for backward compatibility

---

## Integration with TreezWebhookService

### Automatic Card Creation Flow

When a Treez CUSTOMER webhook is received:

```java
// TreezWebhookService.createCustomerWithBoomerangmeCard()

1. Check if Boomerangme card already exists
   └─ If YES: Link existing card to customer
   └─ If NO: Create new card via API

2. Prepare cardholder data
   Map<String, Object> cardholderData = {
       "cardholder_email": email,
       "cardholder_phone": phone,
       "cardholder_first_name": firstName,
       "cardholder_last_name": lastName,
       "cardholder_birth_date": birthDate  // if available
   };

3. Call Boomerangme API
   JsonNode response = boomerangmeApiClient
       .createCard(apiKey, programId, cardholderData)
       .block();

4. Parse response and save Card entity
   Card newCard = Card.builder()
       .serialNumber(response.get("serial_number").asText())
       .cardholderId(response.get("cardholder_id").asText())
       .cardholderEmail(email)
       .bonusBalance(0)
       .build();
   
   cardRepository.save(newCard);

5. Create Customer entity with card link
   Customer customer = Customer.builder()
       .merchantId(merchantId)
       .integrationType(IntegrationType.TREEZ)
       .posCustomerId(treezCustomerId)
       .card(newCard)
       .email(email)
       .build();
   
   customerRepository.save(customer);
```

---

## Error Handling

### Retry Logic

All API methods include automatic retry for:
- HTTP 500+ errors (server errors)
- Network failures
- Connection timeouts

**Retry Configuration:**
- Max retries: 2
- Backoff: Exponential (1s, 2s, 4s)
- Only retries on server errors, not client errors (4xx)

### Error Logging

```java
try {
    JsonNode response = boomerangmeApiClient
        .createCard(apiKey, programId, data)
        .block();
} catch (ApiException e) {
    log.error("Boomerangme API error: {} - {}", 
        e.getStatusCode(), e.getBody());
    // Error added to dead letter queue
}
```

---

## Testing

### Test Card Creation

```bash
# This will be called automatically when Treez customer is created
# Or you can test manually:

curl -X POST "https://api.boomerangme.cards/api/2.0/programs/{programId}/cards" \
  -H "Authorization: Bearer YOUR_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "cardholder_email": "test@example.com",
    "cardholder_phone": "+1234567890",
    "cardholder_first_name": "Test",
    "cardholder_last_name": "User"
  }'
```

### Test Add Scores

```bash
curl -X POST "https://api.boomerangme.cards/api/2.0/cards/{serialNumber}/scores/add" \
  -H "Authorization: Bearer YOUR_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "scores": 100,
    "comment": "Test points"
  }'
```

---

## Configuration

### Required Settings in Database

```sql
UPDATE integration_configs 
SET 
    boomerangme_api_key = 'your_api_key_here',
    boomerangme_program_id = 'your_program_id_here'
WHERE merchant_id = 'Evergreen';
```

### Card Type Configuration

Recommended card type: **Reward Card (ID 7)** with **Points mechanics**

This card type supports:
- ✅ Add scores (points)
- ✅ Subtract scores (points)
- ✅ Bonus balance tracking
- ✅ Visit tracking
- ✅ Purchase history

---

## Monitoring

### Check API Logs

```bash
# View Boomerangme API calls
grep "Boomerangme API" logs/integrations.log

# View card creations
grep "Creating Boomerangme card" logs/treez-webhooks.log

# View points additions
grep "Adding.*scores" logs/integrations.log

# View API errors
grep "Boomerangme API error" logs/errors.log
```

### Database Queries

```sql
-- Recently created cards
SELECT 
    serial_number,
    cardholder_email,
    bonus_balance,
    status,
    created_at
FROM cards
ORDER BY created_at DESC
LIMIT 10;

-- Customers with cards
SELECT 
    c.id,
    c.email,
    c.total_points,
    ca.serial_number,
    ca.bonus_balance
FROM customers c
JOIN cards ca ON c.card_id = ca.id
WHERE c.merchant_id = 'Evergreen';

-- Sync status
SELECT 
    merchant_id,
    sync_type,
    status,
    COUNT(*)
FROM sync_logs
WHERE created_at > NOW() - INTERVAL '1 hour'
GROUP BY merchant_id, sync_type, status;
```

---

## Next Steps

### 1. Test Card Creation

- [ ] Create test customer in Treez
- [ ] Verify CUSTOMER webhook received
- [ ] Verify Boomerangme card created
- [ ] Verify Card saved in database
- [ ] Verify Customer linked to Card

### 2. Test Points Awarding

- [ ] Complete test order in Treez
- [ ] Verify TICKET webhook received
- [ ] Verify points calculated
- [ ] Verify addScoresToCard called
- [ ] Verify bonus_balance updated

### 3. Test Error Handling

- [ ] Test with invalid API key
- [ ] Test with missing email
- [ ] Test with API downtime
- [ ] Verify errors logged
- [ ] Verify dead letter queue

---

## API Documentation References

- **Boomerangme API 2.0:** See `digitalwallet_boomerangme.OAPI.doc.json`
- **Create Card:** POST /api/2.0/programs/{programId}/cards
- **Add Scores:** POST /api/2.0/cards/{serialNumber}/scores/add
- **Subtract Scores:** POST /api/2.0/cards/{serialNumber}/scores/subtract
- **Get Card:** GET /api/2.0/cards/{serialNumber}
- **Search Cards:** GET /api/2.0/cards?email={email}

---

## Related Documentation

- [CUSTOMER_SYNC_IMPLEMENTATION.md](CUSTOMER_SYNC_IMPLEMENTATION.md) - Customer sync logic
- [LOYALTY_INTEGRATION_PLAN.md](LOYALTY_INTEGRATION_PLAN.md) - Complete integration plan
- [TREEZ_WEBHOOK_GUIDE.md](TREEZ_WEBHOOK_GUIDE.md) - Treez integration
- [IMPLEMENTATION_ROADMAP.md](IMPLEMENTATION_ROADMAP.md) - Development roadmap

---

## Status

✅ **COMPLETE:** Boomerangme API client fully implemented and integrated

**Ready for testing!** 🚀

The system will now automatically:
1. Create Boomerangme cards when Treez customers are created
2. Link existing cards when found by email
3. Handle errors gracefully with retries
4. Log all API operations
5. Store card data in database

