package heymary.co.integrations.util;

import com.fasterxml.jackson.databind.JsonNode;
import heymary.co.integrations.model.RewardTier;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Helper for building and parsing Treez customer notes with a structured
 * points/rewards section. User notes above the delimiter are preserved;
 * the section between "--- DO NOT EDIT" markers is auto-generated from
 * RewardTier data and customer points.
 *
 * Format:
 * <pre>
 * Some random notes blah blah (persists after we update points etc)
 * --- DO NOT EDIT
 * POINTS - 250
 * [10$ off] - 50
 * [60% off] - 100
 * [2 Free 710 Labs - Black Mamba #6 - Live Rosin Pod - Tier 2 - 1g] - 250
 * --- DO NOT EDIT
 * </pre>
 */
public final class CustomerNotesHelper {

    private static final String DELIMITER = "--- DO NOT EDIT";

    private CustomerNotesHelper() {
    }

    /**
     * Build notes string with user notes (preserved) and structured points/rewards section.
     *
     * @param existingNotes Current notes from Treez (may be null) - user notes above delimiter are preserved
     * @param points        Customer's current points balance
     * @param rewardTiers   Reward tiers for the template (sorted by threshold ascending)
     * @return Combined notes string ready for Treez customer update
     */
    public static String buildNotesWithPointsAndRewards(String existingNotes, int points,
                                                        List<RewardTier> rewardTiers) {
        String userNotes = extractUserNotes(existingNotes);
        String structuredSection = buildStructuredSection(points, rewardTiers);

        if (userNotes == null || userNotes.isBlank()) {
            return structuredSection;
        }

        return userNotes.trim() + "\n\n" + structuredSection;
    }

    /**
     * Extract user-editable notes (content above the first delimiter).
     * Everything before the first "---" line is considered user notes.
     */
    public static String extractUserNotes(String notes) {
        if (notes == null || notes.isEmpty()) {
            return "";
        }

        int delimiterIndex = notes.indexOf(DELIMITER);
        if (delimiterIndex < 0) {
            // Also check for plain "---" as fallback
            delimiterIndex = notes.indexOf("---");
        }

        if (delimiterIndex < 0) {
            return notes;
        }

        return notes.substring(0, delimiterIndex).trim();
    }

    /**
     * Build the structured section with points and reward tiers.
     * Format: POINTS - {points}
     * [offer name] - {threshold}
     * ...
     */
    public static String buildStructuredSection(int points, List<RewardTier> rewardTiers) {
        StringBuilder sb = new StringBuilder();
        sb.append(DELIMITER).append("\n");
        sb.append("POINTS - ").append(points).append("\n");

        if (rewardTiers != null && !rewardTiers.isEmpty()) {
            List<RewardTier> sorted = rewardTiers.stream()
                    .filter(t -> t != null && t.getName() != null)
                    .sorted(Comparator.comparingInt(RewardTier::getThreshold))
                    .collect(Collectors.toList());

            for (RewardTier tier : sorted) {
                sb.append("[").append(tier.getName().trim()).append("] - ").append(tier.getThreshold()).append("\n");
            }
        }

        sb.append(DELIMITER);
        return sb.toString();
    }

    /**
     * Extract notes from Treez API response. Handles data array, data object, or direct customer format.
     *
     * @param response Treez API response (from getCustomer, findCustomerByPhone, findCustomerByEmail)
     * @return Notes string, or null if not found
     */
    public static String extractNotesFromTreezResponse(JsonNode response) {
        if (response == null) return null;
        JsonNode customerNode = null;
        if (response.has("data")) {
            JsonNode data = response.get("data");
            if (data.isArray() && data.size() > 0) {
                customerNode = data.get(0);
            } else if (data.isObject()) {
                customerNode = data;
            }
        } else {
            customerNode = response;
        }
        if (customerNode == null || !customerNode.has("notes") || customerNode.get("notes").isNull()) return null;
        return customerNode.get("notes").asText();
    }
}
