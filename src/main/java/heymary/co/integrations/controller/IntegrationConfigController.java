package heymary.co.integrations.controller;

import heymary.co.integrations.model.IntegrationConfig;
import heymary.co.integrations.repository.IntegrationConfigRepository;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
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
    public ResponseEntity<IntegrationConfig> createConfig(@Valid @RequestBody IntegrationConfig config) {
        // Check if merchant already exists
        if (integrationConfigRepository.findByMerchantId(config.getMerchantId()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        IntegrationConfig saved = integrationConfigRepository.save(config);
        log.info("Created integration config for merchant: {}", config.getMerchantId());
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
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
}

