# Auto-Create Treez Customers

## Overview

When performing initial sync or processing card installations, the system now automatically creates Treez customers if they don't already exist in the Treez POS system. This ensures that all Boomerangme cardholders have corresponding customer records in Treez.

## Flow Diagram

```
Card Installation/Initial Sync
         ↓
Check Local Database for Customer
         ↓
    ┌────┴────┐
    │ Found?  │
    └────┬────┘
         │
    ┌────┴────────────────┐
    │                     │
   YES                   NO
    │                     │
    ↓                     ↓
Link Card          Search Treez POS
to Customer        (by email/phone)
    │                     │
    │                ┌────┴────┐
    │                │ Found?  │
    │                └────┬────┘
    │                     │
    │                ┌────┴────────────┐
    │                │                 │
    │               YES               NO
    │                │                 │
    │                ↓                 ↓
    │         Create Local       CREATE NEW
    │         Customer Record    TREEZ CUSTOMER
    │         with Treez ID            │
    │                │                 │
    │                ↓                 ↓
    │         Link Card to      Extract Customer ID
    │         Customer          from Response
    │                │                 │
    │                ↓                 ↓
    └────────────────┴─────────────────┘
                     │
                     ↓
              Save to Database
```

## Implementation

### 1. Customer Linking Logic

**Location:** `InitialSyncService.linkCardToTreezCustomer()`

**Process:**
1. Check if customer exists in local database (by email/phone)
2. If not found locally, search Treez POS API
3. If not found in Treez, **create new customer**
4. Link card to customer record
5. Save to database

### 2. Customer Creation

**Location:** `InitialSyncService.createTreezCustomer()`

**Required Fields:**
- `birthday` - Customer's birth date (YYYY-MM-DD format)
- `first_name` - Customer's first name
- `last_name` - Customer's last name
- `patient_type` - Account type (default: "ADULT")
- `drivers_license` - Driver's license number (default: "N/A")
- `drivers_license_expiration` - License expiration (default: "2030-12-31")

**Optional Fields:**
- `email` - Customer's email address
- `phone` - Customer's phone number (10 digits, no country code)
- `gender` - Gender (default: "U" for unspecified)
- `rewards_balance` - Initial rewards points from Boomerangme
- `banned` - Whether customer is banned (default: false)
- `opt_out` - Marketing opt-out status (default: false)

### 3. Data Mapping

**From Boomerangme Card to Treez Customer:**

| Boomerangme Field | Treez Field | Notes |
|-------------------|-------------|-------|
| `cardholderBirthDate` | `birthday` | Required, YYYY-MM-DD format |
| `cardholderFirstName` | `first_name` | Required |
| `cardholderLastName` | `last_name` | Required |
| `cardholderEmail` | `email` | Optional |
| `cardholderPhone` | `phone` | Normalized to 10 digits |
| `bonusBalance` | `rewards_balance` | Optional, syncs points |
| - | `patient_type` | Default: "ADULT" |
| - | `gender` | Default: "U" (unspecified) |
| - | `drivers_license` | Default: "N/A" |
| - | `drivers_license_expiration` | Default: "2030-12-31" |

### 4. Phone Number Normalization

Boomerangme stores US phone numbers with country code "1" prefix (11 digits), while Treez requires 10 digits without prefix.

**Example:**
```
Boomerangme: 12138167531 (11 digits)
      ↓
Normalized: 2138167531 (10 digits)
      ↓
Treez API: 2138167531
```

**Code:**
```java
private String normalizePhoneForTreez(String phone) {
    String digitsOnly = phone.replaceAll("[^0-9]", "");
    
    if (digitsOnly.startsWith("1") && digitsOnly.length() == 11) {
        return digitsOnly.substring(1);  // Remove "1" prefix
    }
    
    return digitsOnly;
}
```

## API Request Example

### Create Customer in Treez

```bash
POST https://api.treez.io/v2.0/dispensary/partnersandbox3/customer/detailcustomer
Authorization: {access_token}
client_id: {client_id}
Content-Type: application/json

{
  "birthday": "1990-01-01",
  "first_name": "John",
  "last_name": "Doe",
  "email": "john.doe@example.com",
  "phone": "2138167531",
  "patient_type": "ADULT",
  "gender": "U",
  "drivers_license": "N/A",
  "drivers_license_expiration": "2030-12-31",
  "banned": false,
  "opt_out": false,
  "rewards_balance": 100
}
```

### Success Response

```json
{
  "resultCode": "SUCCESS",
  "resultReason": null,
  "data": [
    {
      "customer_id": "2143",
      "first_name": "JOHN",
      "last_name": "DOE",
      "email": "john.doe@example.com",
      "phone": "2138167531",
      "birthday": "1990-01-01",
      "patient_type": "ADULT",
      "status": "ACTIVE",
      "verification_status": "VERIFICATION_PENDING",
      "rewards_balance": 100,
      "signup_date": "2026-01-11T20:00:00.000-08:00"
    }
  ]
}
```

## Error Handling

### Missing Required Fields

If card is missing required fields, customer creation is skipped:

```java
if (card.getCardholderBirthDate() == null) {
    log.warn("Cannot create Treez customer: birthday is required");
    return null;
}
```

**Required Fields Check:**
- ✅ Birth date
- ✅ First name
- ✅ Last name

### Invalid Phone Number

If phone number is not 10 digits after normalization:

```java
if (digitsOnly.length() != 10) {
    log.warn("Phone number is not 10 digits, skipping phone field");
    // Customer is still created, just without phone
}
```

### API Errors

If Treez API returns an error:

```java
if (!"SUCCESS".equals(response.get("resultCode").asText())) {
    String reason = response.get("resultReason").asText();
    log.error("Treez customer creation failed: {}", reason);
    return null;
}
```

**Common Errors:**
- **Duplicate email** - Email already exists in Treez
- **Duplicate phone** - Phone already exists in Treez
- **Invalid date format** - Birth date not in YYYY-MM-DD format
- **Missing required fields** - Required fields not provided

## Logging

### Successful Creation

```
INFO  - Customer not found in Treez for card 153111-927-114, attempting to create new customer
INFO  - Creating Treez customer for card 153111-927-114: email=john@example.com, phone=2138167531, name=John Doe
INFO  - Successfully created Treez customer 2143 for card 153111-927-114
INFO  - Created new Treez customer 2143 and linked to card 153111-927-114
```

### Missing Required Data

```
WARN  - Customer not found in Treez for card 153111-927-114, attempting to create new customer
WARN  - Cannot create Treez customer: birthday is required but missing for card 153111-927-114
WARN  - Failed to create Treez customer for card 153111-927-114 (missing required data)
```

### API Error

```
INFO  - Customer not found in Treez for card 153111-927-114, attempting to create new customer
ERROR - Treez customer creation failed for card 153111-927-114: DUPLICATE_EMAIL
ERROR - Error creating Treez customer for card 153111-927-114: Failed to create customer
```

## Database Records

### Customer Table Entry

After successful creation:

```sql
SELECT * FROM customers WHERE external_customer_id = '2143';
```

**Result:**
```
id: 1
merchant_id: Evergreen
integration_type: TREEZ
external_customer_id: 2143
card_id: 1
treez_email: john.doe@example.com
treez_phone: 2138167531
treez_first_name: John
treez_last_name: Doe
treez_birth_date: 1990-01-01
total_points: 100
synced_at: 2026-01-11 20:15:00
created_at: 2026-01-11 20:15:00
```

## Benefits

### 1. Automatic Customer Onboarding
- No manual customer creation needed
- Seamless integration between Boomerangme and Treez
- Reduces administrative overhead

### 2. Data Consistency
- Ensures all cardholders have Treez accounts
- Maintains synchronized customer data
- Links loyalty points to POS records

### 3. Improved Customer Experience
- Customers can use their Boomerangme card immediately
- No delays waiting for manual account creation
- Rewards points automatically available in POS

### 4. Error Recovery
- If customer creation fails, system logs the error
- Initial sync can be re-run to retry failed creations
- Manual intervention only needed for edge cases

## Testing

### Test Scenario 1: New Customer

**Setup:**
- Card with complete data (name, email, phone, birth date)
- Customer doesn't exist in Treez

**Expected Result:**
1. ✅ Customer created in Treez
2. ✅ Customer record saved to database
3. ✅ Card linked to customer
4. ✅ Rewards points synced

### Test Scenario 2: Missing Birth Date

**Setup:**
- Card without birth date
- Customer doesn't exist in Treez

**Expected Result:**
1. ⚠️ Customer creation skipped
2. ⚠️ Warning logged
3. ❌ No customer record created
4. ❌ Card not linked

### Test Scenario 3: Duplicate Email

**Setup:**
- Card with email that exists in Treez
- Customer doesn't exist in local database

**Expected Result:**
1. ❌ Customer creation fails (duplicate email)
2. ⚠️ Error logged
3. 🔄 System should search for existing customer by email
4. ✅ Link to existing customer if found

## Configuration

No additional configuration required. The feature is automatically enabled for all Treez integrations.

**Integration Config Requirements:**
- `treez_api_key` - For token generation
- `treez_client_id` - For API requests
- `treez_dispensary_id` - Dispensary identifier
- `integration_type` = "TREEZ"
- `customer_match_type` - EMAIL or PHONE

## Monitoring

### Success Metrics

Monitor these logs to track customer creation:

```bash
# Count successful creations
grep "Successfully created Treez customer" logs/integrations.log | wc -l

# Count failed creations
grep "Failed to create Treez customer" logs/integrations.log | wc -l

# Count missing required data
grep "Cannot create Treez customer" logs/integrations.log | wc -l
```

### Database Queries

```sql
-- Count customers created today
SELECT COUNT(*) FROM customers 
WHERE integration_type = 'TREEZ' 
AND DATE(created_at) = CURRENT_DATE;

-- Find customers without Treez ID
SELECT * FROM customers 
WHERE integration_type = 'TREEZ' 
AND external_customer_id IS NULL;

-- Find cards without linked customers
SELECT c.* FROM cards c
LEFT JOIN customers cu ON cu.card_id = c.id
WHERE cu.id IS NULL AND c.status = 'installed';
```

## Troubleshooting

### Issue: Customer not created

**Check:**
1. Does card have required fields? (birth date, first name, last name)
2. Is Treez API accessible?
3. Are Treez credentials configured correctly?
4. Check logs for specific error messages

### Issue: Duplicate customer error

**Solution:**
- Search for existing customer by email/phone
- Link card to existing customer instead of creating new one
- This is handled automatically by the system

### Issue: Phone number rejected

**Check:**
- Is phone number 10 digits after normalization?
- Does phone start with 0 or 1? (Treez rejects these)
- Try creating customer without phone field

## Related Documentation

- [Initial Sync Implementation](./INITIAL_SYNC_IMPLEMENTATION.md)
- [Treez Token Authentication](./TREEZ_TOKEN_AUTHENTICATION.md)
- [Customer Sync Implementation](./CUSTOMER_SYNC_IMPLEMENTATION.md)
- [Treez API Documentation](https://api.treez.io/docs)
