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
        
        // Only apply to integration-configs endpoints
        if (path.startsWith("/api/integration-configs")) {
            String apiKey = request.getHeader("X-Admin-API-Key");
            
            if (apiKey == null || !apiKey.equals(adminApiKey)) {
                log.warn("Unauthorized access attempt to admin endpoint: {} from IP: {}", path, request.getRemoteAddr());
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Forbidden\",\"message\":\"Valid X-Admin-API-Key header required\"}");
                return;
            }
        }
        
        filterChain.doFilter(request, response);
    }
}

