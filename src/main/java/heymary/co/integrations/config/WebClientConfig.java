package heymary.co.integrations.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

@Configuration
public class WebClientConfig {

    @Value("${boomerangme.api.timeout:30000}")
    private int boomerangmeTimeout;

    @Value("${dutchie.api.timeout:30000}")
    private int dutchieTimeout;

    @Value("${treez.api.timeout:30000}")
    private int treezTimeout;

    @Bean
    public WebClient.Builder webClientBuilder() {
        ConnectionProvider connectionProvider = ConnectionProvider.builder("webclient-pool")
                .maxConnections(100)
                .pendingAcquireTimeout(Duration.ofSeconds(30))
                .evictInBackground(Duration.ofSeconds(60))
                .build();

        HttpClient httpClient = HttpClient.create(connectionProvider)
                .responseTimeout(Duration.ofSeconds(30));

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient));
    }

    @Bean(name = "boomerangmeWebClient")
    public WebClient boomerangmeWebClient(WebClient.Builder webClientBuilder,
                                         @Value("${boomerangme.api.base-url}") String baseUrl) {
        return webClientBuilder
                .baseUrl(baseUrl)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    @Bean(name = "dutchieWebClient")
    public WebClient dutchieWebClient(WebClient.Builder webClientBuilder,
                                     @Value("${dutchie.api.base-url}") String baseUrl) {
        return webClientBuilder
                .baseUrl(baseUrl)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    @Bean(name = "treezWebClient")
    public WebClient treezWebClient(WebClient.Builder webClientBuilder,
                                   @Value("${treez.api.base-url}") String baseUrl) {
        return webClientBuilder
                .baseUrl(baseUrl)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }
}

