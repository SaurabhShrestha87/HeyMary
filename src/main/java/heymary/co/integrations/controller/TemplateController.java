package heymary.co.integrations.controller;

import heymary.co.integrations.model.RewardProgram;
import heymary.co.integrations.model.RewardTier;
import heymary.co.integrations.model.Template;
import heymary.co.integrations.model.TemplateCustomField;
import heymary.co.integrations.repository.IntegrationConfigRepository;
import heymary.co.integrations.repository.RewardProgramRepository;
import heymary.co.integrations.repository.RewardTierRepository;
import heymary.co.integrations.repository.TemplateCustomFieldRepository;
import heymary.co.integrations.repository.TemplateRepository;
import heymary.co.integrations.service.TemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@RequestMapping("/api/templates")
@RequiredArgsConstructor
@Tag(name = "Templates", description = "Manage Boomerangme loyalty card templates")
@SecurityRequirement(name = "AdminApiKey")
public class TemplateController {

    private final TemplateService templateService;
    private final TemplateRepository templateRepository;
    private final IntegrationConfigRepository integrationConfigRepository;
    private final RewardTierRepository rewardTierRepository;
    private final RewardProgramRepository rewardProgramRepository;
    private final TemplateCustomFieldRepository templateCustomFieldRepository;

    @GetMapping("/{templateId}")
    @Operation(
        summary = "Get template by ID",
        description = "Retrieve template details including rewards, reward tiers, and custom fields from the database by template ID"
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Template found"),
        @ApiResponse(responseCode = "404", description = "Template not found")
    })
    public ResponseEntity<TemplateDetailResponse> getTemplate(@PathVariable Integer templateId) {
        Optional<Template> templateOpt = templateRepository.findByTemplateId(templateId);
        
        if (templateOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        
        Template template = templateOpt.get();
        
        // Fetch related data
        List<RewardTier> rewardTiers = rewardTierRepository.findByTemplateId(templateId);
        Optional<RewardProgram> rewardProgram = rewardProgramRepository.findByTemplateId(templateId);
        List<TemplateCustomField> customFields = templateCustomFieldRepository.findByTemplateId(templateId);
        
        TemplateDetailResponse response = new TemplateDetailResponse(
            template,
            rewardTiers,
            rewardProgram.orElse(null),
            customFields
        );
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/merchant/{merchantId}")
    @Operation(
        summary = "Get template by merchant ID",
        description = "Retrieve the template associated with a specific merchant's integration config"
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Template found"),
        @ApiResponse(responseCode = "404", description = "Merchant or template not found")
    })
    public ResponseEntity<Template> getTemplateByMerchant(@PathVariable String merchantId) {
        return integrationConfigRepository.findByMerchantId(merchantId)
                .map(config -> {
                    Optional<Template> template = templateRepository.findByTemplateId(config.getDefaultTemplateId());
                    return template.map(ResponseEntity::ok)
                            .orElse(ResponseEntity.notFound().build());
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    @Operation(
        summary = "List all templates",
        description = "Retrieve all templates stored in the database"
    )
    public ResponseEntity<List<Template>> getAllTemplates() {
        List<Template> templates = templateRepository.findAll();
        return ResponseEntity.ok(templates);
    }

    @PostMapping("/{merchantId}/fetch/{templateId}")
    @Operation(
        summary = "Fetch and sync template from Boomerangme API",
        description = "Fetch template data from Boomerangme API and sync it to the database. " +
                     "This will create or update the template, custom fields, reward tiers, and reward program."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Template fetched and synced successfully"),
        @ApiResponse(responseCode = "404", description = "Merchant not found"),
        @ApiResponse(responseCode = "400", description = "Invalid template ID or API error")
    })
    public ResponseEntity<TemplateResponse> fetchTemplate(
            @PathVariable String merchantId,
            @PathVariable Integer templateId) {
        
        // Verify merchant exists
        if (integrationConfigRepository.findByMerchantId(merchantId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new TemplateResponse(null, "Merchant not found: " + merchantId));
        }

        try {
            log.info("Fetching template {} from Boomerangme API for merchant: {}", templateId, merchantId);
            Template template = templateService.syncTemplateFromApi(merchantId, templateId);
            log.info("Template {} fetched and synced successfully for merchant: {}", templateId, merchantId);
            
            return ResponseEntity.ok(new TemplateResponse(
                    template,
                    "Template fetched and synced successfully from Boomerangme API"
            ));
        } catch (Exception e) {
            log.error("Error fetching template {} for merchant {}: {}", templateId, merchantId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new TemplateResponse(null, "Failed to fetch template: " + e.getMessage()));
        }
    }

    @PostMapping("/{merchantId}/sync")
    @Operation(
        summary = "Sync template for merchant",
        description = "Sync the template associated with a merchant's integration config from Boomerangme API. " +
                     "Uses the default_template_id from the integration config."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Template synced successfully"),
        @ApiResponse(responseCode = "404", description = "Merchant not found or template ID not configured"),
        @ApiResponse(responseCode = "400", description = "API error")
    })
    public ResponseEntity<TemplateResponse> syncTemplateForMerchant(@PathVariable String merchantId) {
        return integrationConfigRepository.findByMerchantId(merchantId)
                .map(config -> {
                    if (config.getDefaultTemplateId() == null) {
                        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                .body(new TemplateResponse(null, "No default_template_id configured for merchant: " + merchantId));
                    }

                    try {
                        log.info("Syncing template {} for merchant: {}", config.getDefaultTemplateId(), merchantId);
                        Template template = templateService.syncTemplateFromApi(merchantId, config.getDefaultTemplateId());
                        log.info("Template {} synced successfully for merchant: {}", config.getDefaultTemplateId(), merchantId);
                        
                        return ResponseEntity.ok(new TemplateResponse(
                                template,
                                "Template synced successfully from Boomerangme API"
                        ));
                    } catch (Exception e) {
                        log.error("Error syncing template for merchant {}: {}", merchantId, e.getMessage(), e);
                        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                .body(new TemplateResponse(null, "Failed to sync template: " + e.getMessage()));
                    }
                })
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new TemplateResponse(null, "Merchant not found: " + merchantId)));
    }

    /**
     * Response wrapper for template operations
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class TemplateResponse {
        private Template template;
        private String message;
    }

    /**
     * Detailed template response including rewards and related data
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class TemplateDetailResponse {
        private Template template;
        private List<RewardTier> rewardTiers;
        private RewardProgram rewardProgram;
        private List<TemplateCustomField> customFields;
    }
}
