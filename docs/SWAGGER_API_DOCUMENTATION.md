# Swagger/OpenAPI Documentation Guide

## Overview

The HeyMary Integrations Service now includes interactive API documentation powered by Swagger/OpenAPI. This provides a user-friendly interface to explore, test, and understand all available API endpoints.

## Accessing Swagger UI

### Local Development
Once the application is running, access Swagger UI at:

**Swagger UI**: http://localhost:8080/swagger-ui.html

**OpenAPI JSON**: http://localhost:8080/v3/api-docs

**OpenAPI YAML**: http://localhost:8080/v3/api-docs.yaml

### Production
Replace `localhost:8080` with your production API domain:

**Swagger UI**: https://api.heymary.co/swagger-ui.html

## Features

### 1. Interactive API Testing
- **Try It Out**: Test endpoints directly from the browser
- **Live Responses**: See real-time responses from the API
- **Request Examples**: Pre-filled example requests for each endpoint
- **Response Examples**: See what successful responses look like

### 2. Comprehensive Documentation
- **Endpoint Descriptions**: Detailed explanations of what each endpoint does
- **Parameters**: Clear documentation of all required and optional parameters
- **Request/Response Models**: Schema definitions for all DTOs
- **Status Codes**: All possible HTTP response codes explained

### 3. Organized by Categories

#### Credentials Validation
- POST `/api/check-credentials` - Validate credentials (array format)
- POST `/api/check-credentials-simple` - Validate credentials (simple format)

#### Integration Configuration
- GET `/api/integration-configs` - Get all integration configs
- GET `/api/integration-configs/{merchantId}` - Get specific config
- POST `/api/integration-configs` - Create new config
- PUT `/api/integration-configs/{merchantId}` - Update config
- DELETE `/api/integration-configs/{merchantId}` - Delete config
- POST `/api/integration-configs/{merchantId}/access-token` - Set access token

#### Webhooks
- POST `/webhooks/dutchie/order` - Dutchie order webhooks
- POST `/webhooks/dutchie/customer` - Dutchie customer webhooks
- POST `/webhooks/boomerangme/card` - Boomerangme card webhooks

#### Health & Monitoring
- GET `/actuator/health` - Health check
- GET `/actuator/info` - Application info

## How to Use Swagger UI

### Step 1: Navigate to Swagger UI
Open your browser and go to: http://localhost:8080/swagger-ui.html

### Step 2: Browse API Endpoints
- Endpoints are organized by tags (categories)
- Click on a tag to expand and see all endpoints in that category
- Click on an endpoint to see detailed documentation

### Step 3: Try an Endpoint

1. **Click "Try it out"** button on any endpoint
2. **Fill in parameters** (the UI will show required fields)
3. **Click "Execute"** to send the request
4. **View the response** below, including:
   - Response body (JSON)
   - Response headers
   - HTTP status code
   - Response time

### Example: Testing Credentials Validation

1. Navigate to **Credentials Validation** section
2. Click on **POST /api/check-credentials-simple**
3. Click **"Try it out"**
4. Edit the request body:
   ```json
   {
     "merchantId": "Evergreen",
     "accessToken": "my-secure-token-123"
   }
   ```
5. Click **"Execute"**
6. See the response:
   ```json
   {
     "isValid": true
   }
   ```

## Using the OpenAPI Spec

### Generate Client Libraries

You can use the OpenAPI specification to generate client libraries for various programming languages:

```bash
# Download the spec
curl http://localhost:8080/v3/api-docs -o heymary-api-spec.json

# Generate a JavaScript client using OpenAPI Generator
npx @openapitools/openapi-generator-cli generate \
  -i heymary-api-spec.json \
  -g javascript \
  -o ./generated-client

# Generate a Python client
npx @openapitools/openapi-generator-cli generate \
  -i heymary-api-spec.json \
  -g python \
  -o ./generated-client
```

### Import into API Testing Tools

The OpenAPI spec can be imported into:
- **Postman**: File → Import → Paste URL: `http://localhost:8080/v3/api-docs`
- **Insomnia**: Import → From URL → Paste URL
- **Bruno**: Import → OpenAPI/Swagger
- **Paw**: Import → OpenAPI

### Supported Languages for Code Generation
- JavaScript/TypeScript
- Python
- Java
- C#
- Go
- Ruby
- PHP
- Swift
- Kotlin
- And 50+ more...

## Swagger UI Tips & Tricks

### 1. Schemas Section
- Click on **"Schemas"** at the bottom to see all data models
- This shows the structure of request/response objects
- Includes field descriptions and examples

### 2. Authorize Button (Future)
If authentication is added in the future, you can:
- Click the **"Authorize"** button at the top
- Enter API keys or tokens
- All subsequent requests will include authentication

### 3. Example Values
- Swagger automatically populates example values
- You can modify these before executing
- Examples are taken from `@Schema` annotations

### 4. Model Descriptions
- Hover over field names to see descriptions
- Red asterisks (*) indicate required fields
- Green checkmarks indicate optional fields

### 5. Response Status Codes
- Each endpoint shows all possible status codes
- Click on a status code to see example responses
- Includes error responses with descriptions

## Customizing Request Examples

All examples in Swagger come from the `@ExampleObject` annotations in the code. For example:

```java
@ExampleObject(
    value = """
        {
          "merchantId": "Evergreen",
          "accessToken": "my-secure-token-123"
        }
        """
)
```

To customize for your use case, simply modify the values in the Swagger UI before executing.

## API Versioning

The current API version is `v1.0.0`. This is shown in:
- Swagger UI header
- OpenAPI spec metadata
- All documentation

Future versions will be indicated clearly in the Swagger interface.

## Security Information in Swagger

Swagger displays security requirements for each endpoint:
- **Public endpoints**: No authentication shown
- **Protected endpoints**: Will show required authentication (when implemented)

Current endpoints are public for development but should be secured in production.

## Exporting API Documentation

### Export as JSON
```bash
curl http://localhost:8080/v3/api-docs > heymary-api.json
```

### Export as YAML
```bash
curl http://localhost:8080/v3/api-docs.yaml > heymary-api.yaml
```

### Generate Static HTML
```bash
# Using redoc-cli
npx redoc-cli bundle http://localhost:8080/v3/api-docs -o heymary-api.html

# Using swagger-ui-cli
npx swagger-ui-cli generate http://localhost:8080/v3/api-docs
```

## Integration with Development Tools

### VSCode REST Client
Create a `.http` file with:
```http
@baseUrl = http://localhost:8080

### Check Credentials
POST {{baseUrl}}/api/check-credentials-simple
Content-Type: application/json

{
  "merchantId": "Evergreen",
  "accessToken": "my-secure-token-123"
}
```

### Postman Collection
1. Import OpenAPI spec into Postman
2. All endpoints automatically available as collection
3. Examples pre-populated
4. Easy environment variable management

## Troubleshooting

### Swagger UI not loading
1. Check application is running: `docker-compose ps`
2. Verify URL: http://localhost:8080/swagger-ui.html
3. Check browser console for errors
4. Clear browser cache

### "Failed to load API definition"
1. Verify OpenAPI endpoint: http://localhost:8080/v3/api-docs
2. Check application logs: `docker-compose logs app`
3. Ensure springdoc dependency is in pom.xml

### Endpoints not showing
1. Restart application after code changes
2. Check controllers have `@Tag` annotations
3. Verify SecurityConfig permits Swagger endpoints

### "Try it out" not working
1. Check CORS settings in SecurityConfig
2. Verify endpoint is accessible (not 401/403)
3. Check request format matches examples

## Best Practices

### For API Consumers
1. **Start with Swagger UI** to understand available endpoints
2. **Test in Swagger** before writing code
3. **Export OpenAPI spec** for client generation
4. **Bookmark Swagger URL** for quick reference

### For API Developers
1. **Add detailed descriptions** to all endpoints
2. **Provide realistic examples** for requests/responses
3. **Document all status codes** including errors
4. **Keep schemas up-to-date** with code changes
5. **Use tags** to organize endpoints logically

## Screenshots Guide

### Main Swagger UI
- Lists all API endpoints grouped by category
- Shows HTTP methods with color coding (GET=blue, POST=green, etc.)
- Expandable sections for each endpoint

### Endpoint Detail View
- Full endpoint documentation
- "Try it out" button for testing
- Request body editor
- Response viewer with syntax highlighting

### Schemas View
- All data models documented
- Field types and constraints
- Example values
- Required vs optional fields

## Additional Resources

- **SpringDoc Documentation**: https://springdoc.org/
- **OpenAPI Specification**: https://swagger.io/specification/
- **OpenAPI Generator**: https://openapi-generator.tech/
- **Swagger Editor**: https://editor.swagger.io/

## Next Steps

1. **Explore the API** using Swagger UI
2. **Test endpoints** with "Try it out"
3. **Generate a client** for your programming language
4. **Import into Postman** for team collaboration
5. **Share Swagger URL** with other developers

---

**Swagger UI URL**: http://localhost:8080/swagger-ui.html

Happy exploring! 🚀

