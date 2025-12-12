package heymary.co.integrations.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    // StringHttpMessageConverter is already configured by Spring Boot
    // This ensures UTF-8 encoding for string bodies
    public StringHttpMessageConverter stringHttpMessageConverter() {
        return new StringHttpMessageConverter(StandardCharsets.UTF_8);
    }
}

