package heymary.co.integrations.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "customers", indexes = {
    @Index(name = "idx_customers_merchant_id", columnList = "merchant_id"),
    @Index(name = "idx_customers_dutchie_id", columnList = "dutchie_customer_id"),
    @Index(name = "idx_customers_boomerangme_id", columnList = "boomerangme_card_id"),
    @Index(name = "idx_customers_email", columnList = "email")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_customers_merchant_dutchie", columnNames = {"merchant_id", "dutchie_customer_id"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "dutchie_customer_id", nullable = false)
    private String dutchieCustomerId;

    @Column(name = "boomerangme_card_id")
    private String boomerangmeCardId;

    @Column(name = "email")
    private String email;

    @Column(name = "phone", length = 50)
    private String phone;

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

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
}

