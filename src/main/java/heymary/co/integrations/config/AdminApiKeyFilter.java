package heymary.co.integrations.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
public class AdminApiKeyFilter extends OncePerRequestFilter {

    @Value("${app.admin.api-key:admin-default-key-change-me}")
    private String adminApiKey;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        String path = request.getRequestURI();
        
        // Apply to admin endpoints: integration-configs and templates
        if (path.startsWith("/api/integration-configs") || path.startsWith("/api/templates")) {
            // Try both header name variations (case-insensitive)
            String apiKey = request.getHeader("X-Admin-API-Key");
            if (apiKey == null) {
                apiKey = request.getHeader("x-admin-api-key"); // lowercase variant
            }
            
            log.debug("Admin endpoint access attempt: path={}, apiKey={}, expected={}", 
                    path, apiKey != null ? "***" : "null", adminApiKey != null ? "***" : "null");
            
            if (apiKey == null || !apiKey.equals(adminApiKey)) {
                log.warn("Unauthorized access attempt to admin endpoint: {} from IP: {}. Header value: {}", 
                        path, request.getRemoteAddr(), apiKey != null ? "provided but invalid" : "missing");
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Forbidden\",\"message\":\"Valid X-Admin-API-Key header required\"}");
                return;
            }
            
            log.debug("Admin API key validated successfully for path: {}", path);
        }
        
        filterChain.doFilter(request, response);
    }
}

