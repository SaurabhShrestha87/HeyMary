package heymary.co.integrations.service;

import com.fasterxml.jackson.databind.JsonNode;
import heymary.co.integrations.model.Customer;
import heymary.co.integrations.model.IntegrationConfig;
import heymary.co.integrations.model.SyncLog;
import heymary.co.integrations.repository.CustomerRepository;
import heymary.co.integrations.repository.IntegrationConfigRepository;
import heymary.co.integrations.repository.SyncLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PointsSyncService {

    private final BoomerangmeApiClient boomerangmeApiClient;
    private final CustomerRepository customerRepository;
    private final IntegrationConfigRepository integrationConfigRepository;
    private final SyncLogRepository syncLogRepository;
    private final DeadLetterQueueService deadLetterQueueService;

    /**
     * Sync points balance from Boomerangme to local database
     */
    @Async("syncTaskExecutor")
    @Transactional
    public void syncPointsFromBoomerangme(String merchantId, String cardId) {
        log.info("Syncing points from Boomerangme for card: {}", cardId);
        
        try {
            IntegrationConfig config = integrationConfigRepository
                    .findByMerchantIdAndEnabledTrue(merchantId)
                    .orElseThrow(() -> new RuntimeException("Integration config not found or disabled for merchant: " + merchantId));

            Optional<Customer> customerOpt = customerRepository
                    .findByMerchantIdAndBoomerangmeCardId(merchantId, cardId);

            if (customerOpt.isEmpty()) {
                log.warn("Customer not found for Boomerangme card: {}", cardId);
                return;
            }

            Customer customer = customerOpt.get();

            // Get current points from Boomerangme
            JsonNode cardResponse = boomerangmeApiClient.getCard(
                    config.getBoomerangmeApiKey(),
                    cardId
            ).block();

            if (cardResponse != null && cardResponse.has("points")) {
                int points = cardResponse.get("points").asInt();
                customer.setTotalPoints(points);
                customer.setSyncedAt(LocalDateTime.now());
                customerRepository.save(customer);

                createSyncLog(merchantId, SyncLog.SyncType.POINTS, cardId, 
                        SyncLog.SyncStatus.SUCCESS, null, 
                        String.format("{\"card_id\": \"%s\"}", cardId),
                        String.format("{\"points\": %d}", points));

                log.info("Successfully synced points {} for card {}", points, cardId);
            } else {
                throw new RuntimeException("Invalid response from Boomerangme API");
            }

        } catch (Exception e) {
            log.error("Error syncing points for merchant {}: {}", merchantId, e.getMessage(), e);
            
            createSyncLog(merchantId, SyncLog.SyncType.POINTS, cardId, 
                    SyncLog.SyncStatus.FAILED, e.getMessage(), 
                    String.format("{\"card_id\": \"%s\"}", cardId), null);
            
            deadLetterQueueService.addToDeadLetterQueue(
                    merchantId,
                    DeadLetterQueueService.SyncType.POINTS,
                    DeadLetterQueueService.EntityType.CUSTOMER,
                    cardId,
                    e.getMessage(),
                    String.format("{\"card_id\": \"%s\"}", cardId)
            );
        }
    }

    private void createSyncLog(String merchantId, SyncLog.SyncType syncType, String entityId,
                               SyncLog.SyncStatus status, String errorMessage, String requestPayload, 
                               String responsePayload) {
        SyncLog syncLog = SyncLog.builder()
                .merchantId(merchantId)
                .syncType(syncType)
                .entityType(SyncLog.EntityType.CUSTOMER)
                .entityId(entityId)
                .sourceSystem(SyncLog.SystemType.BOOMERANGME)
                .targetSystem(SyncLog.SystemType.BOOMERANGME)
                .status(status)
                .errorMessage(errorMessage)
                .requestPayload(requestPayload)
                .responsePayload(responsePayload)
                .build();

        if (status == SyncLog.SyncStatus.SUCCESS) {
            syncLog.setCompletedAt(LocalDateTime.now());
        }

        syncLogRepository.save(syncLog);
    }
}

