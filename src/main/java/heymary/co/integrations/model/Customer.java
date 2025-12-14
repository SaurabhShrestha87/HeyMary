package heymary.co.integrations.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "customers", indexes = {
    @Index(name = "idx_customers_merchant_id", columnList = "merchant_id"),
    @Index(name = "idx_customers_external_id", columnList = "external_customer_id"),
    @Index(name = "idx_customers_card_id", columnList = "card_id"),
    @Index(name = "idx_customers_treez_email", columnList = "treez_email"),
    @Index(name = "idx_customers_treez_phone", columnList = "treez_phone"),
    @Index(name = "idx_customers_email", columnList = "email"), // Legacy index
    @Index(name = "idx_customers_integration_type", columnList = "integration_type")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_customers_merchant_external", columnNames = {"merchant_id", "external_customer_id", "integration_type"}),
    @UniqueConstraint(name = "uk_customers_card", columnNames = {"card_id"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false, length = 100)
    private String merchantId;

    // POS Integration Type (TREEZ or DUTCHIE)
    @Enumerated(EnumType.STRING)
    @Column(name = "integration_type", nullable = false, length = 50)
    private IntegrationType integrationType;

    // External POS customer ID (from Dutchie, Treez, or other POS systems)
    @Column(name = "external_customer_id", length = 100)
    private String externalCustomerId;

    // One-to-One relationship with Card (Boomerangme card)
    @OneToOne
    @JoinColumn(name = "card_id", unique = true)
    private Card card;

    // Treez Customer Information (stored separately from Boomerangme data)
    @Column(name = "treez_email", length = 255)
    private String treezEmail;

    @Column(name = "treez_phone", length = 50)
    private String treezPhone;

    @Column(name = "treez_first_name", length = 100)
    private String treezFirstName;

    @Column(name = "treez_last_name", length = 100)
    private String treezLastName;

    @Column(name = "treez_birth_date")
    private LocalDate treezBirthDate;

    // Legacy fields - kept for backward compatibility (deprecated)
    @Column(name = "email", length = 255)
    @Deprecated
    private String email;

    @Column(name = "phone", length = 50)
    @Deprecated
    private String phone;

    @Column(name = "first_name", length = 100)
    @Deprecated
    private String firstName;

    @Column(name = "last_name", length = 100)
    @Deprecated
    private String lastName;

    @Column(name = "birth_date")
    @Deprecated
    private LocalDate birthDate;

    // Loyalty Information
    @Column(name = "total_points", nullable = false)
    @Builder.Default
    private Integer totalPoints = 0;

    @Column(name = "synced_at")
    private LocalDateTime syncedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // Legacy field - kept for backward compatibility
    @Column(name = "boomerangme_card_id", length = 100)
    @Deprecated
    private String boomerangmeCardId;
}

