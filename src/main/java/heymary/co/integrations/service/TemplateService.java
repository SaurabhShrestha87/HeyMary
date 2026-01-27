package heymary.co.integrations.service;

import com.fasterxml.jackson.databind.JsonNode;
import heymary.co.integrations.model.*;
import heymary.co.integrations.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for managing Boomerangme templates.
 * Handles fetching, parsing, and syncing template data from Boomerangme API.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateService {

    private final BoomerangmeApiClient boomerangmeApiClient;
    private final TemplateRepository templateRepository;
    private final TemplateCustomFieldRepository templateCustomFieldRepository;
    private final RewardTierRepository rewardTierRepository;
    private final RewardProgramRepository rewardProgramRepository;
    private final IntegrationConfigRepository integrationConfigRepository;


    /**
     * Fetch and sync template from Boomerangme API.
     * 
     * @param merchantId Merchant identifier
     * @param templateId Template ID to fetch
     * @return Synced Template entity
     */
    @Transactional
    public Template syncTemplateFromApi(String merchantId, Integer templateId) {
        log.info("Syncing template {} for merchant {}", templateId, merchantId);

        // Get integration config to retrieve API key
        IntegrationConfig config = integrationConfigRepository.findByMerchantId(merchantId)
                .orElseThrow(() -> new RuntimeException("Integration config not found for merchant: " + merchantId));

        // Fetch template from Boomerangme API
        JsonNode response = boomerangmeApiClient.getTemplate(config.getBoomerangmeApiKey(), templateId)
                .block();

        if (response == null || !response.has("data")) {
            throw new RuntimeException("Invalid template response from Boomerangme API");
        }

        JsonNode templateData = response.get("data");
        return saveTemplate(merchantId, templateData, config);
    }

    /**
     * Save or update template from Boomerangme API response.
     * 
     * @param merchantId Merchant identifier
     * @param templateData Template data from API response
     * @param config Integration config (for reward program points per dollar)
     * @return Saved Template entity
     */
    @Transactional
    public Template saveTemplate(String merchantId, JsonNode templateData, IntegrationConfig config) {
        Integer templateId = templateData.get("id").asInt();
        log.info("Saving template {} for merchant {}", templateId, merchantId);

        // Find or create template
        Template template = templateRepository.findByMerchantIdAndTemplateId(merchantId, templateId)
                .orElse(Template.builder()
                        .templateId(templateId)
                        .merchantId(merchantId)
                        .build());

        // Update template fields
        if (templateData.has("companyId")) {
            template.setCompanyId(templateData.get("companyId").asInt());
        }
        if (templateData.has("name")) {
            template.setName(templateData.get("name").asText());
        }
        if (templateData.has("type")) {
            template.setType(templateData.get("type").asText());
        }
        if (templateData.has("installLink")) {
            template.setInstallLink(templateData.get("installLink").asText());
        }
        if (templateData.has("qrLink")) {
            template.setQrLink(templateData.get("qrLink").asText());
        }
        if (templateData.has("createdAt")) {
            template.setCreatedAt(parseDateTime(templateData.get("createdAt").asText()));
        }
        if (templateData.has("updatedAt")) {
            template.setUpdatedAt(parseDateTime(templateData.get("updatedAt").asText()));
        }
        template.setSyncedAt(LocalDateTime.now());

        template = templateRepository.save(template);
        log.info("Saved template: {} ({})", template.getName(), templateId);

        // Save custom fields
        if (templateData.has("customFields") && templateData.get("customFields").isArray()) {
            saveCustomFields(templateId, templateData.get("customFields"));
        }

        // Save reward tiers
        if (templateData.has("rewardTiers") && templateData.get("rewardTiers").isArray()) {
            saveRewardTiers(templateId, templateData.get("rewardTiers"));
        }

        // Try to extract reward program mechanics
        // Note: The template API response may not include reward program mechanics directly
        // If not available, use the manual column in integration_configs
        extractAndSaveRewardProgram(templateId, templateData, config);

        return template;
    }

    /**
     * Save custom fields for a template.
     * Uses upsert logic: find existing fields and update, or create new ones.
     */
    @Transactional
    private void saveCustomFields(Integer templateId, JsonNode customFieldsArray) {
        // Get existing fields for this template
        List<TemplateCustomField> existingFields = templateCustomFieldRepository.findByTemplateId(templateId);
        
        List<TemplateCustomField> fieldsToSave = new ArrayList<>();
        for (JsonNode fieldData : customFieldsArray) {
            Integer fieldId = fieldData.get("id").asInt();
            
            // Find existing field or create new one
            TemplateCustomField field = existingFields.stream()
                    .filter(f -> f.getFieldId().equals(fieldId))
                    .findFirst()
                    .orElse(TemplateCustomField.builder()
                            .templateId(templateId)
                            .fieldId(fieldId)
                            .build());
            
            // Update field properties
            field.setName(fieldData.has("name") ? fieldData.get("name").asText() : "");
            field.setType(fieldData.has("type") ? fieldData.get("type").asText() : "");
            field.setOrder(fieldData.has("order") ? fieldData.get("order").asInt() : 0);
            field.setRequired(fieldData.has("required") && fieldData.get("required").asBoolean());
            field.setUniqueField(fieldData.has("unique") && fieldData.get("unique").asBoolean());
            field.setValue(fieldData.has("value") && !fieldData.get("value").isNull() 
                    ? fieldData.get("value").asText() : null);
            
            fieldsToSave.add(field);
        }
        
        // Delete fields that are no longer in the template
        List<Integer> currentFieldIds = new ArrayList<>();
        for (JsonNode fieldData : customFieldsArray) {
            if (fieldData.has("id") && !fieldData.get("id").isNull()) {
                currentFieldIds.add(fieldData.get("id").asInt());
            }
        }
        existingFields.stream()
                .filter(f -> !currentFieldIds.contains(f.getFieldId()))
                .forEach(templateCustomFieldRepository::delete);

        templateCustomFieldRepository.saveAll(fieldsToSave);
        log.info("Saved {} custom fields for template {}", fieldsToSave.size(), templateId);
    }

    /**
     * Save reward tiers for a template.
     * Uses upsert logic: find existing tiers and update, or create new ones.
     */
    @Transactional
    private void saveRewardTiers(Integer templateId, JsonNode rewardTiersArray) {
        // Get existing tiers for this template
        List<RewardTier> existingTiers = rewardTierRepository.findByTemplateId(templateId);
        
        List<RewardTier> tiersToSave = new ArrayList<>();
        for (JsonNode tierData : rewardTiersArray) {
            Integer tierId = tierData.get("id").asInt();
            
            // Find existing tier or create new one
            RewardTier tier = existingTiers.stream()
                    .filter(t -> t.getTierId().equals(tierId))
                    .findFirst()
                    .orElse(RewardTier.builder()
                            .templateId(templateId)
                            .tierId(tierId)
                            .build());
            
            // Update tier properties
            tier.setName(tierData.has("name") ? tierData.get("name").asText() : "");
            tier.setType(tierData.has("type") ? tierData.get("type").asInt() : 0);
            tier.setThreshold(tierData.has("threshold") ? tierData.get("threshold").asInt() : 0);
            tier.setValue(tierData.has("value") 
                    ? BigDecimal.valueOf(tierData.get("value").asDouble()) 
                    : BigDecimal.ZERO);
            tier.setValueLimit(tierData.has("valueLimit") ? tierData.get("valueLimit").asInt() : 0);
            tier.setUsageLimit(tierData.has("usageLimit") ? tierData.get("usageLimit").asInt() : 0);
            
            tiersToSave.add(tier);
        }
        
        // Delete tiers that are no longer in the template
        List<Integer> currentTierIds = new ArrayList<>();
        for (JsonNode tierData : rewardTiersArray) {
            currentTierIds.add(tierData.get("id").asInt());
        }
        existingTiers.stream()
                .filter(t -> !currentTierIds.contains(t.getTierId()))
                .forEach(rewardTierRepository::delete);

        rewardTierRepository.saveAll(tiersToSave);
        log.info("Saved {} reward tiers for template {}", tiersToSave.size(), templateId);
    }

    /**
     * Extract and save reward program mechanics.
     * If not available in API response, use manual configuration from integration_configs.
     */
    @Transactional
    private void extractAndSaveRewardProgram(Integer templateId, JsonNode templateData, IntegrationConfig config) {
        // Try to find reward program mechanics in the template data
        // The API response structure may vary, so we check multiple possible locations
        String mechanicsType = null;
        Integer pointsPerUnit = null;
        BigDecimal unitAmount = null;

        // Check if reward program info is in the template data
        // This is a placeholder - actual API response structure may differ
        if (templateData.has("rewardProgram")) {
            JsonNode rewardProgram = templateData.get("rewardProgram");
            if (rewardProgram.has("mechanicsType")) {
                mechanicsType = rewardProgram.get("mechanicsType").asText();
            }
            if (rewardProgram.has("pointsPerUnit")) {
                pointsPerUnit = rewardProgram.get("pointsPerUnit").asInt();
            }
            if (rewardProgram.has("unitAmount")) {
                unitAmount = BigDecimal.valueOf(rewardProgram.get("unitAmount").asDouble());
            }
        }

        // If not found in API response, check if we have manual configuration
        if (mechanicsType == null && config.getRewardProgramPointsPerDollar() != null) {
            mechanicsType = "spend";
            pointsPerUnit = config.getRewardProgramPointsPerDollar().intValue();
            unitAmount = BigDecimal.ONE; // $1 per unit
            log.info("Using manual reward program configuration: {} points per ${}", pointsPerUnit, unitAmount);
        }

        // Save or update reward program
        if (mechanicsType != null) {
            RewardProgram program = rewardProgramRepository.findByTemplateId(templateId)
                    .orElse(RewardProgram.builder()
                            .templateId(templateId)
                            .build());

            program.setMechanicsType(mechanicsType);
            if (pointsPerUnit != null) {
                program.setPointsPerUnit(pointsPerUnit);
            }
            if (unitAmount != null) {
                program.setUnitAmount(unitAmount);
            }

            rewardProgramRepository.save(program);
            log.info("Saved reward program for template {}: {} mechanics, {} points per unit", 
                    templateId, mechanicsType, pointsPerUnit);
        } else {
            log.warn("No reward program mechanics found for template {}. " +
                    "Please configure reward_program_points_per_dollar in integration_configs or check API response structure.", 
                    templateId);
        }
    }

    /**
     * Parse ISO 8601 datetime string to LocalDateTime.
     */
    private LocalDateTime parseDateTime(String dateTimeStr) {
        try {
            // Remove timezone offset for parsing (e.g., "2025-11-11T21:30:58+00:00" -> "2025-11-11T21:30:58")
            String cleaned = dateTimeStr.replaceAll("([+-]\\d{2}):(\\d{2})$", "");
            return LocalDateTime.parse(cleaned, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            log.warn("Failed to parse datetime: {}", dateTimeStr, e);
            return null;
        }
    }

    /**
     * Get template by template ID.
     */
    public Template getTemplate(Integer templateId) {
        return templateRepository.findByTemplateId(templateId)
                .orElseThrow(() -> new RuntimeException("Template not found: " + templateId));
    }

    /**
     * Get template by merchant ID and template ID.
     */
    public Template getTemplate(String merchantId, Integer templateId) {
        return templateRepository.findByMerchantIdAndTemplateId(merchantId, templateId)
                .orElseThrow(() -> new RuntimeException("Template not found for merchant " + merchantId + ": " + templateId));
    }
}
