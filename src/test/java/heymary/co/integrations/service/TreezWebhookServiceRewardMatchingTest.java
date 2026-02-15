package heymary.co.integrations.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import heymary.co.integrations.model.RewardTier;
import heymary.co.integrations.repository.RewardTierRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test cases for HM reward tier matching logic in TreezWebhookService
 * Tests the extractHMRedeemDiscountPoints method which matches discount titles
 * starting with "HM" prefix to reward tier names.
 */
@ExtendWith(MockitoExtension.class)
class TreezWebhookServiceRewardMatchingTest {

    @Mock
    private RewardTierRepository rewardTierRepository;

    private TreezWebhookService treezWebhookService;
    private ObjectMapper objectMapper;
    private Integer templateId = 955805;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        
        // Create service instance using reflection to set only the RewardTierRepository
        // Other dependencies are not needed since we're only testing the private method
        Constructor<TreezWebhookService> constructor = TreezWebhookService.class.getDeclaredConstructor(
                heymary.co.integrations.repository.SyncLogRepository.class,
                heymary.co.integrations.repository.CustomerRepository.class,
                heymary.co.integrations.repository.CardRepository.class,
                heymary.co.integrations.repository.OrderRepository.class,
                heymary.co.integrations.repository.IntegrationConfigRepository.class,
                BoomerangmeApiClient.class,
                TemplateService.class,
                heymary.co.integrations.repository.TemplateRepository.class,
                RewardTierRepository.class,
                ObjectMapper.class,
                DeadLetterQueueService.class
        );
        
        // Create service with null dependencies (we only need rewardTierRepository)
        treezWebhookService = constructor.newInstance(
                null, // syncLogRepository
                null, // customerRepository
                null, // cardRepository
                null, // orderRepository
                null, // integrationConfigRepository
                null, // boomerangmeApiClient
                null, // templateService
                null, // templateRepository
                rewardTierRepository, // This is the only one we need
                objectMapper,
                null  // deadLetterQueueService
        );
    }

    /**
     * Test successful matching of HM discount to reward tier by name
     */
    @Test
    void testMatchHMDiscountToRewardTier_Success() throws Exception {
        // Setup: Create reward tiers
        List<RewardTier> rewardTiers = createTestRewardTiers();
        when(rewardTierRepository.findByTemplateId(templateId)).thenReturn(rewardTiers);

        // Setup: Create order data with HM discount
        String orderJson = """
            {
              "items": [
                {
                  "discounts": [
                    {
                      "discount_title": "HM 10$ off",
                      "discount_amount": 10.0
                    }
                  ]
                }
              ]
            }
            """;

        JsonNode orderData = objectMapper.readTree(orderJson);

        // Execute: Call private method via reflection
        int points = invokeExtractHMRedeemDiscountPoints(orderData, templateId);

        // Verify: Should return threshold points (50) from matched tier
        assertEquals(50, points, "Should return threshold points (50) from '10$ off' tier");
        verify(rewardTierRepository, times(1)).findByTemplateId(templateId);
    }

    /**
     * Test case-insensitive matching
     */
    @Test
    void testCaseInsensitiveMatching() throws Exception {
        List<RewardTier> rewardTiers = createTestRewardTiers();
        when(rewardTierRepository.findByTemplateId(templateId)).thenReturn(rewardTiers);

        // Test with different case variations
        String[] testCases = {
            "HM 10$ OFF",      // Uppercase
            "hm 10$ off",     // Lowercase
            "Hm 10$ Off",     // Mixed case
            "HM  10$ off",    // Extra spaces
        };

        for (String discountTitle : testCases) {
            String orderJson = String.format("""
                {
                  "items": [
                    {
                      "discounts": [
                        {
                          "discount_title": "%s",
                          "discount_amount": 10.0
                        }
                      ]
                    }
                  ]
                }
                """, discountTitle);

            JsonNode orderData = objectMapper.readTree(orderJson);
            int points = invokeExtractHMRedeemDiscountPoints(orderData, templateId);

            assertEquals(50, points, 
                String.format("Should match '%s' case-insensitively", discountTitle));
        }
    }

    /**
     * Test matching multiple HM discounts to different reward tiers
     */
    @Test
    void testMultipleHMDiscounts() throws Exception {
        List<RewardTier> rewardTiers = createTestRewardTiers();
        when(rewardTierRepository.findByTemplateId(templateId)).thenReturn(rewardTiers);

        String orderJson = """
            {
              "items": [
                {
                  "discounts": [
                    {
                      "discount_title": "HM 10$ off",
                      "discount_amount": 10.0
                    },
                    {
                      "discount_title": "HM 60% off",
                      "discount_amount": 60.0
                    }
                  ]
                },
                {
                  "discounts": [
                    {
                      "discount_title": "HM Free Gummies ",
                      "discount_amount": 10.0
                    }
                  ]
                }
              ]
            }
            """;

        JsonNode orderData = objectMapper.readTree(orderJson);
        int points = invokeExtractHMRedeemDiscountPoints(orderData, templateId);

        // Should sum all matched thresholds: 50 + 100 + 150 = 300
        assertEquals(300, points, "Should sum thresholds from all matched tiers");
    }

    /**
     * Test that non-HM discounts are ignored
     */
    @Test
    void testNonHMDiscountsIgnored() throws Exception {
        List<RewardTier> rewardTiers = createTestRewardTiers();
        when(rewardTierRepository.findByTemplateId(templateId)).thenReturn(rewardTiers);

        String orderJson = """
            {
              "items": [
                {
                  "discounts": [
                    {
                      "discount_title": "EMPLOYEE DISCOUNT",
                      "discount_amount": 20.0
                    },
                    {
                      "discount_title": "SENIOR DISCOUNT",
                      "discount_amount": 10.0
                    }
                  ]
                }
              ]
            }
            """;

        JsonNode orderData = objectMapper.readTree(orderJson);
        int points = invokeExtractHMRedeemDiscountPoints(orderData, templateId);

        assertEquals(0, points, "Should return 0 for non-HM discounts");
    }

    /**
     * Test that unmatched HM discounts return 0
     */
    @Test
    void testUnmatchedHMDiscount() throws Exception {
        List<RewardTier> rewardTiers = createTestRewardTiers();
        when(rewardTierRepository.findByTemplateId(templateId)).thenReturn(rewardTiers);

        String orderJson = """
            {
              "items": [
                {
                  "discounts": [
                    {
                      "discount_title": "HM Unknown Reward",
                      "discount_amount": 15.0
                    }
                  ]
                }
              ]
            }
            """;

        JsonNode orderData = objectMapper.readTree(orderJson);
        int points = invokeExtractHMRedeemDiscountPoints(orderData, templateId);

        assertEquals(0, points, "Should return 0 for unmatched HM discount");
    }

    /**
     * Test that threshold is used, not value
     */
    @Test
    void testUsesThresholdNotValue() throws Exception {
        // Create a tier with threshold=75 but value=25
        RewardTier tier = RewardTier.builder()
                .templateId(templateId)
                .tierId(999)
                .name("Test Reward")
                .threshold(75)  // Points required
                .value(BigDecimal.valueOf(25.0))  // Reward value
                .type(1)
                .build();

        List<RewardTier> rewardTiers = List.of(tier);
        when(rewardTierRepository.findByTemplateId(templateId)).thenReturn(rewardTiers);

        String orderJson = """
            {
              "items": [
                {
                  "discounts": [
                    {
                      "discount_title": "HM Test Reward",
                      "discount_amount": 25.0
                    }
                  ]
                }
              ]
            }
            """;

        JsonNode orderData = objectMapper.readTree(orderJson);
        int points = invokeExtractHMRedeemDiscountPoints(orderData, templateId);

        // Should return threshold (75), not value (25)
        assertEquals(75, points, "Should use threshold (75), not value (25)");
        assertNotEquals(25, points, "Should NOT use value field");
    }

    /**
     * Test empty items array
     */
    @Test
    void testEmptyItemsArray() throws Exception {
        String orderJson = """
            {
              "items": []
            }
            """;

        JsonNode orderData = objectMapper.readTree(orderJson);
        int points = invokeExtractHMRedeemDiscountPoints(orderData, templateId);

        assertEquals(0, points, "Should return 0 for empty items array");
        verify(rewardTierRepository, never()).findByTemplateId(any());
    }

    /**
     * Test missing items array
     */
    @Test
    void testMissingItemsArray() throws Exception {
        String orderJson = """
            {
              "order_number": "12345"
            }
            """;

        JsonNode orderData = objectMapper.readTree(orderJson);
        int points = invokeExtractHMRedeemDiscountPoints(orderData, templateId);

        assertEquals(0, points, "Should return 0 when items array is missing");
        verify(rewardTierRepository, never()).findByTemplateId(any());
    }

    /**
     * Test empty reward tiers list
     */
    @Test
    void testEmptyRewardTiers() throws Exception {
        when(rewardTierRepository.findByTemplateId(templateId)).thenReturn(new ArrayList<>());

        String orderJson = """
            {
              "items": [
                {
                  "discounts": [
                    {
                      "discount_title": "HM 10$ off",
                      "discount_amount": 10.0
                    }
                  ]
                }
              ]
            }
            """;

        JsonNode orderData = objectMapper.readTree(orderJson);
        int points = invokeExtractHMRedeemDiscountPoints(orderData, templateId);

        assertEquals(0, points, "Should return 0 when no reward tiers exist");
    }

    /**
     * Test HM prefix with no remaining text
     */
    @Test
    void testHMPrefixOnly() throws Exception {
        List<RewardTier> rewardTiers = createTestRewardTiers();
        when(rewardTierRepository.findByTemplateId(templateId)).thenReturn(rewardTiers);

        String orderJson = """
            {
              "items": [
                {
                  "discounts": [
                    {
                      "discount_title": "HM",
                      "discount_amount": 10.0
                    }
                  ]
                }
              ]
            }
            """;

        JsonNode orderData = objectMapper.readTree(orderJson);
        int points = invokeExtractHMRedeemDiscountPoints(orderData, templateId);

        assertEquals(0, points, "Should return 0 when HM prefix has no remaining text");
    }

    /**
     * Test matching with extra whitespace
     */
    @Test
    void testWhitespaceHandling() throws Exception {
        List<RewardTier> rewardTiers = createTestRewardTiers();
        when(rewardTierRepository.findByTemplateId(templateId)).thenReturn(rewardTiers);

        // Tier name has trailing space: "Free Gummies "
        String orderJson = """
            {
              "items": [
                {
                  "discounts": [
                    {
                      "discount_title": "HM  Free Gummies ",
                      "discount_amount": 10.0
                    }
                  ]
                }
              ]
            }
            """;

        JsonNode orderData = objectMapper.readTree(orderJson);
        int points = invokeExtractHMRedeemDiscountPoints(orderData, templateId);

        assertEquals(150, points, "Should match despite extra whitespace");
    }

    /**
     * Helper method to invoke private extractHMRedeemDiscounts method via reflection
     */
    private int invokeExtractHMRedeemDiscountPoints(JsonNode data, Integer templateId) throws Exception {
        Method method = TreezWebhookService.class.getDeclaredMethod(
                "extractHMRedeemDiscounts", JsonNode.class, Integer.class);
        method.setAccessible(true);
        Object result = method.invoke(treezWebhookService, data, templateId);
        return (int) result.getClass().getMethod("totalPoints").invoke(result);
    }

    /**
     * Helper method to create test reward tiers matching the template.json sample
     */
    private List<RewardTier> createTestRewardTiers() {
        List<RewardTier> tiers = new ArrayList<>();

        // "10$ off" - threshold: 50, value: 10
        tiers.add(RewardTier.builder()
                .templateId(templateId)
                .tierId(898627)
                .name("10$ off")
                .type(1)  // cashback
                .threshold(50)
                .value(BigDecimal.valueOf(10.0))
                .build());

        // "60% off" - threshold: 100, value: 60
        tiers.add(RewardTier.builder()
                .templateId(templateId)
                .tierId(939585)
                .name("60% off")
                .type(2)  // discount percentage
                .threshold(100)
                .value(BigDecimal.valueOf(60.0))
                .build());

        // "Free Gummies " - threshold: 150, value: 10 (note trailing space)
        tiers.add(RewardTier.builder()
                .templateId(templateId)
                .tierId(939667)
                .name("Free Gummies ")
                .type(0)  // gift
                .threshold(150)
                .value(BigDecimal.valueOf(10.0))
                .build());

        return tiers;
    }
}
