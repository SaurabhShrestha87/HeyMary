package heymary.co.integrations.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "orders", indexes = {
    @Index(name = "idx_orders_merchant_id", columnList = "merchant_id"),
    @Index(name = "idx_orders_external_order_id", columnList = "external_order_id"),
    @Index(name = "idx_orders_integration_type", columnList = "integration_type"),
    @Index(name = "idx_orders_customer_id", columnList = "customer_id"),
    @Index(name = "idx_orders_sync_status", columnList = "points_synced, synced_at")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_orders_external_order", columnNames = {"merchant_id", "external_order_id", "integration_type"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "integration_type", nullable = false, length = 50)
    private IntegrationType integrationType;

    @Column(name = "external_order_id", nullable = false, length = 100)
    private String externalOrderId; // dutchie_order_id or treez_ticket_id

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Column(name = "order_total", nullable = false, precision = 10, scale = 2)
    private BigDecimal orderTotal;

    @Column(name = "points_earned", nullable = false)
    @Builder.Default
    private Integer pointsEarned = 0;

    @Column(name = "points_synced", nullable = false)
    @Builder.Default
    private Boolean pointsSynced = false;

    @Column(name = "order_date", nullable = false)
    private LocalDateTime orderDate;

    @Column(name = "synced_at")
    private LocalDateTime syncedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

