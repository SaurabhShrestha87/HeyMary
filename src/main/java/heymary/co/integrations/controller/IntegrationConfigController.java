package heymary.co.integrations.controller;

import heymary.co.integrations.model.IntegrationConfig;
import heymary.co.integrations.repository.IntegrationConfigRepository;
import heymary.co.integrations.service.InitialSyncService;
import heymary.co.integrations.service.TemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/integration-configs")
@RequiredArgsConstructor
@Tag(name = "Integration Configuration", description = "Manage merchant integration configurations for Dutchie and Boomerangme")
@SecurityRequirement(name = "AdminApiKey")
public class IntegrationConfigController {

    private final IntegrationConfigRepository integrationConfigRepository;
    private final InitialSyncService initialSyncService;
    private final TemplateService templateService;

    @GetMapping
    public ResponseEntity<List<IntegrationConfig>> getAllConfigs() {
        List<IntegrationConfig> configs = integrationConfigRepository.findAll();
        return ResponseEntity.ok(configs);
    }

    @GetMapping("/{merchantId}")
    public ResponseEntity<IntegrationConfig> getConfig(@PathVariable String merchantId) {
        Optional<IntegrationConfig> config = integrationConfigRepository.findByMerchantId(merchantId);
        return config.map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @Operation(
        summary = "Create integration configuration",
        description = "Create a new integration configuration for a merchant. " +
                     "This will automatically trigger an initial sync of all cards and customers from Boomerangme."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Integration config created successfully"),
        @ApiResponse(responseCode = "409", description = "Merchant already exists"),
        @ApiResponse(responseCode = "400", description = "Invalid request body")
    })
    public ResponseEntity<IntegrationConfigResponse> createConfig(@Valid @RequestBody IntegrationConfig config) {
        // Check if merchant already exists
        if (integrationConfigRepository.findByMerchantId(config.getMerchantId()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        // Validate that default_template_id is provided
        if (config.getDefaultTemplateId() == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new IntegrationConfigResponse(null, "default_template_id is required"));
        }

        // Save config first (needed for template sync)
        IntegrationConfig saved = integrationConfigRepository.save(config);
        log.info("Created integration config for merchant: {}", config.getMerchantId());
        
        // Fetch and sync template from Boomerangme API
        try {
            log.info("Fetching template {} for merchant: {}", config.getDefaultTemplateId(), config.getMerchantId());
            templateService.syncTemplateFromApi(config.getMerchantId(), config.getDefaultTemplateId());
            log.info("Template {} synced successfully for merchant: {}", config.getDefaultTemplateId(), config.getMerchantId());
        } catch (Exception e) {
            log.error("Failed to fetch template {} for merchant {}: {}", 
                    config.getDefaultTemplateId(), config.getMerchantId(), e.getMessage(), e);
            // Don't fail the config creation, but log the error
            // Template can be synced later via webhook or manual sync
        }
        
        // Trigger initial sync asynchronously
        log.info("Triggering initial sync for merchant: {}", config.getMerchantId());
        initialSyncService.performInitialSync(saved);
        
        IntegrationConfigResponse response = new IntegrationConfigResponse(
            saved,
            "Integration config created successfully. Template synced and initial sync of cards and customers has been triggered in the background."
        );
        
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
    
    /**
     * Response wrapper for integration config creation
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class IntegrationConfigResponse {
        private IntegrationConfig config;
        private String message;
    }

    @PutMapping("/{merchantId}")
    public ResponseEntity<IntegrationConfig> updateConfig(
            @PathVariable String merchantId,
            @Valid @RequestBody IntegrationConfig config) {
        
        Optional<IntegrationConfig> existing = integrationConfigRepository.findByMerchantId(merchantId);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        config.setId(existing.get().getId());
        config.setMerchantId(merchantId); // Ensure merchant ID matches
        IntegrationConfig updated = integrationConfigRepository.save(config);
        log.info("Updated integration config for merchant: {}", merchantId);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{merchantId}")
    public ResponseEntity<Void> deleteConfig(@PathVariable String merchantId) {
        Optional<IntegrationConfig> config = integrationConfigRepository.findByMerchantId(merchantId);
        if (config.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        integrationConfigRepository.delete(config.get());
        log.info("Deleted integration config for merchant: {}", merchantId);
        return ResponseEntity.noContent().build();
    }
    
    @PostMapping("/{merchantId}/sync")
    @Operation(
        summary = "Trigger initial sync",
        description = "Manually trigger an initial sync of all cards and customers from Boomerangme for a specific merchant. " +
                     "This is useful for re-syncing data or if the automatic sync failed during config creation."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "202", description = "Initial sync triggered successfully"),
        @ApiResponse(responseCode = "404", description = "Merchant not found")
    })
    public ResponseEntity<SyncResponse> triggerInitialSync(@PathVariable String merchantId) {
        Optional<IntegrationConfig> config = integrationConfigRepository.findByMerchantId(merchantId);
        if (config.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        log.info("Manually triggering initial sync for merchant: {}", merchantId);
        initialSyncService.performInitialSync(config.get());
        
        SyncResponse response = new SyncResponse(
            "Initial sync triggered successfully for merchant: " + merchantId,
            "The sync is running in the background. Check the sync logs for progress."
        );
        
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
    
    @PostMapping("/{merchantId}/reverse-sync-and-create")
    @Operation(
        summary = "Trigger reverse sync",
        description = "Manually trigger a reverse sync: fetch Treez customers and create Boomerangme customers/cards if missing. " +
                     "This syncs customers from Treez to Boomerangme (opposite direction of initial sync)." +
                     "Carefully consider the impact of this sync before triggering it " + 
                     "as it will create lots of new HeyMary cards and customers, for all previous Treez customers."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "202", description = "Reverse sync triggered successfully"),
        @ApiResponse(responseCode = "404", description = "Merchant not found")
    })
    public ResponseEntity<SyncResponse> triggerReverseSync(@PathVariable String merchantId) {
        Optional<IntegrationConfig> config = integrationConfigRepository.findByMerchantId(merchantId);
        if (config.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        log.info("Manually triggering reverse sync for merchant: {}", merchantId);
        initialSyncService.performReverseSync(config.get());
        
        SyncResponse response = new SyncResponse(
            "Reverse sync triggered successfully for merchant: " + merchantId,
            "The sync is running in the background. Check the sync logs for progress."
        );
        
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
    
    /**
     * Response for sync trigger endpoint
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SyncResponse {
        private String message;
        private String details;
    }
}

