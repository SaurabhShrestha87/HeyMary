# HeyMary Integrations Service

Integration service for syncing Boomerangme loyalty program with Dutchie POS systems.

## Features

- Real-time webhook-based synchronization
- Bidirectional customer sync between Dutchie and Boomerangme
- Automatic points calculation and sync for orders
- Dead letter queue for failed syncs
- Comprehensive logging and monitoring
- HMAC signature validation for webhooks

## Technology Stack

- Spring Boot 3.4.12
- Java 17
- PostgreSQL
- Flyway for database migrations
- Docker & Docker Compose

## Prerequisites

- Java 17+
- Maven 3.6+
- Docker & Docker Compose (for local development)
- PostgreSQL (if not using Docker)

## Local Development

### Using Docker Compose

1. Clone the repository
2. Set environment variables in `.env` file or export them:
   ```bash
   export BOOMERANGME_API_KEY=your-api-key
   export DUTCHIE_API_KEY=your-api-key
   export DUTCHIE_WEBHOOK_SECRET=your-webhook-secret
   ```

3. Start services:
   ```bash
   docker-compose down
   docker-compose up -d
   // OR
   docker-compose up -d --build app // Rebuilding the app so Flyway picks up the new migration
   ```


4. Application will be available at `http://localhost:8080`

### Merchant Configuration

Merchants need to be configured in the `integration_configs` table with:
- `merchant_id` - Unique merchant identifier
- `boomerangme_api_key` - Merchant's Boomerangme API key
- `dutchie_api_key` - Merchant's Dutchie API key
- `dutchie_webhook_secret` - Webhook secret for HMAC validation
- `boomerangme_program_id` - Boomerangme program ID

## API Endpoints

### Webhooks

- `POST /webhooks/dutchie/order` - Receive order webhooks from Dutchie
- `POST /webhooks/dutchie/customer` - Receive customer webhooks from Dutchie
- `POST /webhooks/boomerangme/card` - Receive card webhooks from Boomerangme

### Health & Monitoring

- `GET /actuator/health` - Application health check
- `GET /actuator/metrics` - Application metrics
- `GET /actuator/prometheus` - Prometheus metrics endpoint

## Database Schema

The application uses Flyway for database migrations. Tables include:

- `integration_configs` - Merchant integration configurations
- `customers` - Customer mappings between systems
- `orders` - Order sync history
- `sync_logs` - Audit trail for sync operations
- `dead_letter_queue` - Failed syncs for manual review

## Points Calculation

By default, the system calculates points as 1 point per dollar spent. This can be configured in `OrderSyncService`.

## Building

```bash
./mvnw clean package
```

## Testing

```bash
./mvnw test
```

## License

Copyright (c) 2024 HeyMary

