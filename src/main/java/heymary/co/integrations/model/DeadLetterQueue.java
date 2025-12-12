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
@Table(name = "dead_letter_queue", indexes = {
    @Index(name = "idx_dlq_merchant_id", columnList = "merchant_id"),
    @Index(name = "idx_dlq_resolved", columnList = "resolved, created_at"),
    @Index(name = "idx_dlq_entity", columnList = "entity_type, entity_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeadLetterQueue {

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

    @Column(name = "error_message", nullable = false, columnDefinition = "TEXT")
    private String errorMessage;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_payload", nullable = false, columnDefinition = "jsonb")
    private String requestPayload;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "max_retries", nullable = false)
    @Builder.Default
    private Integer maxRetries = 3;

    @Column(name = "last_attempt_at", nullable = false)
    @Builder.Default
    private LocalDateTime lastAttemptAt = LocalDateTime.now();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "resolved", nullable = false)
    @Builder.Default
    private Boolean resolved = false;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolution_notes", columnDefinition = "TEXT")
    private String resolutionNotes;

    public enum SyncType {
        ORDER, CUSTOMER, POINTS
    }

    public enum EntityType {
        ORDER, CUSTOMER
    }
}

