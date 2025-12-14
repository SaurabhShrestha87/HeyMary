package heymary.co.integrations.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "sync_logs", indexes = {
    @Index(name = "idx_sync_logs_merchant_id", columnList = "merchant_id"),
    @Index(name = "idx_sync_logs_entity", columnList = "entity_type, entity_id"),
    @Index(name = "idx_sync_logs_status", columnList = "status"),
    @Index(name = "idx_sync_logs_created_at", columnList = "created_at"),
    @Index(name = "idx_sync_logs_failed", columnList = "status, created_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SyncLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "sync_type", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private SyncType syncType;

    @Column(name = "entity_type", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private EntityType entityType;

    @Column(name = "entity_id", nullable = false)
    private String entityId;

    @Column(name = "source_system", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private SystemType sourceSystem;

    @Column(name = "target_system", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private SystemType targetSystem;

    @Column(name = "status", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private SyncStatus status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_payload", columnDefinition = "jsonb")
    private String requestPayload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_payload", columnDefinition = "jsonb")
    private String responsePayload;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public enum SyncType {
        ORDER, CUSTOMER, POINTS
    }

    public enum EntityType {
        ORDER, CUSTOMER, POINTS
    }

    public enum SystemType {
        DUTCHIE, BOOMERANGME, TREEZ
    }

    public enum SyncStatus {
        SUCCESS, FAILED, RETRYING
    }
}

