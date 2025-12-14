package heymary.co.integrations.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {
    
    private final AdminApiKeyFilter adminApiKeyFilter;

    /**
     * BCrypt password encoder for secure token hashing and validation.
     * Used by CredentialsValidationService for merchant access token verification.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(adminApiKeyFilter, UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/webhooks/**").permitAll()
                .requestMatchers("/api/integration-configs/**").permitAll() // Auth handled by filter
                .requestMatchers("/api/sync-logs/**").permitAll() // Allow access to sync logs API
                .requestMatchers("/api/treez/integrations/**").permitAll() // Allow access to Treez integration API
                .requestMatchers("/", "/logs", "/logs.html", "/treez", "/treez.html", "/static/**", "/css/**", "/js/**").permitAll() // Allow access to UI pages
                .requestMatchers("/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/v3/api-docs/**", "/v3/api-docs.yaml").permitAll()
                .anyRequest().authenticated()
            );

        return http.build();
    }
}

