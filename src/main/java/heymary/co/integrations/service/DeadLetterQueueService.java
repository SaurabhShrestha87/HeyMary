package heymary.co.integrations.service;

import heymary.co.integrations.model.DeadLetterQueue;
import heymary.co.integrations.repository.DeadLetterQueueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeadLetterQueueService {

    private final DeadLetterQueueRepository deadLetterQueueRepository;

    public enum SyncType {
        ORDER, CUSTOMER, POINTS
    }

    public enum EntityType {
        ORDER, CUSTOMER
    }

    @Transactional
    public void addToDeadLetterQueue(String merchantId, SyncType syncType, EntityType entityType,
                                    String entityId, String errorMessage, String requestPayload) {
        // Check if already exists
        Optional<DeadLetterQueue> existing = deadLetterQueueRepository
                .findByEntityTypeAndEntityIdAndResolvedFalse(
                        convertEntityType(entityType),
                        entityId
                );

        if (existing.isPresent()) {
            DeadLetterQueue dlq = existing.get();
            dlq.setRetryCount(dlq.getRetryCount() + 1);
            dlq.setLastAttemptAt(LocalDateTime.now());
            dlq.setErrorMessage(errorMessage);
            deadLetterQueueRepository.save(dlq);
            log.warn("Updated existing DLQ entry for {}: {}", entityType, entityId);
        } else {
            DeadLetterQueue dlq = DeadLetterQueue.builder()
                    .merchantId(merchantId)
                    .syncType(convertSyncType(syncType))
                    .entityType(convertEntityType(entityType))
                    .entityId(entityId)
                    .errorMessage(errorMessage)
                    .requestPayload(requestPayload)
                    .retryCount(0)
                    .maxRetries(3)
                    .resolved(false)
                    .build();
            
            deadLetterQueueRepository.save(dlq);
            log.error("Added to dead letter queue: {} - {}: {}", syncType, entityType, entityId);
        }
    }

    @Transactional
    public void markAsResolved(Long dlqId, String resolutionNotes) {
        Optional<DeadLetterQueue> dlqOpt = deadLetterQueueRepository.findById(dlqId);
        if (dlqOpt.isPresent()) {
            DeadLetterQueue dlq = dlqOpt.get();
            dlq.setResolved(true);
            dlq.setResolvedAt(LocalDateTime.now());
            dlq.setResolutionNotes(resolutionNotes);
            deadLetterQueueRepository.save(dlq);
            log.info("Marked DLQ entry {} as resolved", dlqId);
        }
    }

    private DeadLetterQueue.SyncType convertSyncType(SyncType syncType) {
        return switch (syncType) {
            case ORDER -> DeadLetterQueue.SyncType.ORDER;
            case CUSTOMER -> DeadLetterQueue.SyncType.CUSTOMER;
            case POINTS -> DeadLetterQueue.SyncType.POINTS;
        };
    }

    private DeadLetterQueue.EntityType convertEntityType(EntityType entityType) {
        return switch (entityType) {
            case ORDER -> DeadLetterQueue.EntityType.ORDER;
            case CUSTOMER -> DeadLetterQueue.EntityType.CUSTOMER;
        };
    }
}

