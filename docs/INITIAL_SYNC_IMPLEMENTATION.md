# Initial Sync Implementation

## Overview

This document describes the implementation of the initial synchronization feature that fetches all cards and customers from Boomerangme when a new integration configuration is created.

## Feature Description

When a new integration configuration is created (or manually triggered), the system will:

1. **Fetch all cards** from Boomerangme API using pagination
2. **Save cards** to the local database with all card details
3. **Link cards to POS customers** by matching email or phone number
4. **Create customer records** linking Boomerangme cards to Treez POS customers

## Implementation Details

### 1. New API Method: `BoomerangmeApiClient.getCards()`

**Location:** `src/main/java/heymary/co/integrations/service/BoomerangmeApiClient.java`

```java
public Mono<JsonNode> getCards(String apiKey, int page, int itemsPerPage)
```

**Purpose:** Fetches cards from Boomerangme API with pagination support

**Parameters:**
- `apiKey` - Boomerangme API key
- `page` - Page number (starts at 1)
- `itemsPerPage` - Number of items per page (default 50, max 100)

**API Endpoint:** `GET /api/v2/cards?page={page}&itemsPerPage={itemsPerPage}`

**Returns:** JsonNode containing paginated card data

### 2. New Service: `InitialSyncService`

**Location:** `src/main/java/heymary/co/integrations/service/InitialSyncService.java`

**Main Method:** `performInitialSync(IntegrationConfig config)`

**Responsibilities:**
- Orchestrates the entire initial sync process
- Handles pagination through all cards
- Saves cards to database
- Links cards to POS customers
- Runs asynchronously to avoid blocking API responses

**Key Features:**
- **Pagination Logic:** Automatically fetches all pages until no more cards are returned
- **Error Handling:** Continues processing even if individual cards fail
- **Phone Normalization:** Handles phone number format differences between Boomerangme (with "1" prefix) and Treez (without prefix)
- **Duplicate Detection:** Checks for existing cards before creating new ones
- **Customer Matching:** Supports both EMAIL and PHONE matching based on configuration

**Process Flow:**

```
1. Start with page 1, itemsPerPage = 50
2. Fetch cards from Boomerangme API
3. For each card:
   a. Check if card already exists (by cardholder_id or serial_number)
   b. Save or update card in database
   c. If card status is "installed":
      - Check if customer exists locally (by email/phone)
      - If not found locally, search Treez POS API
      - Create Customer record linking card to Treez customer
4. If cards returned < itemsPerPage, stop pagination
5. Otherwise, increment page and repeat from step 2
6. Log summary: total cards fetched, processed, customers linked
```

### 3. Updated Controller: `IntegrationConfigController`

**Location:** `src/main/java/heymary/co/integrations/controller/IntegrationConfigController.java`

**Changes:**

#### Modified Endpoint: `POST /api/integration-configs`

**New Behavior:**
- Creates integration configuration
- **Automatically triggers initial sync** in the background
- Returns response with message indicating sync has started

**Response Format:**
```json
{
  "config": { /* IntegrationConfig object */ },
  "message": "Integration config created successfully. Initial sync of cards and customers has been triggered in the background."
}
```

#### New Endpoint: `POST /api/integration-configs/{merchantId}/sync`

**Purpose:** Manually trigger initial sync for an existing merchant

**Use Cases:**
- Re-sync data after configuration changes
- Retry sync if initial sync failed
- Periodic full sync to catch any missed updates

**Response Format:**
```json
{
  "message": "Initial sync triggered successfully for merchant: {merchantId}",
  "details": "The sync is running in the background. Check the sync logs for progress."
}
```

**HTTP Status:** 202 Accepted (indicates async processing)

## Phone Number Normalization

The system handles phone number format differences between systems:

**Boomerangme Format:**
- US phone numbers stored with country code "1" prefix
- Example: "19999999999" (11 digits)

**Treez Format:**
- Phone numbers stored without country code
- Example: "9999999999" (10 digits)

**Normalization Logic:**
1. Extract digits only from phone number
2. If starts with "1" and has 11 digits, remove the "1" prefix
3. Use normalized phone for Treez API calls and database lookups

## Customer Matching Strategy

The system supports two matching strategies (configured via `customer_match_type`):

### EMAIL Matching
1. Check local database for customer with matching email
2. If not found, query Treez API: `GET /v2.0/dispensary/{dispensaryId}/customer/email/{email}`
3. If found in Treez, create local Customer record linking card to Treez customer

### PHONE Matching
1. Normalize phone number (remove "1" prefix)
2. Check local database for customer with matching phone
3. If not found, query Treez API: `GET /v2.0/dispensary/{dispensaryId}/customer/phone/{phone}`
4. If found in Treez, create local Customer record linking card to Treez customer

## Database Schema

### Cards Table
Stores all Boomerangme card data:
- `cardholder_id` - Primary identifier (required, unique)
- `serial_number` - Optional card serial number
- `status` - Card status ("not_installed", "installed")
- `cardholder_email`, `cardholder_phone` - Contact information
- `cardholder_first_name`, `cardholder_last_name` - Name
- `cardholder_birth_date` - Date of birth
- `bonus_balance` - Loyalty points balance
- `device_type` - Installation device type
- And many more fields for card metrics, links, etc.

### Customers Table
Links Boomerangme cards to POS customers:
- `merchant_id` - Merchant identifier
- `integration_type` - POS type (TREEZ, DUTCHIE)
- `external_customer_id` - POS customer ID
- `card_id` - Foreign key to Cards table (one-to-one)
- `treez_email`, `treez_phone` - Treez customer contact info
- `treez_first_name`, `treez_last_name` - Treez customer name
- `total_points` - Synced loyalty points

## Async Processing

The initial sync runs asynchronously using Spring's `@Async` annotation with the `syncTaskExecutor` thread pool. This ensures:

1. **Non-blocking API responses** - Config creation returns immediately
2. **Background processing** - Sync continues in separate thread
3. **Resource management** - Thread pool prevents overwhelming the system
4. **Scalability** - Multiple merchants can sync simultaneously

## Error Handling

The implementation includes robust error handling:

1. **Card-level errors:** If a single card fails to process, the sync continues with remaining cards
2. **Page-level errors:** If a page fetch fails, the error is logged and sync stops gracefully
3. **API errors:** Retries with exponential backoff for 5xx errors
4. **Validation errors:** Missing required fields are logged and skipped
5. **Duplicate handling:** Existing cards are updated rather than creating duplicates

## Logging

Comprehensive logging at multiple levels:

**INFO Level:**
- Sync start/completion with summary statistics
- Page fetching progress
- Customer linking success
- Manual sync triggers

**DEBUG Level:**
- Individual card processing
- Customer matching attempts
- Phone normalization

**ERROR Level:**
- API failures with status codes and response bodies
- Card processing errors with identifiers
- Customer linking failures

**Log Example:**
```
INFO  - Starting initial sync for merchant: merchant123
INFO  - Fetching cards page 1 for merchant merchant123
DEBUG - Processing 50 cards from page 1
DEBUG - Card abc123 saved successfully
INFO  - Linked card abc123 to existing Treez customer 456
INFO  - Fetching cards page 2 for merchant merchant123
INFO  - Received 30 cards (less than 50), ending pagination
INFO  - Initial sync completed for merchant merchant123. Cards fetched: 80, Cards processed: 80, Customers linked: 45
```

## Testing Recommendations

### Unit Tests
1. Test pagination logic with various page sizes
2. Test phone normalization with different formats
3. Test customer matching logic
4. Test error handling for API failures

### Integration Tests
1. Test full sync flow with mock Boomerangme API
2. Test customer linking with mock Treez API
3. Test duplicate card handling
4. Test async execution

### Manual Testing
1. Create integration config and verify sync triggers
2. Check database for saved cards
3. Verify customer linking for installed cards
4. Test manual sync endpoint
5. Monitor logs for errors

## API Usage Examples

### Create Integration Config (Triggers Initial Sync)

```bash
POST /api/integration-configs
Content-Type: application/json
X-Admin-API-Key: your-admin-key

{
  "merchantId": "merchant123",
  "integrationType": "TREEZ",
  "boomerangmeApiKey": "bmg_key_xxx",
  "treezApiKey": "treez_key_xxx",
  "treezDispensaryId": "dispensary123",
  "customerMatchType": "PHONE",
  "enabled": true
}
```

**Response (201 Created):**
```json
{
  "config": {
    "id": 1,
    "merchantId": "merchant123",
    "integrationType": "TREEZ",
    "enabled": true,
    ...
  },
  "message": "Integration config created successfully. Initial sync of cards and customers has been triggered in the background."
}
```

### Manually Trigger Initial Sync

```bash
POST /api/integration-configs/merchant123/sync
X-Admin-API-Key: your-admin-key
```

**Response (202 Accepted):**
```json
{
  "message": "Initial sync triggered successfully for merchant: merchant123",
  "details": "The sync is running in the background. Check the sync logs for progress."
}
```

## Performance Considerations

1. **Pagination:** Uses 50 items per page to balance API calls vs. memory usage
2. **Async Processing:** Prevents blocking the main API thread
3. **Database Transactions:** Each card is saved in a transaction to ensure data consistency
4. **API Rate Limiting:** Built-in retry logic respects API rate limits
5. **Memory Management:** Processes cards page by page rather than loading all at once

## Future Enhancements

Potential improvements for future versions:

1. **Progress Tracking:** Add sync progress endpoint to check status
2. **Selective Sync:** Option to sync only cards modified after a certain date
3. **Batch Processing:** Save multiple cards in a single transaction
4. **Sync Scheduling:** Periodic automatic syncs (e.g., daily)
5. **Conflict Resolution:** Better handling of data conflicts between systems
6. **Sync History:** Track sync attempts and results over time
7. **Notification:** Alert on sync completion or failures

## Related Documentation

- [Boomerangme API Documentation](https://docs.boomerangme.cards/api/v2)
- [Treez API Documentation](https://api.treez.io/docs)
- [Customer Sync Implementation](./CUSTOMER_SYNC_IMPLEMENTATION.md)
- [Integration Architecture](./INTEGRATION_ARCHITECTURE.md)
