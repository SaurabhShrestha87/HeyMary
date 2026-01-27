package heymary.co.integrations.util;

import heymary.co.integrations.model.RewardTier;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test cases for RewardTierMatcher utility class
 */
class RewardTierMatcherTest {

    @Test
    void testFindMatchingRewardTier_Success() {
        List<RewardTier> rewardTiers = createTestRewardTiers();
        
        Optional<RewardTier> result = RewardTierMatcher.findMatchingRewardTier("HM 10$ off", rewardTiers);
        
        assertTrue(result.isPresent(), "Should find matching reward tier");
        assertEquals("10$ off", result.get().getName());
        assertEquals(50, result.get().getThreshold());
    }

    @Test
    void testFindMatchingRewardTier_CaseInsensitive() {
        List<RewardTier> rewardTiers = createTestRewardTiers();
        
        // Test various case combinations
        assertTrue(RewardTierMatcher.findMatchingRewardTier("HM 10$ OFF", rewardTiers).isPresent());
        assertTrue(RewardTierMatcher.findMatchingRewardTier("hm 10$ off", rewardTiers).isPresent());
        assertTrue(RewardTierMatcher.findMatchingRewardTier("Hm 10$ Off", rewardTiers).isPresent());
    }

    @Test
    void testFindMatchingRewardTier_WithWhitespace() {
        List<RewardTier> rewardTiers = createTestRewardTiers();
        
        // Test with extra spaces
        assertTrue(RewardTierMatcher.findMatchingRewardTier("HM  10$ off", rewardTiers).isPresent());
        assertTrue(RewardTierMatcher.findMatchingRewardTier("HM 10$ off ", rewardTiers).isPresent());
        assertTrue(RewardTierMatcher.findMatchingRewardTier("HM  Free Gummies ", rewardTiers).isPresent());
    }

    @Test
    void testFindMatchingRewardTier_NoMatch() {
        List<RewardTier> rewardTiers = createTestRewardTiers();
        
        Optional<RewardTier> result = RewardTierMatcher.findMatchingRewardTier("HM Unknown Reward", rewardTiers);
        
        assertFalse(result.isPresent(), "Should not find match for unknown reward");
    }

    @Test
    void testFindMatchingRewardTier_NonHMPrefix() {
        List<RewardTier> rewardTiers = createTestRewardTiers();
        
        Optional<RewardTier> result = RewardTierMatcher.findMatchingRewardTier("EMPLOYEE DISCOUNT", rewardTiers);
        
        assertFalse(result.isPresent(), "Should not match non-HM discounts");
    }

    @Test
    void testFindMatchingRewardTier_HMPrefixOnly() {
        List<RewardTier> rewardTiers = createTestRewardTiers();
        
        Optional<RewardTier> result = RewardTierMatcher.findMatchingRewardTier("HM", rewardTiers);
        
        assertFalse(result.isPresent(), "Should not match when only HM prefix present");
    }

    @Test
    void testFindMatchingRewardTier_NullTitle() {
        List<RewardTier> rewardTiers = createTestRewardTiers();
        
        Optional<RewardTier> result = RewardTierMatcher.findMatchingRewardTier(null, rewardTiers);
        
        assertFalse(result.isPresent(), "Should return empty for null title");
    }

    @Test
    void testFindMatchingRewardTier_EmptyTitle() {
        List<RewardTier> rewardTiers = createTestRewardTiers();
        
        Optional<RewardTier> result = RewardTierMatcher.findMatchingRewardTier("", rewardTiers);
        
        assertFalse(result.isPresent(), "Should return empty for empty title");
    }

    @Test
    void testFindMatchingRewardTier_EmptyRewardTiers() {
        Optional<RewardTier> result = RewardTierMatcher.findMatchingRewardTier("HM 10$ off", new ArrayList<>());
        
        assertFalse(result.isPresent(), "Should return empty for empty reward tiers list");
    }

    @Test
    void testFindMatchingRewardTier_NullRewardTiers() {
        Optional<RewardTier> result = RewardTierMatcher.findMatchingRewardTier("HM 10$ off", null);
        
        assertFalse(result.isPresent(), "Should return empty for null reward tiers list");
    }

    @Test
    void testGetThresholdPoints_Success() {
        List<RewardTier> rewardTiers = createTestRewardTiers();
        
        int points = RewardTierMatcher.getThresholdPoints("HM 10$ off", rewardTiers);
        
        assertEquals(50, points, "Should return threshold points (50), not value");
    }

    @Test
    void testGetThresholdPoints_MultipleMatches() {
        List<RewardTier> rewardTiers = createTestRewardTiers();
        
        // First match should be returned
        int points1 = RewardTierMatcher.getThresholdPoints("HM 10$ off", rewardTiers);
        assertEquals(50, points1);
        
        int points2 = RewardTierMatcher.getThresholdPoints("HM 60% off", rewardTiers);
        assertEquals(100, points2);
        
        int points3 = RewardTierMatcher.getThresholdPoints("HM Free Gummies ", rewardTiers);
        assertEquals(150, points3);
    }

    @Test
    void testGetThresholdPoints_NoMatch() {
        List<RewardTier> rewardTiers = createTestRewardTiers();
        
        int points = RewardTierMatcher.getThresholdPoints("HM Unknown", rewardTiers);
        
        assertEquals(0, points, "Should return 0 when no match found");
    }

    @Test
    void testGetThresholdPoints_UsesThresholdNotValue() {
        // Create a tier with different threshold and value
        RewardTier tier = RewardTier.builder()
                .name("Test Reward")
                .threshold(75)  // Points required
                .value(BigDecimal.valueOf(25.0))  // Reward value
                .type(1)
                .build();
        
        List<RewardTier> rewardTiers = List.of(tier);
        
        int points = RewardTierMatcher.getThresholdPoints("HM Test Reward", rewardTiers);
        
        assertEquals(75, points, "Should use threshold (75), not value (25)");
        assertNotEquals(25, points, "Should NOT use value field");
    }

    /**
     * Helper method to create test reward tiers matching the template.json sample
     */
    private List<RewardTier> createTestRewardTiers() {
        List<RewardTier> tiers = new ArrayList<>();

        // "10$ off" - threshold: 50, value: 10
        tiers.add(RewardTier.builder()
                .templateId(955805)
                .tierId(898627)
                .name("10$ off")
                .type(1)  // cashback
                .threshold(50)
                .value(BigDecimal.valueOf(10.0))
                .build());

        // "60% off" - threshold: 100, value: 60
        tiers.add(RewardTier.builder()
                .templateId(955805)
                .tierId(939585)
                .name("60% off")
                .type(2)  // discount percentage
                .threshold(100)
                .value(BigDecimal.valueOf(60.0))
                .build());

        // "Free Gummies " - threshold: 150, value: 10 (note trailing space)
        tiers.add(RewardTier.builder()
                .templateId(955805)
                .tierId(939667)
                .name("Free Gummies ")
                .type(0)  // gift
                .threshold(150)
                .value(BigDecimal.valueOf(10.0))
                .build());

        return tiers;
    }
}
