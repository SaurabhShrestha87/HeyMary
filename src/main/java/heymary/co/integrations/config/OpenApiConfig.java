package heymary.co.integrations.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

/**
 * OpenAPI/Swagger configuration for HeyMary Integrations Service API documentation.
 * 
 * Swagger UI will be available at: http://localhost:8080/swagger-ui.html
 * OpenAPI JSON spec: http://localhost:8080/v3/api-docs
 */
@Configuration
public class OpenApiConfig {

    @Value("${spring.application.name:HeyMary Integrations}")
    private String applicationName;

    @Bean
    public OpenAPI customOpenAPI() {
        SecurityScheme adminApiKeyScheme = new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER)
                .name("X-Admin-API-Key")
                .description("Admin API Key for accessing configuration endpoints");
        
        return new OpenAPI()
                .addSecurityItem(new SecurityRequirement().addList("AdminApiKey"))
                .components(new io.swagger.v3.oas.models.Components()
                        .addSecuritySchemes("AdminApiKey", adminApiKeyScheme))
                .info(new Info()
                        .title("HeyMary Integrations Service API")
                        .version("1.0.0")
                        .description("""
                                **HeyMary Integrations Service** provides secure merchant authentication and integration management.
                                
                                ## Key Features
                                - **Credentials Validation**: Secure BCrypt-based merchant authentication
                                - **Integration Management**: Configure merchant integrations with Dutchie and Boomerangme
                                - **Webhook Processing**: Handle webhooks from integrated services
                                - **Health Monitoring**: Actuator endpoints for system health checks
                                
                                ## Authentication
                                Most endpoints use BCrypt-hashed access tokens for merchant authentication.
                                See the Credentials Validation endpoints for details.
                                
                                ## Getting Started
                                1. Set up a merchant integration configuration
                                2. Generate and set an access token for the merchant
                                3. Use the credentials validation endpoint to authenticate
                                
                                For detailed documentation, see the [Credentials Quick Start Guide](../docs/CREDENTIALS_QUICK_START.md).
                                """)
                        .contact(new Contact()
                                .name("HeyMary Development Team")
                                .email("shresthasaurabh86@gmail.com")
                                .url("https://heymary.co"))
                        .license(new License()
                                .name("Proprietary")
                                .url("https://heymary.co/license")))
                .servers(Arrays.asList(
                        new Server()
                                .url("https://doubly-subglobular-kayden.ngrok-free.dev/")
                                .description("Dev Server"),
                        new Server()
                                .url("https://integrations.heymary.co")
                                .description("Production API Server")))
                .tags(Arrays.asList(
                        new Tag()
                                .name("Credentials Validation")
                                .description("Endpoints for validating merchant credentials using BCrypt-hashed access tokens"),
                        new Tag()
                                .name("Integration Configuration")
                                .description("Manage merchant integration configurations for Dutchie and Boomerangme"),
                        new Tag()
                                .name("Webhooks")
                                .description("Webhook endpoints for receiving events from integrated services"),
                        new Tag()
                                .name("Health & Monitoring")
                                .description("System health checks and monitoring endpoints")));
    }
}

