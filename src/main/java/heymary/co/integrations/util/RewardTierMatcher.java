package heymary.co.integrations.util;

import heymary.co.integrations.model.RewardTier;

import java.util.List;
import java.util.Optional;

/**
 * Utility class for matching discount titles to reward tiers.
 * Used for HM-prefixed discount redemption matching.
 */
public class RewardTierMatcher {

    private static final String HM_PREFIX = "HM";

    /**
     * Find matching reward tier for a discount title.
     * 
     * Checks if the discount title starts with "HM" prefix (case-insensitive),
     * extracts the remaining part after "HM", and matches it against reward tier names.
     * 
     * @param discountTitle Discount title from Treez (e.g., "HM 10$ off")
     * @param rewardTiers List of reward tiers to match against
     * @return Optional containing the matched RewardTier, or empty if no match found
     */
    public static Optional<RewardTier> findMatchingRewardTier(String discountTitle, List<RewardTier> rewardTiers) {
        if (discountTitle == null || discountTitle.isEmpty()) {
            return Optional.empty();
        }

        if (rewardTiers == null || rewardTiers.isEmpty()) {
            return Optional.empty();
        }

        // Check if title starts with "HM" (case-insensitive)
        String titleUpper = discountTitle.toUpperCase().trim();
        if (!titleUpper.startsWith(HM_PREFIX)) {
            return Optional.empty();
        }

        // Extract the remaining part after "HM" prefix
        String remainingTitle = discountTitle.substring(HM_PREFIX.length()).trim();

        if (remainingTitle.isEmpty()) {
            return Optional.empty();
        }

        // Find matching reward tier by name (case-insensitive, trimmed)
        return rewardTiers.stream()
                .filter(tier -> tier != null && tier.getName() != null)
                .filter(tier -> tier.getName().trim().equalsIgnoreCase(remainingTitle))
                .findFirst();
    }

    /**
     * Extract threshold points from a matched reward tier.
     * 
     * @param discountTitle Discount title from Treez
     * @param rewardTiers List of reward tiers to match against
     * @return Threshold points if match found, 0 otherwise
     */
    public static int getThresholdPoints(String discountTitle, List<RewardTier> rewardTiers) {
        return findMatchingRewardTier(discountTitle, rewardTiers)
                .map(RewardTier::getThreshold)
                .orElse(0);
    }
}
