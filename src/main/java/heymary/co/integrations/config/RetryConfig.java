package heymary.co.integrations.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

@Configuration
@EnableRetry
public class RetryConfig {

    @Value("${app.retry.max-attempts:3}")
    private int maxAttempts;

    @Value("${app.retry.initial-interval:1000}")
    private long initialInterval;

    @Value("${app.retry.multiplier:2.0}")
    private double multiplier;

    @Value("${app.retry.max-interval:10000}")
    private long maxInterval;

    public RetryTemplate retryTemplate() {
        RetryTemplate retryTemplate = new RetryTemplate();

        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy();
        retryPolicy.setMaxAttempts(maxAttempts);
        retryTemplate.setRetryPolicy(retryPolicy);

        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(initialInterval);
        backOffPolicy.setMultiplier(multiplier);
        backOffPolicy.setMaxInterval(maxInterval);
        retryTemplate.setBackOffPolicy(backOffPolicy);

        return retryTemplate;
    }
}

