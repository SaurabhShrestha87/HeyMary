package heymary.co.integrations.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.Base64;

@Entity
@Table(name = "integration_configs", indexes = {
    @Index(name = "idx_integration_configs_merchant_id", columnList = "merchant_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IntegrationConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false, unique = true)
    private String merchantId;

    @Column(name = "boomerangme_api_key", nullable = false, length = 500)
    private String boomerangmeApiKey;

    @Column(name = "dutchie_api_key", nullable = false, length = 500)
    private String dutchieApiKey;

    @Column(name = "dutchie_auth_header", length = 500)
    private String dutchieAuthHeader;

    @Column(name = "dutchie_webhook_secret", length = 500)
    private String dutchieWebhookSecret;

    @Column(name = "boomerangme_webhook_secret", length = 500)
    private String boomerangmeWebhookSecret;

    @Column(name = "boomerangme_program_id")
    private String boomerangmeProgramId;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Automatically compute and set the Dutchie auth header when API key is set or updated
     */
    @PrePersist
    @PreUpdate
    private void computeDutchieAuthHeader() {
        if (dutchieApiKey != null && !dutchieApiKey.isEmpty()) {
            String credentials = dutchieApiKey + ":";
            String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());
            this.dutchieAuthHeader = "Basic " + encoded;
        }
    }
}

