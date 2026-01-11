# Customer Match Type: BOTH

## Overview

The `BOTH` customer match type allows matching Treez customers with Boomerangme cards using **both email AND phone number**. This provides the most flexible and accurate customer matching by trying both identifiers.

## Match Types

### EMAIL
- Matches customers by email address only
- Use when email is the primary identifier
- Best for customers who may change phone numbers

### PHONE
- Matches customers by phone number only
- Use when phone is the primary identifier
- Best for customers who may change email addresses

### BOTH (Default)
- Tries to match by email **first**
- Falls back to phone if email doesn't match
- Provides the best chance of finding existing customers
- **Recommended for most use cases**

## How BOTH Matching Works

### 1. Local Database Search

```
Search Local Database
        ↓
Try Email Match
        ↓
    ┌───┴───┐
    │Found? │
    └───┬───┘
        │
   ┌────┴────┐
   │         │
  YES       NO
   │         │
   ↓         ↓
Return    Try Phone Match
Customer      ↓
          ┌───┴───┐
          │Found? │
          └───┬───┘
              │
         ┌────┴────┐
         │         │
        YES       NO
         │         │
         ↓         ↓
      Return    Return
      Customer   null
```

### 2. Treez POS Search

```
Search Treez POS API
        ↓
Try Email Lookup
  (GET /customer/email/{email})
        ↓
    ┌───┴───┐
    │Found? │
    └───┬───┘
        │
   ┌────┴────┐
   │         │
  YES       NO
   │         │
   ↓         ↓
Create    Try Phone Lookup
Customer  (GET /customer/phone/{phone})
Record        ↓
          ┌───┴───┐
          │Found? │
          └───┬───┘
              │
         ┌────┴────┐
         │         │
        YES       NO
         │         │
         ↓         ↓
      Create    Create New
      Customer  Treez Customer
      Record
```

## Implementation

### Configuration

**Database Column:**
```sql
customer_match_type VARCHAR(20) DEFAULT 'BOTH'
```

**Java Enum:**
```java
public enum CustomerMatchType {
    PHONE("phone", "Phone Number"),
    EMAIL("email", "Email Address"),
    BOTH("both", "Phone and Email");  // Default
}
```

**Integration Config:**
```java
@Column(name = "customer_match_type", length = 20)
@Builder.Default
private CustomerMatchType customerMatchType = CustomerMatchType.BOTH;
```

### Matching Logic

**InitialSyncService.findExistingTreezCustomer():**

```java
if (matchType == CustomerMatchType.BOTH) {
    // Try email first
    if (email != null && !email.isEmpty()) {
        Optional<Customer> byEmail = customerRepository
            .findByMerchantIdAndTreezEmailAndIntegrationType(
                merchantId, email, IntegrationType.TREEZ);
        if (byEmail.isPresent()) {
            return byEmail.get();
        }
    }
    
    // Try phone if email didn't match
    if (phone != null && !phone.isEmpty()) {
        String normalizedPhone = normalizePhoneFromBoomerangme(phone);
        Optional<Customer> byPhone = customerRepository
            .findByMerchantIdAndTreezPhoneAndIntegrationType(
                merchantId, normalizedPhone, IntegrationType.TREEZ);
        if (byPhone.isPresent()) {
            return byPhone.get();
        }
    }
}
```

**InitialSyncService.findCustomerInTreezPOS():**

```java
if (matchType == CustomerMatchType.BOTH) {
    // Try email first
    if (email != null && !email.isEmpty()) {
        try {
            treezCustomerData = treezApiClient
                .findCustomerByEmail(config, email).block();
        } catch (Exception e) {
            log.debug("Customer not found by email, trying phone");
        }
    }
    
    // If not found by email, try phone
    if (treezCustomerData == null && phone != null && !phone.isEmpty()) {
        String normalizedPhone = normalizePhoneForTreez(phone);
        try {
            treezCustomerData = treezApiClient
                .findCustomerByPhone(config, normalizedPhone).block();
        } catch (Exception e) {
            log.debug("Customer not found by phone either");
        }
    }
}
```

## Examples

### Example 1: Match by Email

**Card Data:**
```json
{
  "cardholder_email": "john@example.com",
  "cardholder_phone": "12138167531"
}
```

**Process:**
1. Search local DB by email `john@example.com` → **Found!**
2. Return customer, skip phone search
3. Link card to customer

### Example 2: Match by Phone (Email Not Found)

**Card Data:**
```json
{
  "cardholder_email": "john.new@example.com",
  "cardholder_phone": "12138167531"
}
```

**Process:**
1. Search local DB by email `john.new@example.com` → Not found
2. Search local DB by phone `2138167531` → **Found!**
3. Return customer
4. Link card to customer

### Example 3: Not Found Locally, Found in Treez by Email

**Card Data:**
```json
{
  "cardholder_email": "jane@example.com",
  "cardholder_phone": "12132938005"
}
```

**Process:**
1. Search local DB by email → Not found
2. Search local DB by phone → Not found
3. Search Treez API by email → **Found!** (customer_id: 2143)
4. Create local customer record with Treez ID
5. Link card to customer

### Example 4: Not Found Anywhere, Create New

**Card Data:**
```json
{
  "cardholder_email": "new@example.com",
  "cardholder_phone": "12139999999",
  "cardholder_first_name": "New",
  "cardholder_last_name": "Customer",
  "cardholder_birth_date": "1990-01-01"
}
```

**Process:**
1. Search local DB by email → Not found
2. Search local DB by phone → Not found
3. Search Treez API by email → Not found
4. Search Treez API by phone → Not found
5. **Create new Treez customer**
6. Save customer record with new Treez ID
7. Link card to customer

## Validation

### Required Data for BOTH Match Type

When using `BOTH`, the system requires **both email AND phone** to be present:

```java
if (matchType == CustomerMatchType.BOTH) {
    if (card.getCardholderEmail() == null || card.getCardholderEmail().isEmpty()) {
        log.debug("Card has no email for BOTH matching, skipping");
        return false;
    }
    if (card.getCardholderPhone() == null || card.getCardholderPhone().isEmpty()) {
        log.debug("Card has no phone for BOTH matching, skipping");
        return false;
    }
}
```

**Result:** If either email or phone is missing, the card is skipped.

## Logging

### Successful Match by Email

```
DEBUG - Found existing Treez customer by email: john@example.com
INFO  - Linked card 153111-927-114 to existing Treez customer 1
```

### Fallback to Phone Match

```
DEBUG - Customer not found by email, trying phone
DEBUG - Found existing Treez customer by phone: 2138167531
INFO  - Linked card 153111-927-114 to existing Treez customer 2
```

### Not Found, Creating New

```
DEBUG - Customer not found by email, trying phone
DEBUG - Customer not found by phone either
INFO  - Customer not found in Treez for card 153111-927-114, attempting to create new customer
INFO  - Creating Treez customer for card 153111-927-114: email=new@example.com, phone=2138167531
INFO  - Successfully created Treez customer 2143 for card 153111-927-114
```

### Missing Required Data

```
DEBUG - Card 153111-927-114 has no email for matching (match type: BOTH), skipping customer linking
```

## Configuration Examples

### Set to BOTH (Default)

```sql
INSERT INTO integration_configs (
    merchant_id,
    customer_match_type,
    ...
) VALUES (
    'Evergreen',
    'BOTH',  -- Default: try email first, then phone
    ...
);
```

### Set to EMAIL Only

```sql
UPDATE integration_configs 
SET customer_match_type = 'EMAIL'
WHERE merchant_id = 'Evergreen';
```

### Set to PHONE Only

```sql
UPDATE integration_configs 
SET customer_match_type = 'PHONE'
WHERE merchant_id = 'Evergreen';
```

## Benefits of BOTH

### 1. Higher Match Rate
- More chances to find existing customers
- Reduces duplicate customer creation
- Better data consistency

### 2. Handles Data Changes
- Customers who change email can still be matched by phone
- Customers who change phone can still be matched by email
- More resilient to data updates

### 3. Flexible Matching
- Adapts to data quality issues
- Works even if one field is incorrect
- Increases successful customer linking

### 4. Best Practice
- Recommended by Treez for most use cases
- Provides best balance of accuracy and flexibility
- Reduces manual intervention

## Performance Considerations

### API Calls

**EMAIL Only:** 1 API call per customer lookup
**PHONE Only:** 1 API call per customer lookup
**BOTH:** Up to 2 API calls per customer lookup (if email fails)

**Optimization:** Email is tried first because it's typically more stable than phone numbers.

### Database Queries

**EMAIL Only:** 1 query per customer lookup
**PHONE Only:** 1-2 queries per customer lookup (normalized + original)
**BOTH:** 2-3 queries per customer lookup (email + normalized phone + original phone)

**Impact:** Minimal - database queries are very fast

## Troubleshooting

### Issue: Card skipped with BOTH match type

**Cause:** Card is missing either email or phone

**Solution:** 
- Ensure Boomerangme cards have both email and phone
- Or change match type to EMAIL or PHONE only

### Issue: Duplicate customers created

**Cause:** Email and phone don't match between systems

**Example:**
- Boomerangme: email=john@example.com, phone=1234567890
- Treez: email=john@gmail.com, phone=1234567890

**Solution:**
- Update data in one system to match the other
- Manually merge duplicate customers in Treez
- Consider using PHONE only if phone is more reliable

### Issue: Customer not found despite existing

**Cause:** Phone number format mismatch

**Example:**
- Boomerangme: 12138167531 (11 digits with "1")
- Treez: 2138167531 (10 digits without "1")

**Solution:** System automatically normalizes phone numbers

## Related Documentation

- [Initial Sync Implementation](./INITIAL_SYNC_IMPLEMENTATION.md)
- [Auto-Create Treez Customers](./AUTO_CREATE_TREEZ_CUSTOMERS.md)
- [Customer Sync Implementation](./CUSTOMER_SYNC_IMPLEMENTATION.md)
