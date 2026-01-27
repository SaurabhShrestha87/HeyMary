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

    @Enumerated(EnumType.STRING)
    @Column(name = "integration_type", nullable = false, length = 50)
    @Builder.Default
    private IntegrationType integrationType = IntegrationType.DUTCHIE; // Default for backward compatibility

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

    @Column(name = "default_template_id", nullable = false, unique = true)
    private Integer defaultTemplateId;

    // One-to-One relationship with Template
    // Each integration config has exactly one unique template
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "default_template_id", referencedColumnName = "template_id", insertable = false, updatable = false)
    private Template defaultTemplate;

    @Column(name = "reward_program_points_per_dollar", precision = 10, scale = 2)
    private java.math.BigDecimal rewardProgramPointsPerDollar; // Points per dollar spent (e.g., 10.00 = 10 points per $1)

    // Treez-specific configuration
    @Column(name = "treez_api_key", length = 500)
    private String treezApiKey;  // API key for token generation

    @Column(name = "treez_client_id", length = 500)
    private String treezClientId;  // Client ID for API requests

    @Column(name = "treez_auth_header", length = 500)
    @Deprecated  // No longer used - tokens are fetched dynamically
    private String treezAuthHeader;

    @Column(name = "treez_dispensary_id", length = 100)
    private String treezDispensaryId;

    @Column(name = "treez_webhook_secret", length = 500)
    private String treezWebhookSecret;  // Bearer token for webhook authentication
    
    // Treez token management (transient - not stored in DB)
    @Transient
    private String treezAccessToken;  // Current access token
    
    @Transient
    private LocalDateTime treezTokenExpiresAt;  // Token expiration time

    @Enumerated(EnumType.STRING)
    @Column(name = "customer_match_type", length = 20)
    @Builder.Default
    private CustomerMatchType customerMatchType = CustomerMatchType.PHONE; // Default to PHONE for customer matching

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

