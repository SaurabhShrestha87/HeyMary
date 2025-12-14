
### Field mapping

| Webhook Format | API Response Format | Notes |
|----------------|---------------------|-------|
| `serial_number` | `id` | Card serial number |
| `cardholder_id` | `customerId` or `customer.id` | Primary identifier |
| `cardholder_email` | `customer.email` or `customFields` | Email address |
| `cardholder_phone` | `customer.phone` or `customFields` | Phone number |
| `cardholder_first_name` | `customer.firstName` or `customFields` | First name |
| `cardholder_last_name` | `customer.surname` or `customFields` | Last name |
| `cardholder_birth_date` | `customer.dateOfBirth` or `customFields` | Birth date |
| `bonus_balance` | `balance.bonusBalance` | Points balance |
| `device_type` | `device` | Device type |
| `install_link_*` | `directInstallLink.*` | Install links |
