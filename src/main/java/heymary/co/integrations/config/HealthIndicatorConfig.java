package heymary.co.integrations.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class HealthIndicatorConfig {

    @Bean
    public HealthIndicator databaseHealthIndicator(JdbcTemplate jdbcTemplate) {
        return new HealthIndicator() {
            @Override
            public Health health() {
                try {
                    jdbcTemplate.queryForObject("SELECT 1", Integer.class);
                    return Health.up()
                            .withDetail("database", "PostgreSQL")
                            .build();
                } catch (Exception e) {
                    return Health.down()
                            .withDetail("database", "PostgreSQL")
                            .withDetail("error", e.getMessage())
                            .build();
                }
            }
        };
    }
}

