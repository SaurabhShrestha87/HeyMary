package heymary.co.integrations.service;

import heymary.co.integrations.model.DeadLetterQueue;
import heymary.co.integrations.model.SyncLog;
import heymary.co.integrations.repository.DeadLetterQueueRepository;
import heymary.co.integrations.repository.SyncLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final SyncLogRepository syncLogRepository;
    private final DeadLetterQueueRepository deadLetterQueueRepository;

    /**
     * Check for repeated failures and send alerts
     * Runs every 5 minutes
     */
    @Scheduled(fixedRate = 300000) // 5 minutes
    public void checkForRepeatedFailures() {
        LocalDateTime since = LocalDateTime.now().minusHours(1);
        List<SyncLog> recentFailures = syncLogRepository.findRecentFailures(since);

        // Group by merchant and entity type
        long failureCount = recentFailures.size();
        if (failureCount > 10) {
            log.error("ALERT: {} sync failures detected in the last hour. Manual intervention may be required.", failureCount);
            // TODO: Integrate with notification service (email, Slack, etc.)
        }

        // Check dead letter queue
        List<DeadLetterQueue> unresolvedDlq = deadLetterQueueRepository.findByResolvedFalse();
        if (unresolvedDlq.size() > 20) {
            log.error("ALERT: {} items in dead letter queue. Manual review required.", unresolvedDlq.size());
            // TODO: Integrate with notification service
        }
    }

    /**
     * Log summary of sync operations
     * Runs every hour
     */
    @Scheduled(cron = "0 0 * * * *") // Every hour
    public void logSyncSummary() {
        LocalDateTime since = LocalDateTime.now().minusHours(1);
        List<SyncLog> recentLogs = syncLogRepository.findRecentFailures(since);

        long successCount = syncLogRepository.findAll().stream()
                .filter(log -> log.getStatus() == SyncLog.SyncStatus.SUCCESS 
                        && log.getCreatedAt().isAfter(since))
                .count();

        long failureCount = recentLogs.size();

        log.info("Sync Summary (last hour): {} successful, {} failed", successCount, failureCount);
    }
}

